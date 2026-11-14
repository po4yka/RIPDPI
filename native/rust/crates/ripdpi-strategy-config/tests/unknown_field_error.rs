use ripdpi_strategy_config::parse_yaml_str;

#[test]
fn unknown_top_level_field_returns_parse_error() {
    let error = parse_yaml_str("version: 1\nstrategies: []\nextra: true\n", ".").expect_err("unknown field");
    assert!(error.to_string().contains("unknown field"));
}

#[test]
fn unsupported_strategy_version_is_rejected() {
    assert!(parse_yaml_str("version: 2\nstrategies: []\n", ".").is_err());
    assert!(ripdpi_strategy_config::parse_toml_str("version = 2\nstrategies = []\n", ".").is_err());
}

#[test]
fn unsupported_ipv6_routing_type_is_rejected_in_yaml_and_toml() {
    let yaml = "version: 1\nstrategies:\n  - id: ipv6\n    steps:\n      - type: ipv6_ext\n        ext_type: routing\n";
    let toml =
        "version = 1\n[[strategies]]\nid = 'ipv6'\n[[strategies.steps]]\ntype = 'ipv6_ext'\next_type = 'routing'\n";
    assert!(parse_yaml_str(yaml, ".").is_err());
    assert!(ripdpi_strategy_config::parse_toml_str(toml, ".").is_err());
}
