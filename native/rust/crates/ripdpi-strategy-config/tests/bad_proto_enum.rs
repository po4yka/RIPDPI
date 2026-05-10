use ripdpi_strategy_config::parse_yaml_str;

#[test]
fn bad_protocol_name_returns_parse_error() {
    let yaml =
        "version: 1\nstrategies:\n  - id: bad\n    match:\n      proto: [badproto]\n    steps:\n      - type: split\n";
    let error = parse_yaml_str(yaml, ".").expect_err("bad proto");
    assert!(error.to_string().contains("badproto"));
}
