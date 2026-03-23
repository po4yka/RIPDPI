use serde::Deserialize;
use std::{fs, str::FromStr};
use thiserror::Error;

/// Errors that can occur when loading or validating a config file.
#[derive(Debug, Error)]
pub enum ConfigError {
    #[error(transparent)]
    Yaml(#[from] serde_yaml::Error),
    #[error("socks5 credentials: username and password must both be present or both absent")]
    MismatchedCredentials,
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

/// Top-level tunnel configuration.
#[derive(Debug, Clone)]
pub struct Config {
    pub tunnel: TunnelConfig,
    pub socks5: Socks5Config,
    pub mapdns: Option<MapDnsConfig>,
    pub misc: MiscConfig,
}

impl Config {
    /// Read and parse configuration from a file path.
    pub fn from_file(path: &str) -> Result<Self, ConfigError> {
        let content = fs::read_to_string(path)?;
        content.parse()
    }

    fn validate(raw: RawConfig) -> Result<Self, ConfigError> {
        match (&raw.socks5.username, &raw.socks5.password) {
            (Some(_), None) | (None, Some(_)) => {
                return Err(ConfigError::MismatchedCredentials);
            }
            _ => {}
        }
        Ok(Config { tunnel: raw.tunnel, socks5: raw.socks5, mapdns: raw.mapdns, misc: raw.misc })
    }
}

impl FromStr for Config {
    type Err = ConfigError;

    fn from_str(yaml: &str) -> Result<Self, Self::Err> {
        let raw: RawConfig = serde_yaml::from_str(yaml)?;
        Self::validate(raw)
    }
}

/// Private deserialization target (not exposed publicly).
#[derive(Deserialize)]
#[serde(rename_all = "kebab-case")]
struct RawConfig {
    #[serde(default)]
    tunnel: TunnelConfig,
    socks5: Socks5Config,
    mapdns: Option<MapDnsConfig>,
    #[serde(default)]
    misc: MiscConfig,
}

// ── TunnelConfig ─────────────────────────────────────────────────────────────

fn default_tun_name() -> String {
    "tun0".to_string()
}
fn default_tun_mtu() -> u32 {
    1500
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct TunnelConfig {
    #[serde(default = "default_tun_name")]
    pub name: String,
    #[serde(default = "default_tun_mtu")]
    pub mtu: u32,
    #[serde(default)]
    pub multi_queue: bool,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub post_up_script: Option<String>,
    pub pre_down_script: Option<String>,
}

impl Default for TunnelConfig {
    fn default() -> Self {
        Self {
            name: default_tun_name(),
            mtu: default_tun_mtu(),
            multi_queue: false,
            ipv4: None,
            ipv6: None,
            post_up_script: None,
            pre_down_script: None,
        }
    }
}

// ── Socks5Config ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct Socks5Config {
    pub port: u16,
    pub address: String,
    pub udp: Option<String>,
    pub udp_address: Option<String>,
    pub pipeline: Option<bool>,
    pub username: Option<String>,
    pub password: Option<String>,
    pub mark: Option<u32>,
}

// ── MapDnsConfig ─────────────────────────────────────────────────────────────

fn default_mapdns_port() -> u16 {
    53
}
fn default_mapdns_cache_size() -> u32 {
    10000
}
fn default_dns_query_timeout_ms() -> u32 {
    4000
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct MapDnsConfig {
    pub address: String,
    #[serde(default = "default_mapdns_port")]
    pub port: u16,
    pub network: Option<String>,
    pub netmask: Option<String>,
    #[serde(default = "default_mapdns_cache_size")]
    pub cache_size: u32,
    pub resolver_id: Option<String>,
    pub encrypted_dns_protocol: Option<String>,
    pub encrypted_dns_host: Option<String>,
    pub encrypted_dns_port: Option<u16>,
    pub encrypted_dns_tls_server_name: Option<String>,
    #[serde(default)]
    pub encrypted_dns_bootstrap_ips: Vec<String>,
    pub encrypted_dns_doh_url: Option<String>,
    pub encrypted_dns_dnscrypt_provider_name: Option<String>,
    pub encrypted_dns_dnscrypt_public_key: Option<String>,
    // Deprecated compatibility fields kept for older snapshots and tests.
    pub doh_url: Option<String>,
    #[serde(default)]
    pub doh_bootstrap_ips: Vec<String>,
    #[serde(default = "default_dns_query_timeout_ms")]
    pub dns_query_timeout_ms: u32,
    #[serde(default)]
    pub resolver_fallback_active: bool,
    pub resolver_fallback_reason: Option<String>,
}

// ── MiscConfig ───────────────────────────────────────────────────────────────

fn default_task_stack_size() -> u32 {
    86016
}
fn default_tcp_buffer_size() -> u32 {
    65536
}
fn default_udp_recv_buffer_size() -> u32 {
    524288
}
fn default_udp_copy_buffer_nums() -> u32 {
    10
}
fn default_connect_timeout() -> u32 {
    10000
}
fn default_tcp_rw_timeout() -> u32 {
    300000
}
fn default_udp_rw_timeout() -> u32 {
    60000
}
fn default_limit_nofile() -> u32 {
    65535
}
fn default_log_level() -> String {
    "warn".to_string()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct MiscConfig {
    #[serde(default = "default_task_stack_size")]
    pub task_stack_size: u32,
    #[serde(default = "default_tcp_buffer_size")]
    pub tcp_buffer_size: u32,
    #[serde(default = "default_udp_recv_buffer_size")]
    pub udp_recv_buffer_size: u32,
    #[serde(default = "default_udp_copy_buffer_nums")]
    pub udp_copy_buffer_nums: u32,
    #[serde(default)]
    pub max_session_count: u32,
    #[serde(default = "default_connect_timeout")]
    pub connect_timeout: u32,
    #[serde(default = "default_tcp_rw_timeout")]
    pub tcp_read_write_timeout: u32,
    #[serde(default = "default_udp_rw_timeout")]
    pub udp_read_write_timeout: u32,
    pub log_file: Option<String>,
    #[serde(default = "default_log_level")]
    pub log_level: String,
    pub pid_file: Option<String>,
    #[serde(default = "default_limit_nofile")]
    pub limit_nofile: u32,
    #[serde(default)]
    pub filter_injected_resets: bool,
}

impl Default for MiscConfig {
    fn default() -> Self {
        Self {
            task_stack_size: default_task_stack_size(),
            tcp_buffer_size: default_tcp_buffer_size(),
            udp_recv_buffer_size: default_udp_recv_buffer_size(),
            udp_copy_buffer_nums: default_udp_copy_buffer_nums(),
            max_session_count: 0,
            connect_timeout: default_connect_timeout(),
            tcp_read_write_timeout: default_tcp_rw_timeout(),
            udp_read_write_timeout: default_udp_rw_timeout(),
            log_file: None,
            log_level: default_log_level(),
            pid_file: None,
            limit_nofile: default_limit_nofile(),
            filter_injected_resets: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Minimal YAML with only the two required socks5 fields.
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

    // Full example matching conf/main.yml structure.
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

    /// Test 1: parse conf/main.yml — all expected fields present.
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

    /// Test 2: missing socks5.port → Err with descriptive message.
    #[test]
    fn test_missing_socks5_port_is_err() {
        let result = Config::from_str(NO_PORT);
        assert!(result.is_err(), "expected Err for missing socks5.port");
        let msg = result.unwrap_err().to_string();
        assert!(msg.contains("port") || msg.contains("socks5"), "error should mention port or socks5, got: {msg}");
    }

    /// Test 3: missing socks5.address → Err with descriptive message.
    #[test]
    fn test_missing_socks5_address_is_err() {
        let result = Config::from_str(NO_ADDRESS);
        assert!(result.is_err(), "expected Err for missing socks5.address");
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("address") || msg.contains("socks5"),
            "error should mention address or socks5, got: {msg}"
        );
    }

    /// Test 4: username present but password absent → Err.
    #[test]
    fn test_username_without_password_is_err() {
        let result = Config::from_str(USER_NO_PASS);
        assert!(result.is_err(), "expected Err when username is set but password is absent");
    }

    /// Test 5: password present but username absent → Err.
    #[test]
    fn test_password_without_username_is_err() {
        let result = Config::from_str(PASS_NO_USER);
        assert!(result.is_err(), "expected Err when password is set but username is absent");
    }

    /// Test 6: all defaults applied when optional sections are absent.
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

    /// Test 7: mapdns defaults — port 53, network "100.64.0.0".
    #[test]
    fn test_mapdns_fields_parsed_correctly() {
        let cfg = Config::from_str(FULL_YAML).expect("full YAML should parse");
        let mapdns = cfg.mapdns.expect("mapdns should be present");
        assert_eq!(mapdns.port, 53);
        assert_eq!(mapdns.network.as_deref(), Some("100.64.0.0"));
        assert_eq!(mapdns.cache_size, 10000);
    }

    /// Test 8: misc.task_stack_size default = 86016.
    #[test]
    fn test_misc_task_stack_size_default() {
        let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");
        assert_eq!(cfg.misc.task_stack_size, 86016);
    }

    /// Test 9: misc.tcp_buffer_size default = 65536.
    #[test]
    fn test_misc_tcp_buffer_size_default() {
        let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");
        assert_eq!(cfg.misc.tcp_buffer_size, 65536);
    }

    /// Test 10: misc.udp_recv_buffer_size default = 524288.
    #[test]
    fn test_misc_udp_recv_buffer_size_default() {
        let cfg = Config::from_str(MINIMAL_VALID).expect("minimal YAML should parse");
        assert_eq!(cfg.misc.udp_recv_buffer_size, 524288);
    }

    /// Test 11: both username and password present → Ok, fields populated.
    #[test]
    fn test_both_credentials_accepted() {
        let cfg = Config::from_str(FULL_YAML).expect("full YAML with credentials should parse");
        assert_eq!(cfg.socks5.username.as_deref(), Some("user"));
        assert_eq!(cfg.socks5.password.as_deref(), Some("pass"));
    }

    /// Test 12: Config is Send + Sync (compile-time check).
    #[test]
    fn test_config_is_send_sync() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<Config>();
    }
}

#[cfg(test)]
mod proptest_tests {
    use super::*;
    use proptest::prelude::*;

    fn build_minimal_yaml(port: u16, address: &str) -> String {
        format!("socks5:\n  port: {port}\n  address: \"{address}\"\n")
    }

    proptest! {
        /// Any valid (port, address) pair parses and round-trips field values.
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

        /// Missing socks5.port always produces Err.
        #[test]
        fn prop_missing_port_fails(address in "[a-zA-Z0-9._]{1,30}") {
            let yaml = format!("socks5:\n  address: \"{address}\"\n");
            prop_assert!(Config::from_str(&yaml).is_err());
        }

        /// Missing socks5.address always produces Err.
        #[test]
        fn prop_missing_address_fails(port in 1u16..=65535u16) {
            let yaml = format!("socks5:\n  port: {port}\n");
            prop_assert!(Config::from_str(&yaml).is_err());
        }

        /// username present without password always produces Err.
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

        /// password present without username always produces Err.
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

        /// Both username and password present always parses and field values match.
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

        /// misc defaults are applied whenever the misc section is absent.
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

        /// tunnel defaults are applied whenever the tunnel section is absent.
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

        /// mapdns is None whenever the mapdns section is absent.
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
}
