use ciadpi_config::{HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS, RuntimeConfig};
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

#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum ProxyConfigError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ProxyConfigPayload {
    CommandLine {
        args: Vec<String>,
        #[serde(default)]
        runtime_context: Option<ProxyRuntimeContext>,
    },
    Ui {
        #[serde(flatten)]
        config: ProxyUiConfig,
        #[serde(default)]
        runtime_context: Option<ProxyRuntimeContext>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyRuntimeContext {
    #[serde(default)]
    pub encrypted_dns: Option<ProxyEncryptedDnsContext>,
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
    pub midhost_marker: Option<String>,
    #[serde(default)]
    pub fake_host_template: Option<String>,
    #[serde(default)]
    pub fragment_count: i32,
    #[serde(default)]
    pub min_fragment_size: i32,
    #[serde(default)]
    pub max_fragment_size: i32,
    #[serde(default)]
    pub activation_filter: ProxyUiActivationFilter,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiUdpChainStep {
    pub kind: String,
    pub count: i32,
    #[serde(default)]
    pub activation_filter: ProxyUiActivationFilter,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiConfig {
    pub ip: String,
    pub port: i32,
    pub max_connections: i32,
    pub buffer_size: i32,
    pub default_ttl: i32,
    pub custom_ttl: bool,
    pub no_domain: bool,
    pub desync_http: bool,
    pub desync_https: bool,
    pub desync_udp: bool,
    pub desync_method: String,
    #[serde(default)]
    pub split_marker: Option<String>,
    #[serde(default)]
    pub tcp_chain_steps: Vec<ProxyUiTcpChainStep>,
    #[serde(default)]
    pub group_activation_filter: ProxyUiActivationFilter,
    #[serde(default)]
    pub split_position: i32,
    #[serde(default)]
    pub split_at_host: bool,
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
    pub oob_char: u8,
    pub host_mixed_case: bool,
    pub domain_mixed_case: bool,
    pub host_remove_spaces: bool,
    #[serde(default)]
    pub http_method_eol: bool,
    #[serde(default)]
    pub http_unix_eol: bool,
    pub tls_record_split: bool,
    #[serde(default)]
    pub tls_record_split_marker: Option<String>,
    #[serde(default)]
    pub tls_record_split_position: i32,
    #[serde(default)]
    pub tls_record_split_at_sni: bool,
    pub hosts_mode: String,
    pub hosts: Option<String>,
    pub tcp_fast_open: bool,
    pub udp_fake_count: i32,
    #[serde(default)]
    pub udp_chain_steps: Vec<ProxyUiUdpChainStep>,
    #[serde(default = "default_fake_payload_profile")]
    pub udp_fake_profile: String,
    pub drop_sack: bool,
    #[serde(default)]
    pub fake_offset_marker: Option<String>,
    #[serde(default)]
    pub fake_offset: i32,
    #[serde(default)]
    pub quic_initial_mode: Option<String>,
    #[serde(default = "default_true")]
    pub quic_support_v1: bool,
    #[serde(default = "default_true")]
    pub quic_support_v2: bool,
    #[serde(default = "default_quic_fake_profile")]
    pub quic_fake_profile: String,
    #[serde(default)]
    pub quic_fake_host: String,
    #[serde(default)]
    pub host_autolearn_enabled: bool,
    #[serde(default = "default_host_autolearn_penalty_ttl_secs")]
    pub host_autolearn_penalty_ttl_secs: i64,
    #[serde(default = "default_host_autolearn_max_hosts")]
    pub host_autolearn_max_hosts: usize,
    #[serde(default)]
    pub host_autolearn_store_path: Option<String>,
    #[serde(default)]
    pub network_scope_key: Option<String>,
    #[serde(default)]
    pub strategy_preset: Option<String>,
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
}

fn default_true() -> bool {
    true
}

fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

fn default_quic_fake_profile() -> String {
    QUIC_FAKE_PROFILE_DISABLED.to_string()
}

fn default_fake_payload_profile() -> String {
    FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string()
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

fn default_host_autolearn_penalty_ttl_secs() -> i64 {
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS
}

fn default_host_autolearn_max_hosts() -> usize {
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS
}
