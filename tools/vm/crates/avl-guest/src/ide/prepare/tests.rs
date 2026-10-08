use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use avl_wire::ide::{self, IdePrepare, IdePrepared, ProjectSource};
use avl_wire::supervisor::{AgentExit, Outcome, Phase};
use pretty_assertions::assert_eq;
use sha2::{Digest, Sha256};
use zip::write::SimpleFileOptions;

use super::*;
use crate::cli::{CancelArgs, IdeGcArgs, RootArgs, RunArgs, StartArgs};
use crate::supervisor::{self, LaunchHost, Launcher, LiveSystem};
use crate::testing::{agent_launcher, run_agent};

const TOKEN: &str = "s3cr3t-bridge-token";
const TOKEN_PROPERTY: &str = "air.ui.test.http.token";

/// A dev distribution, a flags file and a project archive on disk, and the IDE root the contexts go under.
struct Fixture {
    directory: tempfile::TempDir,
}

impl Fixture {
    fn new() -> Self {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        let bin = root.join("dist/bin");
        fs::create_dir_all(&bin).unwrap();
        // A data directory in `idea.properties` must lose to the one of the context.
        fs::write(
            bin.join("idea.properties"),
            "idea.config.path=/from/idea.properties\nfrom.idea.properties=1\n",
        )
        .unwrap();
        fs::write(bin.join("idea64.vmoptions"), "-Xmx2g\n-Dfrom.vmoptions=1\n").unwrap();
        fs::write(
            bin.join("product-info.json"),
            r#"{"launch":[{"additionalJvmArguments":["-Dfrom.product.info=1"]}]}"#,
        )
        .unwrap();
        fs::write(root.join("dist/core-classpath.txt"), "lib/a.jar\nlib/b.jar\n").unwrap();
        fs::write(root.join("dist.config"), "home.path=dist\nmain.class.name=com.intellij.idea.Main\n").unwrap();
        fs::write(root.join("ide.flags"), "-ea\n\n--add-opens=java.base/java.lang=ALL-UNNAMED\n").unwrap();
        write_project_archive(&root.join("project.zip"));
        Self { directory }
    }

    fn path(&self, relative: &str) -> PathBuf {
        self.directory.path().join(relative)
    }

    fn text(&self, relative: &str) -> String {
        self.path(relative).to_string_lossy().into_owned()
    }

    fn document(&self, launch_name: &str, fresh: bool) -> IdePrepare {
        IdePrepare {
            schema_version: ide::SCHEMA_VERSION,
            ide_root: self.text("ide"),
            launch_key: "key-1".to_owned(),
            fresh,
            dist_home: self.text("dist"),
            ide_config: self.text("dist.config"),
            java_home: self.text("jbr"),
            flags_file: self.text("ide.flags"),
            properties: BTreeMap::from([
                (TOKEN_PROPERTY.to_owned(), TOKEN.to_owned()),
                ("user.home".to_owned(), self.text("ide/key-1/user home")),
            ]),
            environment: BTreeMap::new(),
            disabled_plugin_ids: vec!["com.intellij.copyright".to_owned(), "org.jetbrains.junie".to_owned()],
            project: ProjectSource {
                archive: self.text("project.zip"),
                archive_root: "BookmarksTestProject".to_owned(),
                relocate_to: None,
            },
            launch_name: launch_name.to_owned(),
            product_digest: "product-1".to_owned(),
        }
    }

    fn context(&self) -> PathBuf {
        self.path("ide/key-1")
    }
}

fn write_project_archive(path: &Path) {
    let mut writer = zip::ZipWriter::new(File::create(path).unwrap());
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for (name, content) in [
        ("BookmarksTestProject/src/Main.java", "class Main {}\n"),
        ("BookmarksTestProject/.idea/misc.xml", "<project/>\n"),
    ] {
        writer.start_file(name, options).unwrap();
        writer.write_all(content.as_bytes()).unwrap();
    }
    writer.finish().unwrap();
}

const NO_DISPLAY_CHECK: Guest = Guest {
    linux: false,
    display: None,
};

fn prepared(document: &IdePrepare) -> IdePrepared {
    prepare(&LiveSystem, &NO_DISPLAY_CHECK, document).unwrap_or_else(|refusal| panic!("the preparation was refused: {refusal}"))
}

/// The arguments of an argument file that `dev_launch::argument_file_text` wrote.
fn read_arguments(path: &str) -> Vec<String> {
    fs::read_to_string(path)
        .unwrap()
        .lines()
        .map(|line| match line.strip_prefix('"').and_then(|quoted| quoted.strip_suffix('"')) {
            Some(quoted) => quoted.replace("\\\"", "\"").replace("\\\\", "\\"),
            None => line.to_owned(),
        })
        .collect()
}

fn mode(path: &str) -> u32 {
    fs::metadata(path).unwrap().permissions().mode() & 0o777
}

fn position(arguments: &[String], argument: &str) -> usize {
    arguments
        .iter()
        .position(|candidate| candidate == argument)
        .unwrap_or_else(|| panic!("{argument} is not in {arguments:#?}"))
}

// A fresh preparation lays out the context, unpacks the project and composes the argument file: the flags, the
// properties, the distribution, then the data paths of the context, then the class path and the main class.
#[test]
fn a_fresh_preparation_lays_out_the_context_and_composes_the_argument_file() {
    let fixture = Fixture::new();
    let answer = prepared(&fixture.document("launch-1", true));
    let context = fixture.text("ide/key-1");
    assert_eq!(
        answer,
        IdePrepared {
            context_dir: context.clone(),
            config_dir: format!("{context}/config"),
            system_dir: format!("{context}/system"),
            plugins_dir: format!("{context}/plugins"),
            log_dir: format!("{context}/log/launch-1"),
            project_dir: format!("{context}/project/BookmarksTestProject"),
            arg_file: format!("{context}/ide-jvm.args"),
            arg_file_sha256: answer.arg_file_sha256.clone(),
            reaped: Vec::new(),
        }
    );
    for directory in [&answer.config_dir, &answer.system_dir, &answer.plugins_dir, &answer.log_dir] {
        assert_eq!(mode(directory), 0o700, "{directory}");
    }
    assert_eq!(mode(&answer.arg_file), 0o600);
    assert_eq!(
        fs::read_to_string(format!("{}/src/Main.java", answer.project_dir)).unwrap(),
        "class Main {}\n"
    );
    assert_eq!(
        fs::read_to_string(format!("{}/disabled_plugins.txt", answer.config_dir)).unwrap(),
        "com.intellij.copyright\norg.jetbrains.junie\n"
    );
    let bytes = fs::read(&answer.arg_file).unwrap();
    assert_eq!(answer.arg_file_sha256, hex::encode(Sha256::digest(&bytes)));

    let arguments = read_arguments(&answer.arg_file);
    let log = &answer.log_dir;
    let dist = fixture.text("dist");
    let tail = [
        format!("-Didea.config.path={}", answer.config_dir),
        format!("-Didea.system.path={}", answer.system_dir),
        format!("-Didea.plugins.path={}", answer.plugins_dir),
        format!("-Didea.log.path={log}"),
        format!("-Dsnapshots.path={log}/snapshots"),
        format!("-Didea.diagnostic.opentelemetry.file={log}/opentelemetry.json"),
        format!("-XX:ErrorFile={log}/jvm/java_error_in_idea_%p.log"),
        format!("-XX:HeapDumpPath={log}/heap-dump/heap-dump.hprof"),
        format!("-Xlog:gc*:file={log}/gcLog.log"),
        "-cp".to_owned(),
        format!("{dist}/lib/a.jar:{dist}/lib/b.jar"),
        "com.intellij.idea.Main".to_owned(),
    ];
    assert_eq!(arguments[arguments.len() - tail.len()..], tail);
    assert_eq!(arguments[..2], ["-ea", "--add-opens=java.base/java.lang=ALL-UNNAMED"]);
    let token = position(&arguments, &format!("-D{TOKEN_PROPERTY}={TOKEN}"));
    let home = position(&arguments, &format!("-Didea.home.path={dist}"));
    let from_properties = position(&arguments, "-Didea.config.path=/from/idea.properties");
    let own = position(&arguments, &tail[0]);
    assert!(token < home && home < from_properties && from_properties < own, "{arguments:#?}");
    for property in ["-Dfrom.vmoptions=1", "-Dfrom.product.info=1", "-Dfrom.idea.properties=1"] {
        position(&arguments, property);
    }

    // The launch record says which launch the argument file is for, and holds no property value.
    let record_text = fs::read_to_string(format!("{context}/launch.json")).unwrap();
    assert!(!record_text.contains(TOKEN), "{record_text}");
    assert_eq!(mode(&format!("{context}/launch.json")), 0o600);
    let record = ide::decode_launch_record(record_text.as_bytes()).unwrap();
    assert_eq!(
        (
            record.launch_name.as_str(),
            record.product_digest.as_str(),
            record.arg_file_sha256.as_str()
        ),
        ("launch-1", "product-1", answer.arg_file_sha256.as_str())
    );
    assert_eq!(record.java_home, fixture.text("jbr"));
}

// The first call of a new context sends no properties; the second sends them all. A relaunch keeps the project,
// as an IDE restart keeps it, and a fresh preparation unpacks it again.
#[test]
fn a_relaunch_keeps_the_project_and_a_fresh_preparation_unpacks_it_again() {
    let fixture = Fixture::new();
    let mut first = fixture.document("launch-1", true);
    first.properties.clear();
    let paths = prepared(&first);
    assert!(!fs::read_to_string(&paths.arg_file).unwrap().contains(TOKEN));
    let second = prepared(&fixture.document("launch-1", false));
    assert_eq!(second.project_dir, paths.project_dir);
    assert!(fs::read_to_string(&second.arg_file).unwrap().contains(TOKEN));
    assert_ne!(second.arg_file_sha256, paths.arg_file_sha256);

    let edit = format!("{}/edited.txt", paths.project_dir);
    fs::write(&edit, "kept").unwrap();
    let mut relaunch = fixture.document("launch-2", false);
    relaunch.disabled_plugin_ids.clear();
    let relaunched = prepared(&relaunch);
    assert_eq!(fs::read_to_string(&edit).unwrap(), "kept");
    assert!(Path::new(&relaunched.log_dir).ends_with("log/launch-2"));
    assert!(
        Path::new(&paths.log_dir).is_dir(),
        "the log of the earlier launch is the collector's"
    );
    assert!(!Path::new(&format!("{}/disabled_plugins.txt", relaunched.config_dir)).exists());

    prepared(&fixture.document("launch-3", true));
    assert!(!Path::new(&edit).exists(), "a fresh preparation kept an edit of the project");
    assert!(!Path::new(&paths.log_dir).exists(), "a fresh preparation kept an earlier log");
}

// A relocated project unpacks into its own root, and a fresh preparation replaces that root.
#[test]
fn a_relocated_project_unpacks_into_its_own_root() {
    let fixture = Fixture::new();
    let mut document = fixture.document("launch-1", true);
    document.project.relocate_to = Some(fixture.text("live/project"));
    fs::create_dir_all(fixture.path("live/project/stale")).unwrap();
    let answer = prepared(&document);
    assert_eq!(answer.project_dir, fixture.text("live/project/BookmarksTestProject"));
    assert!(Path::new(&answer.project_dir).join("src/Main.java").is_file());
    assert!(!fixture.path("live/project/stale").exists());
    assert!(!fixture.path("ide/key-1/project/BookmarksTestProject").exists());
}

// The flags file and the launch document cannot move a data path, and no refusal quotes a property value.
#[test]
fn a_flag_that_states_a_data_path_is_refused_without_the_token() {
    let fixture = Fixture::new();
    fs::write(fixture.path("ide.flags"), "-ea\n-Didea.system.path=/elsewhere\n").unwrap();
    let refusal = prepare(&LiveSystem, &NO_DISPLAY_CHECK, &fixture.document("launch-1", true)).unwrap_err();
    assert_eq!(
        (refusal.code.as_ref(), refusal.exit),
        ("guest_ide_prepare_failed", AgentExit::Refused)
    );
    assert!(refusal.message.contains("idea.system.path"), "{}", refusal.message);
    assert!(!refusal.message.contains(TOKEN), "{}", refusal.message);
    for flag in ["-Didea.log.path", "-XX:ErrorFile=/x", "-XX:HeapDumpPath=/x", "-Xlog:gc:file=/x"] {
        assert!(owned_option(flag).is_some(), "{flag}");
    }
    for flag in ["-Didea.logger=x", "-Xmx2g", "-ea"] {
        assert_eq!(owned_option(flag), None, "{flag}");
    }
}

// The verb reads the document on standard input, and its refusal envelope never echoes it.
#[test]
fn the_verb_never_echoes_the_document() {
    let fixture = Fixture::new();
    let mut document = serde_json::to_value(fixture.document("launch-1", true)).unwrap();
    document["properties"][TOKEN_PROPERTY] = serde_json::json!(42);
    document["fresh"] = serde_json::json!(TOKEN);
    let answered = run_agent(&["ide-prepare"], document.to_string().as_bytes());
    assert_eq!(answered.exit, 70, "{}", answered.stderr);
    assert_eq!(answered.code(), "guest_ide_prepare_failed");
    assert_eq!(answered.stdout, "");
    assert!(!answered.stderr.contains(TOKEN), "{}", answered.stderr);

    let valid = serde_json::to_vec(&fixture.document("launch-1", true)).unwrap();
    let answered = run_agent(&["ide-prepare"], &valid);
    if cfg!(target_os = "linux") && std::env::var_os("DISPLAY").is_none() {
        assert_eq!(answered.code(), CODE_DISPLAY_MISSING);
        return;
    }
    assert_eq!(answered.exit, 0, "{}", answered.stderr);
    let answer: IdePrepared = serde_json::from_str(&answered.stdout).unwrap();
    assert!(!answered.stdout.contains(TOKEN), "{}", answered.stdout);
    assert_eq!(answer.context_dir, fixture.text("ide/key-1"));
}

#[test]
fn a_linux_guest_needs_a_display() {
    let linux = |display: Option<&str>| Guest {
        linux: true,
        display: display.map(Into::into),
    };
    let with = |display: &str| BTreeMap::from([("DISPLAY".to_owned(), display.to_owned())]);
    let refusal = require_display(&linux(None), &BTreeMap::new()).unwrap_err();
    assert_eq!((refusal.code.as_ref(), refusal.exit), (CODE_DISPLAY_MISSING, AgentExit::Refused));
    require_display(&linux(Some("")), &with("")).unwrap_err();
    require_display(&linux(Some(":88")), &BTreeMap::new()).unwrap();
    require_display(&linux(None), &with(":88")).unwrap();
    require_display(&NO_DISPLAY_CHECK, &BTreeMap::new()).unwrap();
}

#[test]
fn a_distribution_needs_one_vm_options_file() {
    let fixture = Fixture::new();
    fs::write(fixture.path("dist/bin/jetbrains_client64.vmoptions"), "").unwrap();
    let refusal = prepare(&LiveSystem, &NO_DISPLAY_CHECK, &fixture.document("launch-1", true)).unwrap_err();
    assert!(refusal.message.contains("exactly one *.vmoptions"), "{}", refusal.message);
}

#[test]
fn an_archive_without_the_project_directory_is_refused() {
    let fixture = Fixture::new();
    let mut document = fixture.document("launch-1", true);
    document.project.archive_root = "OtherProject".to_owned();
    let refusal = prepare(&LiveSystem, &NO_DISPLAY_CHECK, &document).unwrap_err();
    assert!(refusal.message.contains("\"OtherProject\""), "{}", refusal.message);
}

fn wait_for_file(path: &Path) -> String {
    let until = Instant::now() + Duration::from_secs(10);
    while Instant::now() < until {
        if let Ok(text) = fs::read_to_string(path)
            && text.ends_with('\n')
        {
            return text;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    panic!("{} was not written", path.display());
}

/// The launch the caller makes after a preparation: `start` on the context with `java @<argument file>`. A fake
/// `java` records its argv and stays; the context then refuses a preparation, `ide-gc` keeps the IDE of the same
/// product and stops it otherwise, and `cancel --thread-dump` takes the dump with a fake `jcmd` before the TERM.
#[test]
fn the_prepared_context_runs_the_ide_as_a_supervisor_run() {
    if !Path::new("/bin/ps").is_file() || !Path::new("/bin/sleep").is_file() {
        eprintln!("skipped: /bin/ps or /bin/sleep is not on this host");
        return;
    }
    let fixture = Fixture::new();
    let tools = fixture.path("tools");
    fs::create_dir_all(&tools).unwrap();
    let java = avl_testkit::fake_executable(
        &tools,
        "java",
        "printf '%s\\n' \"$@\" > \"$(dirname \"$0\")/argv.tmp\"\nmv \"$(dirname \"$0\")/argv.tmp\" \"$(dirname \"$0\")/argv.txt\"\nexec /bin/sleep 60\n",
    )
    .unwrap();
    let jcmd = avl_testkit::fake_executable(&tools, "jcmd", "echo \"jcmd $*\"\n").unwrap();
    let launcher = Launcher {
        self_exe: agent_launcher(&tools),
        host: LaunchHost::Macos,
    };
    let context = fixture.context();
    let launch = |launch_name: &str, fresh: bool| {
        let answer = prepared(&fixture.document(launch_name, fresh));
        let run = RunArgs {
            root: RootArgs { root: context.clone() },
            run_id: ide::ide_run_id(launch_name).unwrap(),
        };
        let started = supervisor::start(
            &LiveSystem,
            &launcher,
            &StartArgs {
                run: run.clone(),
                cwd: fixture.path("dist"),
                snapshot_id: None,
                argv: vec![java.to_string_lossy().into_owned(), format!("@{}", answer.arg_file)],
            },
        )
        .unwrap_or_else(|refusal| panic!("start was refused: {refusal}"));
        assert_eq!(started.phase, Phase::Running, "{started:?}");
        (answer, run, started)
    };
    let gc = |stop_all: bool, keep_product: Option<&str>| {
        super::super::gc::collect(
            &LiveSystem,
            &IdeGcArgs {
                root: fixture.path("ide"),
                stop_all,
                keep_product: keep_product.map(str::to_owned),
                keep_logs: 5,
                grace_ms: 5_000,
            },
        )
        .unwrap()
    };

    let (answer, run, started) = launch("launch-1", true);
    assert_eq!(wait_for_file(&tools.join("argv.txt")), format!("@{}\n", answer.arg_file));

    let refusal = prepare(&LiveSystem, &NO_DISPLAY_CHECK, &fixture.document("launch-2", false)).unwrap_err();
    assert_eq!((refusal.code.as_ref(), refusal.exit), (CODE_IDE_RUNNING, AgentExit::Refused));
    assert!(refusal.message.contains("run-ide-launch-1"), "{}", refusal.message);

    let kept = gc(false, Some("product-1"));
    assert_eq!((kept.kept.len(), kept.stopped.len()), (1, 0), "{kept:?}");
    assert_eq!(kept.kept[0].run_id, "run-ide-launch-1");

    let finished = supervisor::cancel(
        &LiveSystem,
        &CancelArgs {
            run,
            grace_ms: 5_000,
            thread_dump: Some(jcmd),
        },
    )
    .unwrap();
    assert_eq!((finished.phase, finished.outcome), (Phase::Finished, Some(Outcome::Canceled)));
    let dumps: Vec<PathBuf> = fs::read_dir(&answer.log_dir)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.file_name().unwrap().to_string_lossy().starts_with("threadDump-before-kill-"))
        .collect();
    assert_eq!(dumps.len(), 1, "{dumps:?}");
    assert_eq!(
        fs::read_to_string(&dumps[0]).unwrap(),
        format!("jcmd {} Thread.print\n", started.pid.unwrap())
    );
    let supervisor_log = fs::read_to_string(context.join("run-ide-launch-1/supervisor.log")).unwrap();
    assert!(supervisor_log.contains("thread dump of pid"), "{supervisor_log}");

    fs::remove_file(tools.join("argv.txt")).unwrap();
    launch("launch-2", false);
    wait_for_file(&tools.join("argv.txt"));
    let stopped = gc(false, Some("product-2"));
    assert_eq!((stopped.kept.len(), stopped.stopped.len()), (0, 1), "{stopped:?}");
    assert_eq!(stopped.stopped[0].run_id, "run-ide-launch-2");
    assert_eq!(live_run(&LiveSystem, &context).unwrap(), None);
}
