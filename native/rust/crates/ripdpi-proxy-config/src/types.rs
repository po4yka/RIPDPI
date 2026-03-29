use ripdpi_config::{RuntimeConfig, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS};
use serde::{Deserialize, Serialize};

pub(crate) const HOSTS_DISABLE: &str = "disable";
pub(crate) const HOSTS_BLACKLIST: &str = "blacklist";
pub(crate) const HOSTS_WHITELIST: &str = "whitelist";
pub(crate) const TLS_RANDREC_DEFAULT_FRAGMENT_COUNT: i32 = 4;
pub(crate) const TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE: i32 = 16;
pub(crate) const TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE: i32 = 96;
pub const FAKE_TLS_SNI_MODE_FIXED: &str = "fixed";
pub const FAKE_TLS_SNI_MODE_RANDOMIZED: &str = "randomized";
pub const QUIC_FAKE_PROFILE_DISABLED: &str = "disabled";
pub const FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT: &str = "compat_default";
pub const ADAPTIVE_FAKE_TTL_DEFAULT_DELTA: i32 = -1;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_MIN: i32 = 3;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_MAX: i32 = 12;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK: i32 = 8;
pub const HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS: i64 = 6;

#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum ProxyConfigError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "snake_case")]
#[allow(clippy::large_enum_variant)]
pub enum ProxyConfigPayload {
    CommandLine {
        args: Vec<String>,
        #[serde(default)]
        host_autolearn_store_path: Option<String>,
        #[serde(default)]
        runtime_context: Option<ProxyRuntimeContext>,
        #[serde(default)]
        log_context: Option<ProxyLogContext>,
    },
    Ui {
        #[serde(default)]
        strategy_preset: Option<String>,
        #[serde(flatten)]
        config: ProxyUiConfig,
        #[serde(default)]
        runtime_context: Option<ProxyRuntimeContext>,
        #[serde(default)]
        log_context: Option<ProxyLogContext>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyRuntimeContext {
    #[serde(default)]
    pub encrypted_dns: Option<ProxyEncryptedDnsContext>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyLogContext {
    #[serde(default)]
    pub runtime_id: Option<String>,
    #[serde(default)]
    pub mode: Option<String>,
    #[serde(default)]
    pub policy_signature: Option<String>,
    #[serde(default)]
    pub fingerprint_hash: Option<String>,
    #[serde(default)]
    pub diagnostics_session_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyEncryptedDnsContext {
    #[serde(default)]
    pub resolver_id: Option<String>,
    pub protocol: String,
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub tls_server_name: Option<String>,
    #[serde(default)]
    pub bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub doh_url: Option<String>,
    #[serde(default)]
    pub dnscrypt_provider_name: Option<String>,
    #[serde(default)]
    pub dnscrypt_public_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeConfigEnvelope {
    pub config: RuntimeConfig,
    pub runtime_context: Option<ProxyRuntimeContext>,
    pub log_context: Option<ProxyLogContext>,
    pub native_log_level: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiNumericRange {
    #[serde(default)]
    pub start: Option<i64>,
    #[serde(default)]
    pub end: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiActivationFilter {
    #[serde(default)]
    pub round: Option<ProxyUiNumericRange>,
    #[serde(default)]
    pub payload_size: Option<ProxyUiNumericRange>,
    #[serde(default)]
    pub stream_bytes: Option<ProxyUiNumericRange>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiTcpChainStep {
    pub kind: String,
    pub marker: String,
    #[serde(default)]
    pub midhost_marker: String,
    #[serde(default)]
    pub fake_host_template: String,
    #[serde(default)]
    pub fragment_count: i32,
    #[serde(default)]
    pub min_fragment_size: i32,
    #[serde(default)]
    pub max_fragment_size: i32,
    #[serde(default)]
    pub activation_filter: Option<ProxyUiActivationFilter>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiUdpChainStep {
    pub kind: String,
    pub count: i32,
    #[serde(default)]
    pub split_bytes: i32,
    #[serde(default)]
    pub activation_filter: Option<ProxyUiActivationFilter>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiListenConfig {
    pub ip: String,
    pub port: i32,
    pub max_connections: i32,
    pub buffer_size: i32,
    #[serde(default)]
    pub tcp_fast_open: bool,
    pub default_ttl: i32,
    pub custom_ttl: bool,
    #[serde(default)]
    pub freeze_detection_enabled: bool,
}

impl Default for ProxyUiListenConfig {
    fn default() -> Self {
        Self {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            tcp_fast_open: false,
            default_ttl: 0,
            custom_ttl: false,
            freeze_detection_enabled: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiProtocolConfig {
    #[serde(default = "default_true")]
    pub resolve_domains: bool,
    pub desync_http: bool,
    pub desync_https: bool,
    pub desync_udp: bool,
}

impl Default for ProxyUiProtocolConfig {
    fn default() -> Self {
        Self { resolve_domains: true, desync_http: true, desync_https: true, desync_udp: false }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiChainConfig {
    #[serde(default = "default_tcp_chain_steps")]
    pub tcp_steps: Vec<ProxyUiTcpChainStep>,
    #[serde(default)]
    pub udp_steps: Vec<ProxyUiUdpChainStep>,
    #[serde(default)]
    pub group_activation_filter: Option<ProxyUiActivationFilter>,
}

impl Default for ProxyUiChainConfig {
    fn default() -> Self {
        Self { tcp_steps: default_tcp_chain_steps(), udp_steps: Vec::new(), group_activation_filter: None }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiFakePacketConfig {
    pub fake_ttl: i32,
    #[serde(default)]
    pub adaptive_fake_ttl_enabled: bool,
    #[serde(default = "default_adaptive_fake_ttl_delta")]
    pub adaptive_fake_ttl_delta: i32,
    #[serde(default = "default_adaptive_fake_ttl_min")]
    pub adaptive_fake_ttl_min: i32,
    #[serde(default = "default_adaptive_fake_ttl_max")]
    pub adaptive_fake_ttl_max: i32,
    #[serde(default = "default_adaptive_fake_ttl_fallback")]
    pub adaptive_fake_ttl_fallback: i32,
    pub fake_sni: String,
    #[serde(default = "default_fake_payload_profile")]
    pub http_fake_profile: String,
    #[serde(default)]
    pub fake_tls_use_original: bool,
    #[serde(default)]
    pub fake_tls_randomize: bool,
    #[serde(default)]
    pub fake_tls_dup_session_id: bool,
    #[serde(default)]
    pub fake_tls_pad_encap: bool,
    #[serde(default)]
    pub fake_tls_size: i32,
    #[serde(default = "default_fake_tls_sni_mode")]
    pub fake_tls_sni_mode: String,
    #[serde(default = "default_fake_payload_profile")]
    pub tls_fake_profile: String,
    #[serde(default = "default_fake_payload_profile")]
    pub udp_fake_profile: String,
    #[serde(default = "default_fake_offset_marker")]
    pub fake_offset_marker: String,
    pub oob_char: u8,
    pub drop_sack: bool,
    #[serde(default)]
    pub window_clamp: Option<u32>,
    #[serde(default)]
    pub strip_timestamps: bool,
    #[serde(default)]
    pub quic_bind_low_port: bool,
    #[serde(default)]
    pub quic_migrate_after_handshake: bool,
    #[serde(default)]
    pub quic_fake_version: Option<u32>,
    /// Entropy detection mode: "popcount", "shannon", "combined", or empty (disabled).
    #[serde(default)]
    pub entropy_mode: String,
    /// Popcount target in permil (e.g. 3400 = 3.4). 0 = default (3.4).
    #[serde(default)]
    pub entropy_padding_target_permil: Option<u32>,
    /// Maximum entropy padding bytes. 0 = default (256).
    #[serde(default)]
    pub entropy_padding_max: Option<u32>,
    /// Shannon entropy target in permil (e.g. 7920 = 7.92). 0 = default (7.92).
    #[serde(default)]
    pub shannon_entropy_target_permil: Option<u32>,
}

impl Default for ProxyUiFakePacketConfig {
    fn default() -> Self {
        Self {
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: default_adaptive_fake_ttl_delta(),
            adaptive_fake_ttl_min: default_adaptive_fake_ttl_min(),
            adaptive_fake_ttl_max: default_adaptive_fake_ttl_max(),
            adaptive_fake_ttl_fallback: default_adaptive_fake_ttl_fallback(),
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: default_fake_payload_profile(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: default_fake_payload_profile(),
            udp_fake_profile: default_fake_payload_profile(),
            fake_offset_marker: default_fake_offset_marker(),
            oob_char: b'a',
            drop_sack: false,
            window_clamp: None,
            strip_timestamps: false,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            quic_fake_version: None,
            entropy_mode: String::new(),
            entropy_padding_target_permil: None,
            entropy_padding_max: None,
            shannon_entropy_target_permil: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiParserEvasionConfig {
    pub host_mixed_case: bool,
    pub domain_mixed_case: bool,
    pub host_remove_spaces: bool,
    #[serde(default)]
    pub http_method_eol: bool,
    #[serde(default)]
    pub http_unix_eol: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiQuicConfig {
    #[serde(default = "default_quic_initial_mode")]
    pub initial_mode: String,
    #[serde(default = "default_true")]
    pub support_v1: bool,
    #[serde(default = "default_true")]
    pub support_v2: bool,
    #[serde(default = "default_quic_fake_profile")]
    pub fake_profile: String,
    #[serde(default)]
    pub fake_host: String,
}

impl Default for ProxyUiQuicConfig {
    fn default() -> Self {
        Self {
            initial_mode: default_quic_initial_mode(),
            support_v1: true,
            support_v2: true,
            fake_profile: default_quic_fake_profile(),
            fake_host: String::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiHostsConfig {
    pub mode: String,
    #[serde(default)]
    pub entries: Option<String>,
}

impl Default for ProxyUiHostsConfig {
    fn default() -> Self {
        Self { mode: HOSTS_DISABLE.to_string(), entries: None }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiHostAutolearnConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_host_autolearn_penalty_ttl_hours")]
    pub penalty_ttl_hours: i64,
    #[serde(default = "default_host_autolearn_max_hosts")]
    pub max_hosts: usize,
    #[serde(default)]
    pub store_path: Option<String>,
    #[serde(default)]
    pub network_scope_key: Option<String>,
}

impl Default for ProxyUiHostAutolearnConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            penalty_ttl_hours: default_host_autolearn_penalty_ttl_hours(),
            max_hosts: default_host_autolearn_max_hosts(),
            store_path: None,
            network_scope_key: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiConfig {
    #[serde(default)]
    pub listen: ProxyUiListenConfig,
    #[serde(default)]
    pub protocols: ProxyUiProtocolConfig,
    #[serde(default)]
    pub chains: ProxyUiChainConfig,
    #[serde(default)]
    pub fake_packets: ProxyUiFakePacketConfig,
    #[serde(default)]
    pub parser_evasions: ProxyUiParserEvasionConfig,
    #[serde(default)]
    pub quic: ProxyUiQuicConfig,
    #[serde(default)]
    pub hosts: ProxyUiHostsConfig,
    #[serde(default)]
    pub host_autolearn: ProxyUiHostAutolearnConfig,
    #[serde(default)]
    pub ws_tunnel: ProxyUiWsTunnelConfig,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub native_log_level: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWsTunnelConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub mode: Option<String>,
}

// --- Android OS network state snapshot ---

/// A compact snapshot of Android OS network state, captured from ConnectivityManager,
/// NetworkCapabilities, LinkProperties, TelephonyManager, and TrafficStats.
/// All fields use `#[serde(default)]` for forward-compatible deserialization.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct NetworkSnapshot {
    /// Physical transport: "wifi", "cellular", "ethernet", "vpn", "none", "unknown"
    #[serde(default)]
    pub transport: String,
    /// NET_CAPABILITY_VALIDATED
    #[serde(default)]
    pub validated: bool,
    /// NET_CAPABILITY_CAPTIVE_PORTAL
    #[serde(default)]
    pub captive_portal: bool,
    /// !NET_CAPABILITY_NOT_METERED
    #[serde(default)]
    pub metered: bool,
    /// "system" (default/opportunistic) or strict hostname from Private DNS settings
    #[serde(default)]
    pub private_dns_mode: String,
    /// DNS servers from LinkProperties.getDnsServers()
    #[serde(default)]
    pub dns_servers: Vec<String>,
    /// Present when transport is "cellular"
    #[serde(default)]
    pub cellular: Option<CellularSnapshot>,
    /// Present when transport is "wifi"
    #[serde(default)]
    pub wifi: Option<WifiSnapshot>,
    /// LinkProperties.getMtu() when the platform reports a positive value
    #[serde(default)]
    pub mtu: Option<u32>,
    /// TrafficStats.getUidTxBytes(uid) at capture time
    #[serde(default)]
    pub traffic_tx_bytes: u64,
    /// TrafficStats.getUidRxBytes(uid) at capture time
    #[serde(default)]
    pub traffic_rx_bytes: u64,
    /// System.currentTimeMillis() at capture time
    #[serde(default)]
    pub captured_at_ms: u64,
}

/// Cellular network details, populated when transport is "cellular".
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct CellularSnapshot {
    /// Radio generation: "2g", "3g", "4g", "5g", "unknown"
    #[serde(default)]
    pub generation: String,
    /// Whether the device is roaming
    #[serde(default)]
    pub roaming: bool,
    /// MCC+MNC of the serving network operator
    #[serde(default)]
    pub operator_code: String,
    /// Diagnostics-style mobile network type: "LTE", "NR", "IWLAN", "unknown", etc.
    #[serde(default)]
    pub data_network_type: String,
    /// ServiceState.state normalized to "in_service", "out_of_service", etc.
    #[serde(default)]
    pub service_state: String,
    /// Carrier ID when the platform reports a non-negative value
    #[serde(default)]
    pub carrier_id: Option<i32>,
    /// SignalStrength.level
    #[serde(default)]
    pub signal_level: Option<i32>,
    /// First reported cell signal strength dBm
    #[serde(default)]
    pub signal_dbm: Option<i32>,
}

/// Wi-Fi network details, populated when transport is "wifi".
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct WifiSnapshot {
    /// Frequency band: "2.4ghz", "5ghz", "6ghz", "unknown"
    #[serde(default)]
    pub frequency_band: String,
    /// SHA-256 hex of the sanitized SSID (privacy-preserving; never raw SSID)
    #[serde(default)]
    pub ssid_hash: String,
    /// Wi-Fi frequency in MHz when the platform reports a positive value
    #[serde(default)]
    pub frequency_mhz: Option<i32>,
    /// RSSI in dBm when the platform reports a sane value
    #[serde(default)]
    pub rssi_dbm: Option<i32>,
    /// Wi-Fi link speed in Mbps when the platform reports a positive value
    #[serde(default)]
    pub link_speed_mbps: Option<i32>,
    /// Wi-Fi RX link speed in Mbps when available
    #[serde(default)]
    pub rx_link_speed_mbps: Option<i32>,
    /// Wi-Fi TX link speed in Mbps when available
    #[serde(default)]
    pub tx_link_speed_mbps: Option<i32>,
    /// Diagnostics-style channel width label: "20 MHz", "80 MHz", "unknown", etc.
    #[serde(default)]
    pub channel_width: String,
    /// Diagnostics-style standard label: "802.11ax", "legacy", "unknown", etc.
    #[serde(default)]
    pub wifi_standard: String,
}

fn default_true() -> bool {
    true
}

fn default_tcp_chain_steps() -> Vec<ProxyUiTcpChainStep> {
    vec![ProxyUiTcpChainStep {
        kind: "disorder".to_string(),
        marker: "1".to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
    }]
}

fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

fn default_quic_initial_mode() -> String {
    "route_and_cache".to_string()
}

fn default_quic_fake_profile() -> String {
    QUIC_FAKE_PROFILE_DISABLED.to_string()
}

fn default_fake_payload_profile() -> String {
    FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string()
}

fn default_fake_offset_marker() -> String {
    "0".to_string()
}

fn default_adaptive_fake_ttl_delta() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_DELTA
}

fn default_adaptive_fake_ttl_min() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_MIN
}

fn default_adaptive_fake_ttl_max() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_MAX
}

fn default_adaptive_fake_ttl_fallback() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
}

fn default_host_autolearn_penalty_ttl_hours() -> i64 {
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS
}

fn default_host_autolearn_max_hosts() -> usize {
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn network_snapshot_field_manifest_matches_contract_fixture() {
        use golden_test_support::{assert_contract_fixture, extract_field_paths};

        let snapshot = NetworkSnapshot {
            transport: "cellular".to_string(),
            validated: true,
            captive_portal: false,
            metered: true,
            private_dns_mode: "system".to_string(),
            dns_servers: vec!["8.8.8.8".to_string(), "8.8.4.4".to_string()],
            cellular: Some(CellularSnapshot {
                generation: "4g".to_string(),
                roaming: false,
                operator_code: "310260".to_string(),
                data_network_type: "LTE".to_string(),
                service_state: "in_service".to_string(),
                carrier_id: Some(1),
                signal_level: Some(3),
                signal_dbm: Some(-85),
            }),
            wifi: Some(WifiSnapshot {
                frequency_band: "5ghz".to_string(),
                ssid_hash: "abc123def456".to_string(),
                frequency_mhz: Some(5180),
                rssi_dbm: Some(-55),
                link_speed_mbps: Some(866),
                rx_link_speed_mbps: Some(780),
                tx_link_speed_mbps: Some(866),
                channel_width: "80 MHz".to_string(),
                wifi_standard: "802.11ax".to_string(),
            }),
            mtu: Some(1500),
            traffic_tx_bytes: 123456,
            traffic_rx_bytes: 654321,
            captured_at_ms: 1700000000000,
        };

        let json = serde_json::to_value(&snapshot).expect("serialize network snapshot");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("network_snapshot_fields.json", &manifest);
    }
}
