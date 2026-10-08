use std::fs;
use std::path::Path;

use avl_wire::ide::{self, LaunchRecord};
use pretty_assertions::assert_eq;

use super::*;
use crate::supervisor::LiveSystem;

fn args(root: &Path, stop_all: bool, keep_product: Option<&str>, keep_logs: u32) -> IdeGcArgs {
    IdeGcArgs {
        root: root.to_path_buf(),
        stop_all,
        keep_product: keep_product.map(str::to_owned),
        keep_logs,
        grace_ms: 0,
    }
}

fn write_record(context: &Path, product_digest: &str) {
    let record = LaunchRecord {
        schema_version: ide::SCHEMA_VERSION,
        launch_key: "k".to_owned(),
        launch_name: "launch-1".to_owned(),
        product_digest: product_digest.to_owned(),
        java_home: "/jbr".to_owned(),
        arg_file: "/a".to_owned(),
        arg_file_sha256: "00".repeat(32),
        prepared_at: "2026-10-08T10:00:00.000Z".to_owned(),
    };
    fs::write(context.join(LAUNCH_RECORD_FILE), serde_json::to_vec(&record).unwrap()).unwrap();
}

// A root that does not exist holds nothing to collect.
#[test]
fn an_absent_root_collects_nothing() {
    let directory = tempfile::tempdir().unwrap();
    let result = collect(&LiveSystem, &args(&directory.path().join("ide"), true, None, 5)).unwrap();
    assert_eq!(result, IdeGcResult::default());
}

// The newest log directories stay, and so does the one of a live launch. A removed launch loses its finished run
// directory too. A directory that is no launch is not touched.
#[test]
fn the_newest_logs_and_the_live_launch_stay() {
    let directory = tempfile::tempdir().unwrap();
    let context = directory.path().join("k");
    for index in 1..=6 {
        fs::create_dir_all(context.join(format!("log/launch-{index}"))).unwrap();
        fs::create_dir_all(context.join(format!("run-ide-launch-{index}"))).unwrap();
    }
    fs::create_dir_all(context.join("log/not.a.launch")).unwrap();
    let removed = trim_logs(&context, 2, Some("launch-1"));
    let text = |relative: &str| context.join(relative).to_string_lossy().into_owned();
    let mut expected = Vec::new();
    for index in [4, 3, 2] {
        expected.push(text(&format!("log/launch-{index}")));
        expected.push(text(&format!("run-ide-launch-{index}")));
    }
    assert_eq!(removed, expected);
    for kept in [
        "log/launch-6",
        "log/launch-5",
        "log/launch-1",
        "run-ide-launch-1",
        "log/not.a.launch",
    ] {
        assert!(context.join(kept).is_dir(), "{kept}");
    }
}

// `--keep-product` keeps only an IDE whose launch record names the same product.
#[test]
fn keep_product_keeps_only_the_same_product() {
    let directory = tempfile::tempdir().unwrap();
    let context = directory.path();
    let keep = |digest: &str| args(context, false, Some(digest), 5);
    assert!(should_stop(context, &keep("p1")), "an IDE without a record was kept");
    write_record(context, "p1");
    assert!(!should_stop(context, &keep("p1")));
    assert!(should_stop(context, &keep("p2")));
    assert!(should_stop(context, &args(context, true, None, 5)));
    assert!(!should_stop(context, &args(context, false, None, 5)));
    write_record(context, "");
    assert!(should_stop(context, &keep("p1")), "an IDE of no product was kept");
}

#[test]
fn only_launch_key_directories_are_contexts() {
    let directory = tempfile::tempdir().unwrap();
    for name in ["b", "a", ".hidden"] {
        fs::create_dir_all(directory.path().join(name)).unwrap();
    }
    fs::write(directory.path().join("file"), "").unwrap();
    assert_eq!(context_names(directory.path()).unwrap(), ["a", "b"]);
}
