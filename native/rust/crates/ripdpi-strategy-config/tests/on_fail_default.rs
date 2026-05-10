use ripdpi_strategy_config::{parse_yaml_str, OnFail};

#[test]
fn on_fail_defaults_to_next_strategy() {
    let yaml = "version: 1\nstrategies:\n  - id: default\n    steps:\n      - type: split\n";
    let parsed = parse_yaml_str(yaml, ".").expect("parse yaml");
    assert_eq!(parsed.strategies[0].on_fail, OnFail::NextStrategy);
}
