//! `ide-prepare`: one context of the lane IDE, from the launch document on standard input to the published argument
//! file.
//!
//! The steps, in order: with `fresh`, delete the context. Create `config`, `system`, `plugins`, `log/<launchName>` and
//! `project`, each private. Kill the JCEF helpers that name the context. Write `config/disabled_plugins.txt`. Unpack
//! the project archive when the project is not there yet. Compose the argument file through `dev-launch`, with the
//! data paths of the context last. Publish the argument file and the launch record privately, and answer the paths
//! and the sha256 of the argument file.
//!
//! The document holds the bridge token as a property. So no refusal here quotes a property value, and the token
//! reaches no file but the argument file, which is mode 0600.

use std::collections::BTreeMap;
use std::ffi::OsString;
use std::fs::{self, File};
use std::io::{self, Read};
use std::os::unix::fs::DirBuilderExt;
use std::path::Path;

use avl_wire::ide::{
    self, ARG_FILE, CONFIG_DIR, DISABLED_PLUGINS_FILE, IdePrepare, IdePrepared, LAUNCH_RECORD_FILE, LOG_DIR, LaunchRecord,
    OWNED_JVM_OPTIONS, OWNED_PROPERTIES, PLUGINS_DIR, PROJECT_DIR, ProjectSource, SYSTEM_DIR,
};
use avl_wire::verb::AgentVerb;
use dev_launch::{Distribution, DistributionFiles};
use sha2::{Digest, Sha256};

use super::{live_run, reap};
use crate::clock::stamp;
use crate::reply::{AgentRefusal, AgentRefusalExt, refuse};
use crate::stage::launch_prep::publish_privately;
use crate::stage::{StagingTree, is_directory, is_file};
use crate::supervisor::System;

#[cfg(test)]
mod tests;

const VERB: AgentVerb = AgentVerb::IdePrepare;

/// The refusal of a preparation for a context whose run slot holds a live IDE.
pub(crate) const CODE_IDE_RUNNING: &str = "ide_running";
/// The refusal of a preparation on a Linux guest that names no X display.
pub(crate) const CODE_DISPLAY_MISSING: &str = "ide_display_missing";

/// What the preparation reads from the guest itself: the OS, and the display of the agent's own environment.
pub(crate) struct Guest {
    pub linux: bool,
    pub display: Option<OsString>,
}

impl Guest {
    pub(crate) fn current() -> Self {
        Self {
            linux: cfg!(target_os = "linux"),
            display: std::env::var_os("DISPLAY"),
        }
    }
}

/// Reads the launch document from standard input. Standard input, because an argv is readable in the process table
/// by every account of the guest, and the document holds the bridge token.
pub(crate) fn read_document(stdin: &mut dyn Read) -> Result<IdePrepare, AgentRefusal> {
    let mut raw = Vec::new();
    stdin
        .read_to_end(&mut raw)
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot read the IDE launch document: {error}")))?;
    ide::decode_ide_prepare(&raw).map_err(|error| AgentRefusal::for_verb(VERB, error.message))
}

/// The paths of one context for one launch, as guest path text.
struct ContextPaths {
    context: String,
    config: String,
    system: String,
    plugins: String,
    log: String,
    project: String,
    arg_file: String,
    launch_record: String,
}

impl ContextPaths {
    fn new(document: &IdePrepare) -> Self {
        let context = ide::context_dir(&document.ide_root, &document.launch_key);
        let child = |name: &str| format!("{context}/{name}");
        Self {
            config: child(CONFIG_DIR),
            system: child(SYSTEM_DIR),
            plugins: child(PLUGINS_DIR),
            log: format!("{context}/{LOG_DIR}/{}", document.launch_name),
            project: child(PROJECT_DIR),
            arg_file: child(ARG_FILE),
            launch_record: child(LAUNCH_RECORD_FILE),
            context,
        }
    }
}

/// Prepares the context of `document` and answers its paths.
pub(crate) fn prepare(system: &dyn System, guest: &Guest, document: &IdePrepare) -> Result<IdePrepared, AgentRefusal> {
    require_display(guest, &document.environment)?;
    let paths = ContextPaths::new(document);
    let context = Path::new(&paths.context);
    if let Some(run) = live_run(system, context)? {
        return Err(AgentRefusal::refused(
            CODE_IDE_RUNNING,
            format!(
                "the context {} holds the live IDE run {}; quit or cancel it before a new launch",
                paths.context, run.run_id
            ),
        ));
    }
    if document.fresh {
        remove_existing_tree(context)?;
        if let Some(relocate_to) = &document.project.relocate_to {
            remove_existing_tree(Path::new(relocate_to))?;
        }
    }
    for directory in [&paths.config, &paths.system, &paths.plugins, &paths.log, &paths.project] {
        create_private_dirs(Path::new(directory))?;
    }
    let reaped = reap::reap_jcef_helpers(context);
    write_disabled_plugins(&paths.config, &document.disabled_plugin_ids)?;
    let project_dir = unpack_project(&document.project, &paths.project)?;

    let arguments = compose_arguments(document, &paths)?;
    // The error quotes the argument, and an argument can hold the bridge token, so the refusal drops it.
    let text = dev_launch::argument_file_text(&arguments)
        .map_err(|_quoted_argument| AgentRefusal::for_verb(VERB, "an argument of the IDE launch holds a line break"))?;
    publish_privately(Path::new(&paths.arg_file), text.as_bytes())
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot publish {}: {error}", paths.arg_file)))?;
    let arg_file_sha256 = hex::encode(Sha256::digest(text.as_bytes()));

    let record = LaunchRecord {
        schema_version: ide::SCHEMA_VERSION,
        launch_key: document.launch_key.clone(),
        launch_name: document.launch_name.clone(),
        product_digest: document.product_digest.clone(),
        java_home: document.java_home.clone(),
        arg_file: paths.arg_file.clone(),
        arg_file_sha256: arg_file_sha256.clone(),
        prepared_at: stamp(system.now()),
    };
    let mut encoded = serde_json::to_vec(&record).map_err(AgentRefusal::internal)?;
    encoded.push(b'\n');
    publish_privately(Path::new(&paths.launch_record), &encoded)
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot publish {}: {error}", paths.launch_record)))?;

    Ok(IdePrepared {
        context_dir: paths.context,
        config_dir: paths.config,
        system_dir: paths.system,
        plugins_dir: paths.plugins,
        log_dir: paths.log,
        project_dir,
        arg_file: paths.arg_file,
        arg_file_sha256,
        reaped,
    })
}

/// Refuses a Linux guest where neither the launch document nor the agent's own environment names an X display. The
/// guest has no Xvfb wrapper: an IDE without a display fails only after its start.
fn require_display(guest: &Guest, environment: &BTreeMap<String, String>) -> Result<(), AgentRefusal> {
    let named = environment.get("DISPLAY").is_some_and(|display| !display.is_empty())
        || guest.display.as_ref().is_some_and(|display| !display.is_empty());
    if guest.linux && !named {
        return Err(AgentRefusal::refused(
            CODE_DISPLAY_MISSING,
            "the IDE needs an X display on Linux, and neither the launch document nor the agent names DISPLAY",
        ));
    }
    Ok(())
}

fn remove_existing_tree(path: &Path) -> Result<(), AgentRefusal> {
    match fs::remove_dir_all(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => refuse!(VERB, "cannot delete {}: {error}", path.display()),
    }
}

/// Creates `path` and its missing parents, each one readable only by this account.
fn create_private_dirs(path: &Path) -> Result<(), AgentRefusal> {
    fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(path)
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot create {}: {error}", path.display())))
}

/// Writes the plugin ids that the IDE does not load, one per line, or removes the file of an earlier launch when the
/// list is empty.
fn write_disabled_plugins(config: &str, ids: &[String]) -> Result<(), AgentRefusal> {
    let path = Path::new(config).join(DISABLED_PLUGINS_FILE);
    if ids.is_empty() {
        return match fs::remove_file(&path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => refuse!(VERB, "cannot remove {}: {error}", path.display()),
        };
    }
    let mut text = ids.join("\n");
    text.push('\n');
    fs::write(&path, text).map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot write {}: {error}", path.display())))
}

/// Unpacks the project archive when the project is not there yet, and answers the project directory.
///
/// The project stays across a relaunch on the same context, as it stays across an IDE restart: a scenario that
/// restarts the IDE reads what the earlier IDE wrote. A fresh preparation deleted the unpack root before, so it
/// unpacks again. The archive goes into a staging tree that is renamed onto the unpack root, so a killed unpack
/// leaves no half project that a later call takes for a whole one.
fn unpack_project(project: &ProjectSource, default_root: &str) -> Result<String, AgentRefusal> {
    let root = project.relocate_to.as_deref().unwrap_or(default_root);
    let archive_root = project.archive_root.trim_end_matches('/');
    let project_dir = if archive_root.is_empty() {
        root.to_owned()
    } else {
        format!("{root}/{archive_root}")
    };
    if has_entries(Path::new(&project_dir)) {
        return Ok(project_dir);
    }
    let root_path = Path::new(root);
    let (Some(parent), Some(name)) = (root_path.parent(), root_path.file_name()) else {
        refuse!(VERB, "the unpack root {root} has no parent");
    };
    create_private_dirs(parent)?;
    let staging = StagingTree::new(parent, &name.to_string_lossy());
    create_private_dirs(staging.path())?;
    let archive = Path::new(&project.archive);
    File::open(archive)
        .map_err(|error| error.to_string())
        .and_then(|file| zip::ZipArchive::new(file).map_err(|error| error.to_string()))
        .and_then(|mut zip| zip.extract(staging.path()).map_err(|error| error.to_string()))
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot unpack {}: {error}", archive.display())))?;
    remove_existing_tree(root_path)?;
    staging
        .publish(root_path)
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot move the project into {root}: {error}")))?;
    if !has_entries(Path::new(&project_dir)) {
        refuse!(
            VERB,
            "the project archive {} holds no directory {archive_root:?} with files",
            archive.display()
        );
    }
    Ok(project_dir)
}

/// Whether `path` is a directory with at least one entry. A project always has files.
fn has_entries(path: &Path) -> bool {
    is_directory(path) && fs::read_dir(path).is_ok_and(|mut entries| entries.next().is_some())
}

/// The arguments of `java` for the launch: the flags file, the properties of the document, the distribution, then
/// the data paths of the context, then the class path and the main class.
///
/// The data paths come after the properties of the distribution, so a value in `idea.properties` cannot move a data
/// directory out of the context.
fn compose_arguments(document: &IdePrepare, paths: &ContextPaths) -> Result<Vec<String>, AgentRefusal> {
    let home = document.dist_home.as_str();
    let bin = Path::new(home).join("bin");
    let vm_options = single_vm_options(&bin)?;

    let flags_file = Path::new(&document.flags_file);
    let flags = dev_launch::read_lines(flags_file).map_err(|error| AgentRefusal::for_verb(VERB, format!("{error:#}")))?;
    let mut command_line = Vec::new();
    for flag in flags.iter().map(|line| line.trim()).filter(|line| !line.is_empty()) {
        if let Some(owned) = owned_option(flag) {
            refuse!(
                VERB,
                "the flags file {} states {owned}, which names a data path that the agent owns",
                flags_file.display()
            );
        }
        command_line.push(flag.to_owned());
    }
    command_line.extend(document.properties.iter().map(|(key, value)| format!("-D{key}={value}")));

    let idea_properties = bin.join("idea.properties");
    let vm_options_file = bin.join(&vm_options);
    let product_info = bin.join("product-info.json");
    let core_classpath = Path::new(home).join("core-classpath.txt");
    let files = DistributionFiles {
        ide_config: Path::new(&document.ide_config),
        idea_properties: &idea_properties,
        vm_options: &vm_options_file,
        product_info: &product_info,
        core_classpath: &core_classpath,
    };
    let runtime_module_repository = is_file(&Path::new(home).join("modules").join("module-descriptors.dat"));
    let distribution = Distribution::read(home, &files, &format!("bin/{vm_options}"), runtime_module_repository)
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot read the distribution {home}: {error:#}")))?;
    let mut arguments = dev_launch::java_arguments(command_line, distribution, None)
        .map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot compose the IDE launch: {error:#}")))?;

    // `java_arguments` ends with `-cp <class path> <main class>`, and a JVM option after the main class is a program
    // argument.
    let Some(tail_start) = arguments.len().checked_sub(3).filter(|&start| arguments[start] == "-cp") else {
        return Err(AgentRefusal::internal(
            "the composed IDE launch does not end with the class path and the main class",
        ));
    };
    let tail = arguments.split_off(tail_start);
    arguments.extend(data_options(paths)?);
    arguments.extend(tail);
    Ok(arguments)
}

/// The one `*.vmoptions` file of the `bin` directory of the distribution, by its name.
fn single_vm_options(bin: &Path) -> Result<String, AgentRefusal> {
    let entries = fs::read_dir(bin).map_err(|error| AgentRefusal::for_verb(VERB, format!("cannot read {}: {error}", bin.display())))?;
    let mut names: Vec<String> = entries
        .filter_map(Result::ok)
        .filter_map(|entry| entry.file_name().into_string().ok())
        .filter(|name| name.ends_with(".vmoptions"))
        .collect();
    if names.len() != 1 {
        names.sort();
        refuse!(
            VERB,
            "{} must hold exactly one *.vmoptions file, and holds {names:?}",
            bin.display()
        );
    }
    Ok(names.remove(0))
}

/// The data path that a flag states, or `None`.
fn owned_option(flag: &str) -> Option<&str> {
    if let Some(property) = flag.strip_prefix("-D") {
        let key = property.split_once('=').map_or(property, |(key, _)| key);
        return OWNED_PROPERTIES.iter().copied().find(|owned| *owned == key);
    }
    OWNED_JVM_OPTIONS.iter().copied().find(|prefix| flag.starts_with(prefix))
}

/// The JVM options that put every file the IDE writes into the context: the data directories of `idea.properties`,
/// and the crash, heap dump, GC, trace and snapshot files in the log directory of the launch.
fn data_options(paths: &ContextPaths) -> Result<Vec<String>, AgentRefusal> {
    let log = &paths.log;
    for directory in ["jvm", "heap-dump", "snapshots"] {
        create_private_dirs(&Path::new(log).join(directory))?;
    }
    Ok(vec![
        format!("-Didea.config.path={}", paths.config),
        format!("-Didea.system.path={}", paths.system),
        format!("-Didea.plugins.path={}", paths.plugins),
        format!("-Didea.log.path={log}"),
        format!("-Dsnapshots.path={log}/snapshots"),
        format!("-Didea.diagnostic.opentelemetry.file={log}/opentelemetry.json"),
        format!("-XX:ErrorFile={log}/jvm/java_error_in_idea_%p.log"),
        format!("-XX:HeapDumpPath={log}/heap-dump/heap-dump.hprof"),
        format!("-Xlog:gc*:file={log}/gcLog.log"),
    ])
}
