use ripdpi_tunnel_config::{Config, ConfigError};

const MINIMAL: &str = "schemaVersion: 2\nsocks5: {port: 1080, address: 127.0.0.1}\n";

#[test]
fn preserves_unknown_fields_and_ignores_merge_keys() {
    let yaml = format!("{MINIMAL}unknown: true\ntunnel: {{unknown: true, <<: {{mtu: 1200}}}}\n");
    let config: Config = yaml.parse().expect("legacy permissive tunnel schema");
    assert_eq!(config.tunnel.mtu, 1500);
}

#[test]
fn preserves_scalar_aliases_and_integer_forms() {
    let yaml = "schemaVersion: 0x2\nsocks5: {port: 0o2070, address: &host 127.0.0.1}\nmapdns: {address: *host}\ntunnel: {mtu: 0x5dc}\n";
    let config: Config = yaml.parse().expect("aliases and numeric scalars");
    assert_eq!(config.socks5.port, 1080);
    assert_eq!(config.tunnel.mtu, 1500);
    assert_eq!(config.mapdns.expect("mapdns").address, "127.0.0.1");
}

#[test]
fn preserves_string_scalar_content() {
    let yaml = "schemaVersion: 2\nsocks5: {port: 1080, address: on, username: 00123, password: 0x20}\n";
    let config: Config = yaml.parse().expect("string scalar content");
    assert_eq!(config.socks5.address, "on");
    assert_eq!(config.socks5.username.as_deref(), Some("00123"));
    assert_eq!(config.socks5.password.as_deref(), Some("0x20"));
}

#[test]
fn rejects_duplicate_known_fields() {
    for yaml in [
        format!("{MINIMAL}schemaVersion: 2\n"),
        "schemaVersion: 2\nsocks5: {port: 1080, port: 1081, address: localhost}\n".to_owned(),
        format!("{MINIMAL}tunnel: {{mtu: 1500, mtu: 1200}}\n"),
    ] {
        assert!(matches!(yaml.parse::<Config>(), Err(ConfigError::Yaml(_))), "{yaml}");
    }
}

#[test]
fn rejects_ambiguous_boolean_spellings_and_accepts_yaml_12_booleans() {
    for value in ["on", "off", "yes", "'true'", "1"] {
        let yaml = format!("{MINIMAL}tunnel: {{multi-queue: {value}}}\n");
        assert!(matches!(yaml.parse::<Config>(), Err(ConfigError::Yaml(_))), "{value}");
    }
    for value in ["true", "True", "TRUE"] {
        let yaml = format!("{MINIMAL}tunnel: {{multi-queue: {value}}}\n");
        assert!(yaml.parse::<Config>().expect("YAML boolean").tunnel.multi_queue);
    }
}

#[test]
fn keeps_yaml_errors_separate_from_validation_errors() {
    assert!(matches!(format!("{MINIMAL}---\n{MINIMAL}").parse::<Config>(), Err(ConfigError::Yaml(_))));
    assert!(matches!(MINIMAL.replace("1080", "65536").parse::<Config>(), Err(ConfigError::Yaml(_))));
    assert!(matches!(MINIMAL.replace("1080", "0").parse::<Config>(), Err(ConfigError::InvalidValue { .. })));
    assert!(matches!(
        MINIMAL.replace("Version: 2", "Version: 3").parse::<Config>(),
        Err(ConfigError::UnsupportedSchemaVersion { found: 3 })
    ));
}

#[test]
fn preserves_binary_tag_as_string_content() {
    let yaml =
        "schemaVersion: 2\nsocks5: {port: 1080, address: localhost, username: user, password: !!binary c2VjcmV0}\n";
    let config: Config = yaml.parse().expect("binary-tagged string");
    assert_eq!(config.socks5.password.as_deref(), Some("c2VjcmV0"));
}

#[test]
fn keeps_credentials_out_of_syntax_error_messages() {
    let yaml =
        "schemaVersion: 2\nsocks5: {port: 1080, address: localhost, username: user, password: secret-source-marker\n";
    let error = yaml.parse::<Config>().expect_err("syntax error");
    assert!(!error.to_string().contains("secret-source-marker"));
}
