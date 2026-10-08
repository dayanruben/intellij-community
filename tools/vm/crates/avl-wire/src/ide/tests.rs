use pretty_assertions::assert_eq;
use serde_json::{Value, json};

use super::*;
use crate::supervisor::is_run_id;

const TOKEN: &str = "s3cr3t-bridge-token";

fn document() -> Value {
    json!({
        "schemaVersion": 1,
        "ideRoot": "/data/ide",
        "launchKey": "a1b2c3",
        "fresh": true,
        "distHome": "/runfiles/_main/dist",
        "ideConfig": "/runfiles/_main/dist.config",
        "javaHome": "/data/runtime/jbr",
        "flagsFile": "/runfiles/_main/ide.flags",
        "properties": {"air.ui.test.http.token": TOKEN, "user.home": "/data/ide/a1b2c3/user-home"},
        "environment": {"DISPLAY": ":88"},
        "disabledPluginIds": ["com.intellij.copyright"],
        "project": {"archive": "/runfiles/deps/simpleJavaProject.zip", "archiveRoot": "BookmarksTestProject"},
        "launchName": "launch-1",
        "productDigest": "0123"
    })
}

fn decoded(value: &Value) -> Result<IdePrepare, IdeDocumentError> {
    decode_ide_prepare(value.to_string().as_bytes())
}

fn refusal(value: &Value) -> String {
    match decoded(value) {
        Ok(document) => panic!("the document was accepted: {document:?}"),
        Err(error) => error.message,
    }
}

// The run id of a launch is a supervisor run id, and the launch name comes back out of it.
#[test]
fn an_ide_run_id_is_a_supervisor_run_id_and_names_its_launch() {
    let run_id = ide_run_id("launch-7").unwrap();
    assert_eq!(run_id, "run-ide-launch-7");
    assert!(is_run_id(&run_id));
    assert_eq!(launch_name_of(&run_id), Some("launch-7"));
    assert_eq!(launch_name_of("run-ui-daemon-1"), None);
    assert_eq!(launch_name_of("run-ide-"), None);
    assert_eq!(ide_run_id("a.b"), None, "a dot is no run id character");
    assert_eq!(ide_run_id(""), None);
}

#[test]
fn a_launch_key_is_one_plain_path_component() {
    for key in ["a1b2", "air-ui.ide_3", "X"] {
        assert!(is_launch_key(key), "{key}");
    }
    for key in ["", ".", "..", ".hidden", "a/b", "a b", "ä"] {
        assert!(!is_launch_key(key), "{key}");
    }
    assert_eq!(context_dir("/data/ide/", "k"), "/data/ide/k");
}

#[test]
fn a_full_document_round_trips() {
    let read = decoded(&document()).unwrap();
    assert_eq!(read.properties["air.ui.test.http.token"], TOKEN);
    assert_eq!(read.project.archive_root, "BookmarksTestProject");
    assert_eq!(read.project.relocate_to, None);
    assert_eq!(serde_json::to_value(&read).unwrap(), document());
}

// The first call of a new context sends no properties; the fields with a default may be absent.
#[test]
fn the_defaulted_fields_may_be_absent() {
    let mut value = document();
    let object = value.as_object_mut().unwrap();
    for field in ["properties", "environment", "disabledPluginIds", "productDigest"] {
        object.remove(field);
    }
    let document = decoded(&value).unwrap();
    assert!(document.properties.is_empty() && document.environment.is_empty());
    assert_eq!(document.product_digest, "");
}

// A refusal names the path of the bad field, never its value: the value can be the bridge token.
#[test]
fn a_refusal_never_quotes_a_value() {
    let mut wrong_kind = document();
    wrong_kind["properties"]["air.ui.test.http.token"] = json!(7);
    let mut wrong_root = document();
    wrong_root["ideRoot"] = json!(TOKEN);
    let mut line_break = document();
    line_break["properties"]["air.ui.test.http.token"] = json!(format!("{TOKEN}\n"));
    for value in [wrong_kind, wrong_root, line_break] {
        let message = refusal(&value);
        assert!(!message.contains(TOKEN), "{message}");
    }
    let mut wrong_kind = document();
    wrong_kind["properties"]["air.ui.test.http.token"] = json!(7);
    assert_eq!(
        refusal(&wrong_kind),
        "the IDE launch document has no valid value at `properties.air.ui.test.http.token`"
    );
    let not_json = decode_ide_prepare(format!("{{\"properties\":{{\"t\":\"{TOKEN}\"").as_bytes()).unwrap_err();
    assert!(!not_json.message.contains(TOKEN), "{}", not_json.message);
    assert!(
        not_json.message.starts_with("the IDE launch document is not JSON"),
        "{}",
        not_json.message
    );
}

#[test]
fn a_document_the_agent_cannot_act_on_is_refused() {
    let cases: [(&str, Value, &str); 10] = [
        ("schemaVersion", json!(2), "schema version 2"),
        ("ideRoot", json!("/ide"), "ideRoot"),
        ("ideRoot", json!("/data/../ide"), "ideRoot"),
        ("launchKey", json!(".."), "launchKey"),
        ("launchName", json!("a.b"), "launchName"),
        ("distHome", json!("dist"), "distHome"),
        ("flagsFile", json!("ide.flags"), "flagsFile"),
        ("disabledPluginIds", json!([" "]), "plugin id"),
        ("properties", json!({"idea.config.path": "/elsewhere"}), "idea.config.path"),
        ("properties", json!({"": "x"}), "property key"),
    ];
    for (field, value, expected) in cases {
        let mut document = document();
        document[field] = value;
        let message = refusal(&document);
        assert!(message.contains(expected), "{field}: {message}");
    }
    for (field, value) in [
        ("archiveRoot", json!("../outside")),
        ("archiveRoot", json!("/absolute")),
        ("relocateTo", json!("/live")),
        ("relocateTo", json!(null)),
        ("archive", json!("project.zip")),
    ] {
        let mut document = document();
        document["project"][field] = value;
        decoded(&document).unwrap_err();
    }
    let mut document = document();
    document["project"]["relocateTo"] = json!("/tmp/air-live/project");
    assert_eq!(
        decoded(&document).unwrap().project.relocate_to.as_deref(),
        Some("/tmp/air-live/project")
    );
}

// The debug form names the keys only, so a test failure or a log line never prints the token.
#[test]
fn the_debug_form_holds_no_property_value() {
    let shown = format!("{:?}", decoded(&document()).unwrap());
    assert!(!shown.contains(TOKEN), "{shown}");
    assert!(shown.contains("air.ui.test.http.token"), "{shown}");
}

#[test]
fn a_launch_record_round_trips_and_checks_its_version() {
    let record = LaunchRecord {
        schema_version: SCHEMA_VERSION,
        launch_key: "k".to_owned(),
        launch_name: "launch-1".to_owned(),
        product_digest: "0123".to_owned(),
        java_home: "/jbr".to_owned(),
        arg_file: "/data/ide/k/ide-jvm.args".to_owned(),
        arg_file_sha256: "ab".repeat(32),
        prepared_at: "2026-10-08T10:00:00Z".to_owned(),
    };
    let raw = serde_json::to_vec(&record).unwrap();
    assert_eq!(decode_launch_record(&raw).unwrap(), record);
    let text = String::from_utf8(raw).unwrap();
    assert!(text.contains(r#""argFileSha256""#) && text.contains(r#""productDigest""#), "{text}");
    let older = text.replace(r#""schemaVersion":1"#, r#""schemaVersion":0"#);
    decode_launch_record(older.as_bytes()).unwrap_err();
}

// Every list of the collection answer is an array, also when it is empty.
#[test]
fn an_empty_collection_writes_three_arrays() {
    assert_eq!(
        serde_json::to_string(&IdeGcResult::default()).unwrap(),
        r#"{"stopped":[],"kept":[],"removed":[]}"#
    );
}
