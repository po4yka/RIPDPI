use std::fs;

use ripdpi_strategy_config::{StrategyConfigError, parse_yaml_str};

#[test]
fn host_file_reference_is_loaded_relative_to_base_dir() {
    let base = std::env::temp_dir().join(format!("ripdpi-strategy-config-{}", std::process::id()));
    fs::create_dir_all(&base).expect("create temp dir");
    fs::write(base.join("hostlist.txt"), "example.com\n# comment\nexample.net\n").expect("write hosts");
    let yaml = "version: 1\nstrategies:\n  - id: file\n    match:\n      hosts: \"@hostlist.txt\"\n    steps:\n      - type: split\n";

    let parsed = parse_yaml_str(yaml, &base).expect("parse yaml");
    assert_eq!(parsed.strategies[0].matcher.hosts, ["example.com", "example.net"]);
    let _ = fs::remove_dir_all(base);
}

#[test]
fn host_file_reference_rejects_parent_dir_escape() {
    let base = std::env::temp_dir().join(format!("ripdpi-strategy-config-escape-{}", std::process::id()));
    fs::create_dir_all(&base).expect("create temp dir");
    // `..` traversal must be rejected before any file is read, even if a file
    // happens to exist at the resolved location.
    let yaml = "version: 1\nstrategies:\n  - id: file\n    match:\n      hosts: \"@../escape.txt\"\n    steps:\n      - type: split\n";

    let error = parse_yaml_str(yaml, &base).expect_err("parent-dir escape must be rejected");
    assert!(matches!(error, StrategyConfigError::HostListPathEscape { .. }), "expected path-escape error: {error:?}");
    let _ = fs::remove_dir_all(base);
}

#[test]
fn host_file_reference_rejects_absolute_path() {
    let base = std::env::temp_dir().join(format!("ripdpi-strategy-config-abs-{}", std::process::id()));
    fs::create_dir_all(&base).expect("create temp dir");
    let abs = if cfg!(windows) { "@C:/etc/passwd" } else { "@/etc/passwd" };
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: file\n    match:\n      hosts: \"{abs}\"\n    steps:\n      - type: split\n"
    );

    let error = parse_yaml_str(&yaml, &base).expect_err("absolute path must be rejected");
    assert!(matches!(error, StrategyConfigError::HostListPathEscape { .. }), "expected path-escape error: {error:?}");
    let _ = fs::remove_dir_all(base);
}
