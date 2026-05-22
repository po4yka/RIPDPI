use std::str::FromStr;

use proptest::prelude::*;

use crate::{Config, ConfigError};

const MINIMAL_VALID: &str = r#"
socks5:
  port: 1080
  address: 127.0.0.1
"#;

const NO_PORT: &str = r#"
socks5:
  address: 127.0.0.1
"#;

const NO_ADDRESS: &str = r#"
socks5:
  port: 1080
"#;

const USER_NO_PASS: &str = r#"
socks5:
  port: 1080
  address: 127.0.0.1
  username: user
"#;

const PASS_NO_USER: &str = r#"
socks5:
  port: 1080
  address: 127.0.0.1
  password: secret
"#;

const FULL_YAML: &str = r#"
tunnel:
  name: tun0
  mtu: 1500
  multi-queue: false
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'

socks5:
  port: 1080
  address: 127.0.0.1
  udp: udp
  username: user
  password: pass

mapdns:
  address: 198.18.0.2
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000

misc:
  task-stack-size: 86016
  tcp-buffer-size: 65536
  udp-recv-buffer-size: 524288
  udp-copy-buffer-nums: 10
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-level: warn
  limit-nofile: 65535
"#;

#[test]
fn test_parse_main_yml() {
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    let conf_path = format!("{manifest_dir}/tests/fixtures/main.yml");
    let cfg = Config::from_file(&conf_path).expect("should parse conf/main.yml");

    assert_eq!(cfg.socks5.port, 1080);
    assert_eq!(cfg.socks5.address, "127.0.0.1");
    assert_eq!(cfg.tunnel.mtu, 1500);
    assert_eq!(cfg.tunnel.name, "tun0");
    assert!(!cfg.tunnel.multi_queue);
    assert_eq!(cfg.tunnel.ipv4.as_deref(), Some("198.18.0.1"));
    assert_eq!(cfg.tunnel.ipv6.as_deref(), Some("fc00::1"));
}

#[test]
fn test_missing_socks5_port_is_err() {
    let result = Config::from_str(NO_PORT);

    assert!(result.is_err(), "expected Err for missing socks5.port");
    let msg = result.unwrap_err().to_string();
    assert!(msg.contains("port") || msg.contains("socks5"), "error should mention port or socks5, got: {msg}");
}

#[test]
fn test_missing_socks5_address_is_err() {
    let result = Config::from_str(NO_ADDRESS);

    assert!(result.is_err(), "expected Err for missing socks5.address");
    let msg = result.unwrap_err().to_string();
    assert!(msg.contains("address") || msg.contains("socks5"), "error should mention address or socks5, got: {msg}");
}

#[test]
fn test_username_without_password_is_err() {
    let result = Config::from_str(USER_NO_PASS);

    assert!(result.is_err(), "expected Err when username is set but password is absent");
}

#[test]
fn test_password_without_username_is_err() {
    let result = Config::from_str(PASS_NO_USER);

    assert!(result.is_err(), "expected Err when password is set but username is absent");
}

#[test]
fn test_defaults_when_optional_sections_absent() {
    let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");

    assert!(cfg.mapdns.is_none(), "mapdns should be None when not in YAML");
    assert_eq!(cfg.misc.task_stack_size, 86016);
    assert_eq!(cfg.misc.tcp_buffer_size, 65536);
    assert_eq!(cfg.misc.udp_recv_buffer_size, 524288);
    assert_eq!(cfg.misc.udp_copy_buffer_nums, 10);
    assert_eq!(cfg.misc.connect_timeout, 10000);
    assert_eq!(cfg.misc.tcp_read_write_timeout, 300000);
    assert_eq!(cfg.misc.udp_read_write_timeout, 60000);
    assert_eq!(cfg.misc.limit_nofile, 65535);
}

#[test]
fn test_mapdns_fields_parsed_correctly() {
    let cfg = Config::from_str(FULL_YAML).expect("full YAML should parse");
    let mapdns = cfg.mapdns.expect("mapdns should be present");

    assert_eq!(mapdns.port, 53);
    assert_eq!(mapdns.network.as_deref(), Some("100.64.0.0"));
    assert_eq!(mapdns.cache_size, 10000);
}

#[test]
fn test_misc_task_stack_size_default() {
    let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");

    assert_eq!(cfg.misc.task_stack_size, 86016);
}

#[test]
fn test_misc_tcp_buffer_size_default() {
    let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");

    assert_eq!(cfg.misc.tcp_buffer_size, 65536);
}

#[test]
fn test_misc_udp_recv_buffer_size_default() {
    let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");

    assert_eq!(cfg.misc.udp_recv_buffer_size, 524288);
}

#[test]
fn test_both_credentials_accepted() {
    let cfg = Config::from_str(FULL_YAML).expect("full YAML with credentials should parse");

    assert_eq!(cfg.socks5.username.as_deref(), Some("user"));
    assert_eq!(cfg.socks5.password.as_deref(), Some("pass"));
}

#[test]
fn test_config_is_send_sync() {
    fn assert_send_sync<T: Send + Sync>() {}

    assert_send_sync::<Config>();
}

// --- schemaVersion envelope tests ---

const SCHEMA_VERSION_ONE: &str = r#"
schemaVersion: 1
socks5:
  port: 1080
  address: 127.0.0.1
"#;

const SCHEMA_VERSION_TWO: &str = r#"
schemaVersion: 2
socks5:
  port: 1080
  address: 127.0.0.1
"#;

#[test]
fn legacy_payload_without_schema_version_parses() {
    // `MINIMAL_VALID` carries no `schemaVersion` key -- a legacy payload.
    let result = Config::from_str(MINIMAL_VALID);

    assert!(result.is_ok(), "legacy YAML without schemaVersion should parse: {result:?}");
}

#[test]
fn payload_with_explicit_schema_version_one_parses() {
    let result = Config::from_str(SCHEMA_VERSION_ONE);

    assert!(result.is_ok(), "YAML with schemaVersion 1 should parse: {result:?}");
}

#[test]
fn payload_with_unsupported_schema_version_is_rejected() {
    let err = Config::from_str(SCHEMA_VERSION_TWO).expect_err("schemaVersion 2 should be rejected");

    assert!(
        matches!(err, ConfigError::UnsupportedSchemaVersion { found: 2 }),
        "expected UnsupportedSchemaVersion {{ found: 2 }}, got: {err:?}"
    );
    assert!(
        err.to_string().contains("unsupported native config schemaVersion 2"),
        "error should name the found version, got: {err}"
    );
}

fn build_minimal_yaml(port: u16, address: &str) -> String {
    format!("socks5:\n  port: {port}\n  address: \"{address}\"\n")
}

proptest! {
    #[test]
    fn prop_valid_config_parses(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
    ) {
        let yaml = build_minimal_yaml(port, &address);
        let result = Config::from_str(&yaml);

        prop_assert!(result.is_ok(), "expected Ok, got: {:?}", result);
        let cfg = result.unwrap();
        prop_assert_eq!(cfg.socks5.port, port);
        prop_assert_eq!(cfg.socks5.address, address);
    }

    #[test]
    fn prop_missing_port_fails(address in "[a-zA-Z0-9._]{1,30}") {
        let yaml = format!("socks5:\n  address: \"{address}\"\n");

        prop_assert!(Config::from_str(&yaml).is_err());
    }

    #[test]
    fn prop_missing_address_fails(port in 1u16..=65535u16) {
        let yaml = format!("socks5:\n  port: {port}\n");

        prop_assert!(Config::from_str(&yaml).is_err());
    }

    #[test]
    fn prop_username_without_password_fails(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
        username in "[a-zA-Z0-9]{1,20}",
    ) {
        let yaml = format!(
            "socks5:\n  port: {port}\n  address: \"{address}\"\n  username: \"{username}\"\n"
        );

        prop_assert!(Config::from_str(&yaml).is_err());
    }

    #[test]
    fn prop_password_without_username_fails(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
        password in "[a-zA-Z0-9]{1,20}",
    ) {
        let yaml = format!(
            "socks5:\n  port: {port}\n  address: \"{address}\"\n  password: \"{password}\"\n"
        );

        prop_assert!(Config::from_str(&yaml).is_err());
    }

    #[test]
    fn prop_both_credentials_parses(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
        username in "[a-zA-Z0-9]{1,20}",
        password in "[a-zA-Z0-9]{1,20}",
    ) {
        let yaml = format!(
            "socks5:\n  port: {port}\n  address: \"{address}\"\n  username: \"{username}\"\n  password: \"{password}\"\n"
        );
        let result = Config::from_str(&yaml);

        prop_assert!(result.is_ok(), "both credentials should be accepted: {:?}", result);
        let cfg = result.unwrap();
        prop_assert_eq!(cfg.socks5.username.as_deref(), Some(username.as_str()));
        prop_assert_eq!(cfg.socks5.password.as_deref(), Some(password.as_str()));
    }

    #[test]
    fn prop_misc_defaults_always_applied(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
    ) {
        let yaml = build_minimal_yaml(port, &address);
        let cfg = Config::from_str(&yaml).unwrap();

        prop_assert_eq!(cfg.misc.task_stack_size, 86016);
        prop_assert_eq!(cfg.misc.tcp_buffer_size, 65536);
        prop_assert_eq!(cfg.misc.udp_recv_buffer_size, 524288);
        prop_assert_eq!(cfg.misc.connect_timeout, 10000);
        prop_assert_eq!(cfg.misc.tcp_read_write_timeout, 300000);
        prop_assert_eq!(cfg.misc.udp_read_write_timeout, 60000);
        prop_assert_eq!(cfg.misc.limit_nofile, 65535);
        prop_assert_eq!(cfg.misc.filter_injected_resets, false);
    }

    #[test]
    fn prop_tunnel_defaults_always_applied(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
    ) {
        let yaml = build_minimal_yaml(port, &address);
        let cfg = Config::from_str(&yaml).unwrap();

        prop_assert_eq!(cfg.tunnel.name.as_str(), "tun0");
        prop_assert_eq!(cfg.tunnel.mtu, 1500);
        prop_assert!(!cfg.tunnel.multi_queue);
        prop_assert!(cfg.tunnel.ipv4.is_none());
        prop_assert!(cfg.tunnel.ipv6.is_none());
    }

    #[test]
    fn prop_mapdns_absent_when_not_in_yaml(
        port in 1u16..=65535u16,
        address in "[a-zA-Z0-9._]{1,30}",
    ) {
        let yaml = build_minimal_yaml(port, &address);
        let cfg = Config::from_str(&yaml).unwrap();

        prop_assert!(cfg.mapdns.is_none());
    }
}
