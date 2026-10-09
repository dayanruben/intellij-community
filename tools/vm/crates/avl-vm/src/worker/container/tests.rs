//! The Apple `container` engine's suite. The parsers are pure and run on every host. The lifecycle tests run over the
//! fake `container` of the shared hypervisor fake, a shell script, so they are Unix only.
//!
//! The JSON answers below are the ones the CLI 1.5.0 printed on 2026-10-09, cut to the fields the controller reads.

use pretty_assertions::assert_eq;

use super::*;
use crate::worker::docker::{ContainerState, apple_container_id, apple_image_revision};

/// `system status --format json` of a server that runs, as the spike CLI printed it.
const RUNNING: &str = r#"{"client":{"appName":"container","build":"release","version":"1.5.0"},"host":{"architecture":"arm64","cpus":18},"paths":{"appRoot":"/Users/air/Library/Application Support/com.apple.container/","installRoot":"/opt/container/"},"resources":{"containersRunning":0},"server":{"appName":"container-apiserver","version":"1.5.0"},"status":"running"}"#;

#[test]
fn the_system_status_is_read_from_the_json_of_the_cli() {
    let status: SystemStatus = serde_json::from_str(RUNNING).unwrap();
    assert!(status.running());
    assert_eq!(status.paths.install_root, "/opt/container/");
    assert_eq!((status.client.version.as_str(), status.server.version.as_str()), ("1.5.0", "1.5.0"));
    assert_eq!(status.host.architecture, "arm64");
    let down: SystemStatus = serde_json::from_str(r#"{"status":"unregistered"}"#).unwrap();
    assert!(!down.running());
    assert_eq!(
        (major_version("1.5.0"), major_version("12.0"), major_version("")),
        (Some(1), Some(12), None)
    );
}

/// The install root of a CLI is the grandparent of its path, and the trailing `/` of the status does not matter.
#[test]
fn the_install_root_is_the_grandparent_of_the_cli() {
    let root = install_root_of(Path::new("/opt/container/bin/container"));
    assert_eq!(root, Path::new("/opt/container"));
    assert!(same_root(&root, "/opt/container/"));
    assert!(!same_root(&root, "/usr/local/"));
}

/// A loopback resolver, such as the one of a VPN client, and a scoped address are skipped. The first other nameserver
/// wins, and a host without one has none.
#[test]
fn the_nameserver_is_the_first_routable_one_of_the_host() {
    let vpn = "resolver #1\n  search domain[0] : localdomain\n  nameserver[0] : 127.0.2.2\n  nameserver[1] : 127.0.2.3\n";
    assert_eq!(first_routable_nameserver(vpn), None);
    let mixed = format!(
        "{vpn}\nresolver #2\n  nameserver[0] : fe80::1%en0\n  nameserver[1] : 0.0.0.0\n  nameserver[2] : 10.1.2.3\n\
         \nresolver #3\n  nameserver[0] : 192.168.1.1\n"
    );
    assert_eq!(first_routable_nameserver(&mixed), Some("10.1.2.3".parse().unwrap()));
    assert_eq!(
        first_routable_nameserver("  nameserver[0] : 2001:db8::53\n"),
        Some("2001:db8::53".parse().unwrap())
    );
}

/// `inspect` answers JSON: a created container and an exited one are both `stopped`, with no exit code. The id is the
/// name. An answer of another shape is no state.
#[test]
fn the_apple_inspect_is_read_from_its_json() {
    let answer = |state: &str| {
        format!(
            r#"[{{"configuration":{{"id":"air-docker-1","creationDate":"2026-10-09T07:29:06Z"}},"id":"air-docker-1","status":{{"networks":[],"state":"{state}"}}}}]"#
        )
    };
    assert_eq!(ContainerState::parse_apple(&answer("running")), Some(ContainerState::Running));
    assert_eq!(ContainerState::parse_apple(&answer("stopped")), Some(ContainerState::Stopped));
    assert_eq!(
        ContainerState::parse_apple(&answer("stopping")),
        Some(ContainerState::Other("stopping".to_owned()))
    );
    assert_eq!(ContainerState::parse_apple("[]"), Some(ContainerState::Absent));
    for broken in [
        "",
        "running/0",
        "[{}]",
        r#"[{"status":{"state":"Running"}}]"#,
        r#"{"status":"running"}"#,
    ] {
        assert_eq!(ContainerState::parse_apple(broken), None, "{broken:?}");
    }
    assert_eq!(ContainerState::Stopped.as_str(), "stopped");
    assert_eq!(apple_container_id(&answer("running")).as_deref(), Some("air-docker-1"));
    assert_eq!(apple_container_id("[]"), None);
}

/// The revision label sits in the image config of a variant. An image without it says the empty revision, as in the
/// Docker dialect.
#[test]
fn the_apple_image_revision_is_read_from_a_variant() {
    let image = r#"[{"configuration":{},"id":"x","variants":[{"platform":{"os":"linux"}},{"config":{"config":{"Labels":{"org.opencontainers.image.revision":"5b1e0c0ffee1"}}}}]}]"#;
    assert_eq!(apple_image_revision(image), "5b1e0c0ffee1");
    assert_eq!(apple_image_revision(r#"[{"variants":[]}]"#), "");
    assert_eq!(apple_image_revision("not json"), "");
}

/// The controller parses the JSON of one major version of the CLI, so the pin of `container.MODULE.bazel` stays at it.
/// The pin test data is read on Unix only, as the other pin tests do.
#[cfg(unix)]
#[test]
fn the_pinned_container_has_the_major_version_the_controller_speaks() {
    let pinned = crate::worker::pin::module_pin("container.MODULE.bazel", "AIR_CONTAINER_VERSION");
    assert_eq!(pinned[0], 1, "{pinned:?}");
}

// --- the lifecycle over the fake `container` -----------------------------------------------------------------------

#[cfg(unix)]
mod lifecycle {
    use avl_base::config::CONTAINER_WORKER_MEMORY_MIB;
    use avl_testkit::tartfake::Answer;

    use pretty_assertions::assert_eq;

    use super::*;
    use crate::worker::docker::Docker;
    use crate::worker::testing::Fixture;
    use crate::worker::worker::{PoolCommand, PoolTarget, StartState};

    fn ctx() -> Ctx {
        Ctx::background()
    }

    fn docker(fixture: &Fixture) -> &Docker {
        fixture.manager.machine().docker().expect("a Docker pool")
    }

    fn position(calls: &[String], prefix: &str) -> usize {
        calls
            .iter()
            .position(|call| call.starts_with(prefix))
            .unwrap_or_else(|| panic!("no {prefix:?} call in {calls:#?}"))
    }

    const START: &str = "system start --enable-kernel-install";

    /// The gate starts a server that is down, once, and logs the start. The next gate finds it running. Nothing runs a
    /// Docker CLI, nothing names a Docker configuration, and nothing stops the server.
    #[tokio::test]
    async fn the_gate_starts_a_server_that_is_down_once() {
        let fixture = Fixture::docker_container();
        assert!(fixture.settings.runs_container_engine());
        for _ in 0..2 {
            docker(&fixture).require_available(&ctx(), "").await.unwrap();
        }
        let calls = fixture.fake.calls();
        assert_eq!(calls.iter().filter(|call| call.as_str() == START).count(), 1, "{calls:#?}");
        assert!(position(&calls, "system status --format json") < position(&calls, START));
        assert!(
            !calls
                .iter()
                .any(|call| call.starts_with("system stop") || call.starts_with("version") || call.contains("--app-root")),
            "{calls:#?}"
        );
        let log = std::fs::read_to_string(fixture.settings.container_system_log_path()).unwrap();
        assert!(log.contains("starting the server"), "{log}");
        let environment = fixture.manager.runner().environment();
        assert!(
            !environment.iter().any(|(name, _)| name == "DOCKER_CONFIG" || name == "DOCKER_HOST"),
            "{environment:?}"
        );
    }

    /// A server of another install with another major version is refused, and the controller does not touch it. One
    /// of the same major version is used.
    #[tokio::test]
    async fn a_foreign_server_of_another_major_version_is_refused() {
        let fixture = Fixture::docker_container();
        let foreign = |version: &str| {
            format!(
                r#"{{"status":"running","paths":{{"installRoot":"/usr/local/"}},"client":{{"version":"1.5.0"}},"server":{{"version":"{version}"}},"host":{{"architecture":"arm64"}}}}"#
            )
        };
        fixture.fake.answer(Answer::ContainerSystem, foreign("2.0.0"));
        let refusal = docker(&fixture).require_available(&ctx(), "").await.unwrap_err();
        assert_eq!(
            (refusal.code.as_ref(), refusal.exit),
            ("container_engine_foreign", Exit::UNAVAILABLE)
        );
        assert!(refusal.message.contains("/usr/local/"), "{}", refusal.message);
        assert!(!fixture.fake.saw_call_containing("system start"), "{:#?}", fixture.fake.calls());

        fixture.fake.answer(Answer::ContainerSystem, foreign("1.4.1"));
        docker(&fixture).require_available(&ctx(), "").await.unwrap();
    }

    /// A start that fails is `engine_start_failed`, and it names the log that holds the output of `system start`.
    #[tokio::test]
    async fn a_failed_server_start_names_its_log() {
        let fixture = Fixture::docker_container();
        fixture.fake.answer(Answer::ContainerStartExit, "1");
        let refusal = docker(&fixture).require_available(&ctx(), "").await.unwrap_err();
        let log = fixture.settings.container_system_log_path();
        assert_eq!((refusal.code.as_ref(), refusal.exit), ("engine_start_failed", Exit::UNAVAILABLE));
        assert!(refusal.message.contains(&log.display().to_string()), "{}", refusal.message);
        assert!(std::fs::read_to_string(&log).unwrap().contains("the start failed"));
    }

    /// The whole start in the Apple dialect: `image pull`, then a build that names the builder sizes and the
    /// nameserver, then a create with the memory, the CPUs and the nameserver of a worker and without `--hostname`, then
    /// the start. A second start keeps the running container.
    #[tokio::test]
    async fn a_start_speaks_the_apple_dialect() {
        let fixture = Fixture::docker_container();
        let worker = fixture.worker(0);
        assert_eq!(
            fixture.manager.start_without_lifecycle_lock(&ctx(), worker).await.unwrap(),
            StartState::Started
        );
        let calls = fixture.fake.calls();
        let cpus = fixture.settings.vm_cpu;
        assert!(position(&calls, "image pull ") < position(&calls, "build "));
        let build = &calls[position(&calls, "build ")];
        assert!(
            build.starts_with(&format!(
                "build --progress plain -c {cpus} -m 4g --dns 192.0.2.53 -t air-ui-worker:"
            )),
            "{build}"
        );
        let create = &calls[position(&calls, "create ")];
        assert!(
            create.starts_with(&format!(
                "create --name {worker} --init -m {CONTAINER_WORKER_MEMORY_MIB}M -c {cpus} --dns 192.0.2.53 --shm-size 2g \
                 --ulimit nofile=65536:65536 --mount type=bind,"
            )),
            "{create}"
        );
        assert!(!create.contains("--hostname"), "{create}");
        assert!(position(&calls, "create ") < position(&calls, &format!("start {worker}")));
        let record = docker(&fixture).read_create_record(worker).unwrap();
        assert_eq!(record.container_id, worker);
        assert!(docker(&fixture).container_is_current(&ctx(), worker).await.unwrap());

        let before = fixture.fake.calls().len();
        assert_eq!(
            fixture.manager.start_without_lifecycle_lock(&ctx(), worker).await.unwrap(),
            StartState::AlreadyRunning
        );
        let after = &fixture.fake.calls()[before..];
        assert!(
            !after.iter().any(|call| call.starts_with("create ") || call.starts_with("start ")),
            "{after:#?}"
        );
    }

    /// A container that stops during the boot poll is `container_exited`. The engine keeps no exit code, so the
    /// refusal quotes the log of the container and its boot log, which holds the code.
    #[tokio::test]
    async fn a_container_that_stops_while_starting_quotes_both_logs() {
        let fixture = Fixture::docker_container();
        fixture.guest.answer(avl_host_testkit::answer_guest(vec![(
            "/usr/bin/true",
            avl_host_testkit::failed(1, "not running"),
        )]));
        fixture.fake.answer(Answer::StartedState, "exited/1\n");
        fixture
            .fake
            .answer(Answer::ContainerLog, "air-display: Xvfb did not start on :88\n");
        fixture.fake.answer(
            Answer::ContainerBootLog,
            "vminitd: id: air-docker-1, status: 1 managed process exit\n",
        );
        let worker = fixture.worker(0);
        let refusal = fixture.manager.start_without_lifecycle_lock(&ctx(), worker).await.unwrap_err();
        assert_eq!(refusal.code, "container_exited", "{refusal:?}");
        assert!(refusal.message.contains("Xvfb did not start"), "{}", refusal.message);
        assert!(refusal.message.contains("status: 1 managed process exit"), "{}", refusal.message);
        let calls = fixture.fake.calls();
        position(&calls, &format!("logs -n 40 {worker}"));
        position(&calls, &format!("logs --boot -n 40 {worker}"));
    }

    /// `pool stop` stops the container with `stop -t 10` and leaves the server and the builder alone.
    #[tokio::test]
    async fn a_pool_stop_stops_the_containers_only() {
        let fixture = Fixture::docker_container();
        let worker = fixture.worker(0);
        fixture.manager.start_without_lifecycle_lock(&ctx(), worker).await.unwrap();
        fixture.fake.forget_calls();
        fixture.manager.pool(&ctx(), PoolCommand::Stop(PoolTarget::All)).await.unwrap();
        let calls = fixture.fake.calls();
        position(&calls, &format!("stop -t 10 {worker}"));
        assert!(
            !calls
                .iter()
                .any(|call| call.starts_with("system stop") || call.starts_with("builder ")),
            "{calls:#?}"
        );
    }

    /// `pool recycle all` stops and deletes the builder, deletes the container by force, finds no volume to delete,
    /// and never stops the server.
    #[tokio::test]
    async fn a_pool_recycle_of_all_deletes_the_builder_and_keeps_the_server() {
        let fixture = Fixture::docker_container();
        let worker = fixture.worker(0);
        fixture.manager.start_without_lifecycle_lock(&ctx(), worker).await.unwrap();
        fixture.fake.forget_calls();
        fixture.manager.pool(&ctx(), PoolCommand::Recycle(PoolTarget::All)).await.unwrap();
        let calls = fixture.fake.calls();
        assert!(position(&calls, "builder stop") < position(&calls, "builder delete"));
        assert!(position(&calls, "builder delete") < position(&calls, &format!("delete --force {worker}")));
        position(&calls, &format!("volume inspect air-{worker}-data"));
        assert!(
            !calls
                .iter()
                .any(|call| call.starts_with("system stop") || call.starts_with("volume delete")),
            "{calls:#?}"
        );
    }
}
