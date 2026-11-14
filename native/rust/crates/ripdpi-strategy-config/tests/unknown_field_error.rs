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

#[test]
fn invalid_ipv6_extension_types_are_rejected_in_yaml_and_toml() {
    for value in ["", "definitely_invalid", "HopByHop"] {
        let yaml = format!(
            "version: 1\nstrategies:\n  - id: ipv6\n    steps:\n      - type: ipv6_ext\n        ext_type: '{value}'\n"
        );
        let toml = format!(
            "version = 1\n[[strategies]]\nid = 'ipv6'\n[[strategies.steps]]\ntype = 'ipv6_ext'\next_type = '{value}'\n"
        );
        assert!(parse_yaml_str(&yaml, ".").is_err(), "YAML value {value:?}");
        assert!(ripdpi_strategy_config::parse_toml_str(&toml, ".").is_err(), "TOML value {value:?}");
    }
}

#[test]
fn ipv6_extension_aliases_and_missing_default_remain_supported() {
    for value in
        [None, Some("hopbyhop"), Some("hop_by_hop"), Some("destopts"), Some("dest_opts"), Some("destination_options")]
    {
        let field = value.map_or_else(String::new, |v| format!("        ext_type: {v}\n"));
        let yaml = format!("version: 1\nstrategies:\n  - id: ipv6\n    steps:\n      - type: ipv6_ext\n{field}");
        let config = parse_yaml_str(&yaml, ".").expect("valid IPv6 extension type");
        assert_eq!(config.strategies[0].steps[0].ext_type.as_deref(), value);
    }
}
