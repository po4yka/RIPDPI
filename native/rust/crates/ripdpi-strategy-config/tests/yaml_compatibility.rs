use ripdpi_strategy_config::{OnFail, ProtocolName, StrategyConfigError, parse_yaml_str};

fn strategy(body: &str) -> String {
    format!("version: 1\nstrategies:\n  - id: compatibility\n{body}")
}

#[test]
fn preserves_anchors_aliases_and_numeric_scalars() {
    let yaml = strategy(
        "    match:\n      hosts: &hosts [example.com, on, 00123]\n      port: [0x1bb, 0o20, 65535]\n    steps: &steps [{type: split, ttl: 0xff, delta: -4}]\n    on_fail: drop\n  - id: alias\n    match: {hosts: *hosts}\n    steps: *steps\n",
    );
    let parsed = parse_yaml_str(&yaml, ".").expect("anchors and YAML numeric forms");
    assert_eq!(parsed.strategies[0].matcher.port, [443, 16, 65535]);
    assert_eq!(parsed.strategies[0].matcher.hosts, ["example.com", "on", "00123"]);
    assert_eq!(parsed.strategies[1].matcher.hosts, parsed.strategies[0].matcher.hosts);
    assert_eq!(parsed.strategies[1].steps, parsed.strategies[0].steps);
    assert_eq!(parsed.strategies[0].steps[0].ttl, Some(255));
    assert_eq!(parsed.strategies[0].steps[0].delta, Some(-4));
}

#[test]
fn preserves_tagged_unit_enums() {
    let yaml = strategy("    match:\n      proto:\n        - !tls\n    steps: [{type: split}]\n    on_fail: !drop\n");
    let parsed = parse_yaml_str(&yaml, ".").expect("tagged enum variants");
    assert_eq!(parsed.strategies[0].matcher.proto, [ProtocolName::Tls]);
    assert_eq!(parsed.strategies[0].on_fail, OnFail::Drop);
}

#[test]
fn rejects_ambiguous_and_quoted_boolean_scalars() {
    for value in ["yes", "no", "on", "off", "'true'", "1"] {
        let yaml = strategy(&format!("    steps: [{{type: split, disorder: {value}}}]\n"));
        assert!(matches!(parse_yaml_str(&yaml, "."), Err(StrategyConfigError::Parse(_))), "{value}");
    }
}

#[test]
fn preserves_yaml_12_boolean_spellings() {
    for value in ["true", "True", "TRUE"] {
        let yaml = strategy(&format!("    steps: [{{type: split, disorder: {value}}}]\n"));
        assert!(parse_yaml_str(&yaml, ".").expect("YAML boolean").strategies[0].steps[0].disorder);
    }
}

#[test]
fn rejects_duplicate_fields_at_each_schema_level() {
    for yaml in [
        "version: 1\nversion: 1\nstrategies: []\n".to_owned(),
        strategy("    id: duplicate\n    steps: []\n"),
        strategy("    match: {port: [443], port: [80]}\n    steps: []\n"),
        strategy("    steps: [{type: split, ttl: 1, ttl: 2}]\n"),
    ] {
        assert!(matches!(parse_yaml_str(&yaml, "."), Err(StrategyConfigError::Parse(_))), "{yaml}");
    }
}

#[test]
fn rejects_nested_unknown_fields_and_merge_keys() {
    for body in [
        "    unknown: true\n    steps: []\n",
        "    match: {unknown: true}\n    steps: []\n",
        "    steps: [{type: split, unknown: true}]\n",
        "    steps: [{type: split, <<: {ttl: 5}}]\n",
    ] {
        assert!(matches!(parse_yaml_str(&strategy(body), "."), Err(StrategyConfigError::Parse(_))), "{body}");
    }
}

#[test]
fn rejects_multiple_documents_and_invalid_integer_width() {
    for yaml in [
        "version: 1\nstrategies: []\n---\nversion: 1\nstrategies: []\n".to_owned(),
        strategy("    match: {port: [65536]}\n    steps: []\n"),
        strategy("    steps: [{type: split, ttl: 256}]\n"),
        strategy("    steps: [{type: split, delta: -2147483649}]\n"),
    ] {
        assert!(matches!(parse_yaml_str(&yaml, "."), Err(StrategyConfigError::Parse(_))), "{yaml}");
    }
}

#[test]
fn preserves_untagged_host_scalar_and_null() {
    for (hosts, expected) in [("on", vec!["on"]), ("null", vec![])] {
        let yaml = strategy(&format!("    match: {{hosts: {hosts}}}\n    steps: []\n"));
        assert_eq!(parse_yaml_str(&yaml, ".").expect("host form").strategies[0].matcher.hosts, expected);
    }
}

#[test]
fn keeps_yaml_source_out_of_syntax_error_messages() {
    let yaml = strategy("    steps: [{type: split}]\n    # private-source-marker\n    [broken syntax\n");
    let error = parse_yaml_str(&yaml, ".").expect_err("syntax error");
    assert!(!error.to_string().contains("private-source-marker"));
}
