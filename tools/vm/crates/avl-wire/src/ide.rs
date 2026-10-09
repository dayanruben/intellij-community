//! The wire of the lane IDE: the launch document that `ide-prepare` reads, its answer, the launch record that it
//! writes into the context, and the answer of `ide-gc`.
//!
//! The caller is the stable tier of the UI daemon (`AirLaneIde`), and in a standalone lane run the same class on the
//! host. One context is `<ideRoot>/<launchKey>`. It holds the data directories of the IDE, the project, the argument
//! file, the launch record, and the run slot of the run supervisor. One IDE run is the supervisor run
//! [`ide_run_id`] of one launch name, so a relaunch on the same context is a new run in the same slot.
//!
//! The launch document carries the bridge token as a property. So the agent reads it on standard input, writes it
//! only into the argument file (mode 0600), and never puts a property value into a refusal. [`decode_ide_prepare`]
//! keeps that rule for the decode errors too: it names the JSON path, and never the value.

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::error::Category;

#[cfg(test)]
mod tests;

/// The version of the launch document, of its answer and of the launch record. Bump it with the Kotlin client.
pub const SCHEMA_VERSION: u32 = 1;

/// The prefix of the supervisor run id of an IDE launch.
pub const IDE_RUN_PREFIX: &str = "run-ide-";

/// The directories of a context, each one a child of the context directory.
pub const CONFIG_DIR: &str = "config";
pub const SYSTEM_DIR: &str = "system";
pub const PLUGINS_DIR: &str = "plugins";
/// The parent of one log directory per launch name.
pub const LOG_DIR: &str = "log";
/// The default unpack root of the project archive.
pub const PROJECT_DIR: &str = "project";

/// The argument file of the IDE, a child of the context directory. The IDE starts as `java @<this file>`.
pub const ARG_FILE: &str = "ide-jvm.args";
/// The launch record, a child of the context directory.
pub const LAUNCH_RECORD_FILE: &str = "launch.json";
/// The file of the plugin ids that the IDE does not load, a child of the config directory.
pub const DISABLED_PLUGINS_FILE: &str = "disabled_plugins.txt";

/// The system properties that the agent owns, because each one names a path of the context. A launch document or a
/// flags file that states one of them is refused.
pub const OWNED_PROPERTIES: [&str; 6] = [
    "idea.config.path",
    "idea.system.path",
    "idea.plugins.path",
    "idea.log.path",
    "snapshots.path",
    "idea.diagnostic.opentelemetry.file",
];

/// The JVM options that the agent owns, by their prefix, for the same reason as [`OWNED_PROPERTIES`].
pub const OWNED_JVM_OPTIONS: [&str; 3] = ["-XX:ErrorFile=", "-XX:HeapDumpPath=", "-Xlog:gc"];

/// Whether `text` can be a launch key: one path component of ASCII letters, digits, `.`, `_` and `-`, which does
/// not start with `.`.
pub fn is_launch_key(text: &str) -> bool {
    !text.is_empty()
        && !text.starts_with('.')
        && text.len() <= 128
        && text
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// Whether `text` can be a launch name: ASCII letters, digits and `-`, so that [`ide_run_id`] is a supervisor run id.
pub fn is_launch_name(text: &str) -> bool {
    !text.is_empty() && text.len() <= 128 && text.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
}

/// The supervisor run id of the launch `launch_name`, or `None` when the name is not a launch name.
pub fn ide_run_id(launch_name: &str) -> Option<String> {
    is_launch_name(launch_name).then(|| format!("{IDE_RUN_PREFIX}{launch_name}"))
}

/// The launch name of the IDE run `run_id`, or `None` when the run is not the run of an IDE launch.
pub fn launch_name_of(run_id: &str) -> Option<&str> {
    run_id.strip_prefix(IDE_RUN_PREFIX).filter(|name| is_launch_name(name))
}

/// The context directory of `launch_key` under `ide_root`, as a guest path.
pub fn context_dir(ide_root: &str, launch_key: &str) -> String {
    format!("{}/{launch_key}", ide_root.trim_end_matches('/'))
}

// --- the launch document -----------------------------------------------------------------------------------

/// What `ide-prepare` reads on standard input.
///
/// The caller sends it twice for a new context. The first call has no properties and gives the paths, which the
/// caller needs to compute the properties. The second call has the full properties and writes the argument file. A
/// relaunch on the same context sends it once, with `fresh` false and a new launch name.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdePrepare {
    pub schema_version: u32,
    /// The parent of every context, an absolute guest path.
    pub ide_root: String,
    /// The context under `ide_root`. See [`is_launch_key`].
    pub launch_key: String,
    /// Deletes what a preparation writes before it prepares again: the data directories, the project, the argument file
    /// and the launch record. The log directories and the run directories of earlier launches stay, and `ide-gc` trims
    /// them. The caller sets it on the first call for a new context only.
    pub fresh: bool,
    /// The home of the dev distribution, an absolute guest path.
    pub dist_home: String,
    /// The `DevIdeConfig` file of the distribution, which states the main class. An absolute guest path.
    pub ide_config: String,
    /// The JVM that the IDE runs on, an absolute guest path. The launch record keeps it, so a later kill finds
    /// `<javaHome>/bin/jcmd`.
    pub java_home: String,
    /// The JVM flags that Bazel writes, one per line. An absolute guest path.
    pub flags_file: String,
    /// The `-D` properties of the launch. Sorted by key on the wire, so the argument file is the same for the same
    /// document.
    #[serde(default)]
    pub properties: BTreeMap<String, String>,
    /// The environment of the IDE process. The caller passes it to `start`. The agent reads only `DISPLAY` from it.
    #[serde(default)]
    pub environment: BTreeMap<String, String>,
    #[serde(default)]
    pub disabled_plugin_ids: Vec<String>,
    pub project: ProjectSource,
    /// The name of this launch. See [`is_launch_name`].
    pub launch_name: String,
    /// The identity of the product that the IDE runs. `ide-gc --keep-product` keeps a live IDE only when its launch
    /// record names the same digest. Empty means no identity, which no `--keep-product` matches.
    #[serde(default)]
    pub product_digest: String,
}

/// Writes the property and environment keys, and never their values: a value can be the bridge token.
impl fmt::Debug for IdePrepare {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("IdePrepare")
            .field("schema_version", &self.schema_version)
            .field("ide_root", &self.ide_root)
            .field("launch_key", &self.launch_key)
            .field("fresh", &self.fresh)
            .field("dist_home", &self.dist_home)
            .field("ide_config", &self.ide_config)
            .field("java_home", &self.java_home)
            .field("flags_file", &self.flags_file)
            .field("properties", &self.properties.keys().collect::<Vec<_>>())
            .field("environment", &self.environment.keys().collect::<Vec<_>>())
            .field("disabled_plugin_ids", &self.disabled_plugin_ids)
            .field("project", &self.project)
            .field("launch_name", &self.launch_name)
            .field("product_digest", &self.product_digest)
            .finish()
    }
}

/// The project that the IDE opens: a zip archive, and the directory inside it that is the project.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectSource {
    /// The zip archive, an absolute guest path.
    pub archive: String,
    /// The project directory inside the archive, a relative slash path. Empty is the archive root.
    #[serde(default)]
    pub archive_root: String,
    /// The unpack root instead of `<context>/project`, an absolute guest path. A fresh preparation deletes it first.
    #[serde(default, deserialize_with = "crate::json::non_null", skip_serializing_if = "Option::is_none")]
    pub relocate_to: Option<String>,
}

/// What `ide-prepare` answers: the paths of the context, the argument file and its digest, and the pids of the JCEF
/// helpers that it killed.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdePrepared {
    pub context_dir: String,
    pub config_dir: String,
    pub system_dir: String,
    pub plugins_dir: String,
    /// `log/<launchName>` of the context.
    pub log_dir: String,
    /// The directory that the IDE opens: the unpack root joined with `archiveRoot`.
    pub project_dir: String,
    pub arg_file: String,
    /// The sha256 of the argument file bytes, as lowercase hex.
    pub arg_file_sha256: String,
    pub reaped: Vec<i32>,
}

/// The file `launch.json` of a context: which launch the argument file is for.
///
/// It holds no property and no environment value, because a value can be the bridge token.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchRecord {
    pub schema_version: u32,
    pub launch_key: String,
    pub launch_name: String,
    pub product_digest: String,
    pub java_home: String,
    pub arg_file: String,
    pub arg_file_sha256: String,
    pub prepared_at: String,
}

// --- the collection -----------------------------------------------------------------------------------------

/// One IDE run under an IDE root.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdeRun {
    pub launch_key: String,
    pub run_id: String,
}

/// What `ide-gc` answers. Each list is always an array.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdeGcResult {
    /// The live IDE runs that it canceled.
    pub stopped: Vec<IdeRun>,
    /// The live IDE runs that it kept.
    pub kept: Vec<IdeRun>,
    /// The log directories and the finished run directories that it removed, as guest paths.
    pub removed: Vec<String>,
}

// --- decoding -----------------------------------------------------------------------------------------------

/// Why a launch document is refused. The message names a field or a key, and never a value.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IdeDocumentError {
    pub message: String,
}

impl fmt::Display for IdeDocumentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for IdeDocumentError {}

fn refuse(message: impl Into<String>) -> IdeDocumentError {
    IdeDocumentError { message: message.into() }
}

/// Reads a launch document and refuses one that the agent cannot act on.
///
/// A serde message can quote the refused value, so a data error names only its JSON path, and a syntax error only
/// its line and column.
pub fn decode_ide_prepare(raw: &[u8]) -> Result<IdePrepare, IdeDocumentError> {
    let mut deserializer = serde_json::Deserializer::from_slice(raw);
    let document: IdePrepare = serde_path_to_error::deserialize(&mut deserializer).map_err(|error| {
        let path = error.path().to_string();
        let inner = error.into_inner();
        match inner.classify() {
            Category::Data => refuse(format!("the IDE launch document has no valid value at `{path}`")),
            Category::Io | Category::Syntax | Category::Eof => refuse(format!(
                "the IDE launch document is not JSON (line {}, column {})",
                inner.line(),
                inner.column()
            )),
        }
    })?;
    deserializer.end().map_err(|error| {
        refuse(format!(
            "the IDE launch document has trailing data (line {}, column {})",
            error.line(),
            error.column()
        ))
    })?;
    validate(&document)?;
    Ok(document)
}

fn validate(document: &IdePrepare) -> Result<(), IdeDocumentError> {
    if document.schema_version != SCHEMA_VERSION {
        return Err(refuse(format!(
            "the IDE launch document has schema version {}, and this agent reads {SCHEMA_VERSION}",
            document.schema_version
        )));
    }
    if !is_deletable_root(&document.ide_root) {
        return Err(refuse("ideRoot must be an absolute guest path below the root, without `.` or `..`"));
    }
    if !is_launch_key(&document.launch_key) {
        return Err(refuse(format!("launchKey {:?} is not a launch key", document.launch_key)));
    }
    if !is_launch_name(&document.launch_name) {
        return Err(refuse(format!("launchName {:?} is not a launch name", document.launch_name)));
    }
    for (field, value) in [
        ("distHome", &document.dist_home),
        ("ideConfig", &document.ide_config),
        ("javaHome", &document.java_home),
        ("flagsFile", &document.flags_file),
        ("project.archive", &document.project.archive),
    ] {
        if !is_absolute_guest_path(value) {
            return Err(refuse(format!("{field} must be an absolute guest path")));
        }
    }
    if !is_relative_slash_path(&document.project.archive_root) {
        return Err(refuse("project.archiveRoot must be a relative slash path without `.` or `..`"));
    }
    if let Some(relocate_to) = &document.project.relocate_to
        && !is_deletable_root(relocate_to)
    {
        return Err(refuse(
            "project.relocateTo must be an absolute guest path below the root, without `.` or `..`",
        ));
    }
    for (key, value) in &document.properties {
        if key.is_empty() || key.contains(['=', '\n', '\r']) {
            return Err(refuse(format!("the property key {key:?} is not a property key")));
        }
        if value.contains(['\n', '\r']) {
            return Err(refuse(format!(
                "the value of the property {key} holds a line break, which an argument file cannot hold"
            )));
        }
        if OWNED_PROPERTIES.contains(&key.as_str()) {
            return Err(refuse(format!("the property {key} names a data directory, which the agent owns")));
        }
    }
    for id in &document.disabled_plugin_ids {
        if id.trim().is_empty() || id.contains(['\n', '\r']) {
            return Err(refuse(format!("{id:?} is not a plugin id")));
        }
    }
    if document.product_digest.contains(['\n', '\r']) {
        return Err(refuse("productDigest holds a line break"));
    }
    Ok(())
}

/// Reads the launch record of a context.
pub fn decode_launch_record(raw: &[u8]) -> Result<LaunchRecord, IdeDocumentError> {
    let record: LaunchRecord =
        serde_json::from_slice(raw).map_err(|error| refuse(format!("the launch record is not readable ({error})")))?;
    if record.schema_version != SCHEMA_VERSION {
        return Err(refuse(format!(
            "the launch record has schema version {}, and this agent reads {SCHEMA_VERSION}",
            record.schema_version
        )));
    }
    Ok(record)
}

/// Whether `path` is an absolute POSIX path.
fn is_absolute_guest_path(path: &str) -> bool {
    path.starts_with('/') && !path.contains('\0')
}

/// Whether `path` is an absolute POSIX path with at least two components and no `.` or `..` component: a path that
/// the agent can delete without the risk of a parent of it.
fn is_deletable_root(path: &str) -> bool {
    if !is_absolute_guest_path(path) {
        return false;
    }
    let components: Vec<&str> = path.split('/').filter(|component| !component.is_empty()).collect();
    components.len() >= 2 && components.iter().all(|component| !matches!(*component, "." | ".."))
}

/// Whether `path` is empty or a relative slash path without an empty, `.` or `..` component.
fn is_relative_slash_path(path: &str) -> bool {
    path.is_empty()
        || (!path.starts_with('/')
            && !path.contains('\0')
            && path
                .trim_end_matches('/')
                .split('/')
                .all(|component| !matches!(component, "" | "." | "..")))
}
