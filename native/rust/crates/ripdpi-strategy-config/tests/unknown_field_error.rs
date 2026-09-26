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
