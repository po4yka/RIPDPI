use std::fs;

use ripdpi_strategy_config::parse_yaml_str;

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
