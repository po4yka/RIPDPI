use ripdpi_config::{RuntimeConfig, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS};
use serde::{Deserialize, Serialize};

pub(crate) const HOSTS_DISABLE: &str = "disable";
pub(crate) const HOSTS_BLACKLIST: &str = "blacklist";
pub(crate) const HOSTS_WHITELIST: &str = "whitelist";
pub(crate) const WARP_ROUTE_MODE_OFF: &str = "off";
pub(crate) const WARP_ROUTE_MODE_RULES: &str = "rules";
pub(crate) const WARP_ENDPOINT_SELECTION_AUTOMATIC: &str = "automatic";
pub(crate) const RELAY_KIND_OFF: &str = "off";
pub(crate) const TLS_RANDREC_DEFAULT_FRAGMENT_COUNT: i32 = 4;
pub(crate) const TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE: i32 = 16;
pub(crate) const TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE: i32 = 96;
pub const FAKE_TLS_SNI_MODE_FIXED: &str = "fixed";
pub const FAKE_TLS_SNI_MODE_RANDOMIZED: &str = "randomized";
pub const FAKE_TLS_SOURCE_PROFILE: &str = "profile";
pub const FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO: &str = "captured_client_hello";
pub const QUIC_FAKE_PROFILE_DISABLED: &str = "disabled";
pub const FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT: &str = "compat_default";
pub const SEQOVL_FAKE_MODE_PROFILE: &str = "profile";
pub const SEQOVL_FAKE_MODE_RAND: &str = "rand";
pub const SEQOVL_DEFAULT_OVERLAP_SIZE: i32 = 12;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_DELTA: i32 = -1;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_MIN: i32 = 3;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_MAX: i32 = 12;
pub const ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK: i32 = 8;
pub const ADAPTIVE_FALLBACK_DEFAULT_CACHE_TTL_SECS: i64 = 90;
pub const ADAPTIVE_FALLBACK_DEFAULT_CACHE_PREFIX_V4: u8 = 24;
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
    #[serde(default)]
    pub protect_path: Option<String>,
    #[serde(default)]
    pub preferred_edges: std::collections::BTreeMap<String, Vec<ProxyPreferredEdge>>,
    #[serde(default)]
    pub direct_path_capabilities: Vec<ProxyDirectPathCapability>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyPreferredEdge {
    pub ip: String,
    pub transport_kind: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyDirectPathCapability {
    pub authority: String,
    #[serde(default)]
    pub quic_usable: Option<bool>,
    #[serde(default)]
    pub udp_usable: Option<bool>,
    #[serde(default)]
    pub fallback_required: Option<bool>,
    #[serde(default)]
    pub repeated_handshake_failure_class: Option<String>,
    #[serde(default)]
    pub updated_at: i64,
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
    pub overlap_size: i32,
    #[serde(default = "default_seqovl_fake_mode")]
    pub fake_mode: String,
    #[serde(default)]
    pub fragment_count: i32,
    #[serde(default)]
    pub min_fragment_size: i32,
    #[serde(default)]
    pub max_fragment_size: i32,
    #[serde(default)]
    pub inter_segment_delay_ms: u32,
    #[serde(default)]
    pub activation_filter: Option<ProxyUiActivationFilter>,
    #[serde(default = "default_ipv6_extension_profile")]
    pub ipv6_extension_profile: String,
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
    #[serde(default = "default_ipv6_extension_profile")]
    pub ipv6_extension_profile: String,
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
    #[serde(default)]
    pub auth_token: Option<String>,
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
            auth_token: None,
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
    #[serde(default)]
    pub any_protocol: bool,
}

impl Default for ProxyUiChainConfig {
    fn default() -> Self {
        Self {
            tcp_steps: default_tcp_chain_steps(),
            udp_steps: Vec::new(),
            group_activation_filter: None,
            any_protocol: false,
        }
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
    #[serde(default = "default_fake_tls_source")]
    pub fake_tls_source: String,
    #[serde(default)]
    pub fake_tls_secondary_profile: String,
    #[serde(default)]
    pub fake_tcp_timestamp_enabled: bool,
    #[serde(default)]
    pub fake_tcp_timestamp_delta_ticks: i32,
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
    pub wsize_window: Option<u32>,
    #[serde(default)]
    pub wsize_scale: Option<i32>,
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
    #[serde(default = "default_tls_fingerprint_profile")]
    pub tls_fingerprint_profile: String,
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
            fake_tls_source: default_fake_tls_source(),
            fake_tls_secondary_profile: String::new(),
            fake_tcp_timestamp_enabled: false,
            fake_tcp_timestamp_delta_ticks: 0,
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
            wsize_window: None,
            wsize_scale: None,
            strip_timestamps: false,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            quic_fake_version: None,
            entropy_mode: String::new(),
            entropy_padding_target_permil: None,
            entropy_padding_max: None,
            shannon_entropy_target_permil: None,
            tls_fingerprint_profile: default_tls_fingerprint_profile(),
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
    #[serde(default)]
    pub http_method_space: bool,
    #[serde(default)]
    pub http_host_pad: bool,
    #[serde(default)]
    pub http_host_extra_space: bool,
    #[serde(default)]
    pub http_host_tab: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiAdaptiveFallbackConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default = "default_true")]
    pub torst: bool,
    #[serde(default = "default_true")]
    pub tls_err: bool,
    #[serde(default = "default_true")]
    pub http_redirect: bool,
    #[serde(default = "default_true")]
    pub connect_failure: bool,
    #[serde(default = "default_true")]
    pub auto_sort: bool,
    #[serde(default = "default_adaptive_fallback_cache_ttl_secs")]
    pub cache_ttl_seconds: i64,
    #[serde(default = "default_adaptive_fallback_cache_prefix_v4")]
    pub cache_prefix_v4: u8,
}

impl Default for ProxyUiAdaptiveFallbackConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            torst: true,
            tls_err: true,
            http_redirect: true,
            connect_failure: true,
            auto_sort: true,
            cache_ttl_seconds: default_adaptive_fallback_cache_ttl_secs(),
            cache_prefix_v4: default_adaptive_fallback_cache_prefix_v4(),
        }
    }
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
pub struct ProxyUiWarpManualEndpointConfig {
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub ipv4: String,
    #[serde(default)]
    pub ipv6: String,
    #[serde(default = "default_warp_manual_endpoint_port")]
    pub port: i32,
}

impl Default for ProxyUiWarpManualEndpointConfig {
    fn default() -> Self {
        Self {
            host: String::new(),
            ipv4: String::new(),
            ipv6: String::new(),
            port: default_warp_manual_endpoint_port(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWarpAmneziaConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub jc: i32,
    #[serde(default)]
    pub jmin: i32,
    #[serde(default)]
    pub jmax: i32,
    #[serde(default)]
    pub h1: i64,
    #[serde(default)]
    pub h2: i64,
    #[serde(default)]
    pub h3: i64,
    #[serde(default)]
    pub h4: i64,
    #[serde(default)]
    pub s1: i32,
    #[serde(default)]
    pub s2: i32,
    #[serde(default)]
    pub s3: i32,
    #[serde(default)]
    pub s4: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWarpConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_warp_route_mode")]
    pub route_mode: String,
    #[serde(default)]
    pub route_hosts: String,
    #[serde(default = "default_true")]
    pub built_in_rules_enabled: bool,
    #[serde(default = "default_warp_endpoint_selection_mode")]
    pub endpoint_selection_mode: String,
    #[serde(default)]
    pub manual_endpoint: ProxyUiWarpManualEndpointConfig,
    #[serde(default = "default_true")]
    pub scanner_enabled: bool,
    #[serde(default = "default_warp_scanner_parallelism")]
    pub scanner_parallelism: i32,
    #[serde(default = "default_warp_scanner_max_rtt_ms")]
    pub scanner_max_rtt_ms: i32,
    #[serde(default)]
    pub amnezia: ProxyUiWarpAmneziaConfig,
    #[serde(default = "default_warp_local_socks_host")]
    pub local_socks_host: String,
    #[serde(default = "default_warp_local_socks_port")]
    pub local_socks_port: i32,
}

impl Default for ProxyUiWarpConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            route_mode: default_warp_route_mode(),
            route_hosts: String::new(),
            built_in_rules_enabled: true,
            endpoint_selection_mode: default_warp_endpoint_selection_mode(),
            manual_endpoint: ProxyUiWarpManualEndpointConfig::default(),
            scanner_enabled: true,
            scanner_parallelism: default_warp_scanner_parallelism(),
            scanner_max_rtt_ms: default_warp_scanner_max_rtt_ms(),
            amnezia: ProxyUiWarpAmneziaConfig::default(),
            local_socks_host: default_warp_local_socks_host(),
            local_socks_port: default_warp_local_socks_port(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiRelayConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_relay_kind")]
    pub kind: String,
    #[serde(default)]
    pub profile_id: String,
    #[serde(default)]
    pub outbound_bind_ip: String,
    #[serde(default)]
    pub server: String,
    #[serde(default = "default_relay_server_port")]
    pub server_port: i32,
    #[serde(default)]
    pub server_name: String,
    #[serde(default)]
    pub reality_public_key: String,
    #[serde(default)]
    pub reality_short_id: String,
    #[serde(default)]
    pub chain_entry_server: String,
    #[serde(default = "default_relay_server_port")]
    pub chain_entry_port: i32,
    #[serde(default)]
    pub chain_entry_server_name: String,
    #[serde(default)]
    pub chain_entry_public_key: String,
    #[serde(default)]
    pub chain_entry_short_id: String,
    #[serde(default)]
    pub chain_exit_server: String,
    #[serde(default = "default_relay_server_port")]
    pub chain_exit_port: i32,
    #[serde(default)]
    pub chain_exit_server_name: String,
    #[serde(default)]
    pub chain_exit_public_key: String,
    #[serde(default)]
    pub chain_exit_short_id: String,
    #[serde(default)]
    pub masque_url: String,
    #[serde(default = "default_true")]
    pub masque_use_http2_fallback: bool,
    #[serde(default)]
    pub masque_cloudflare_mode: bool,
    #[serde(default = "default_relay_local_socks_host")]
    pub local_socks_host: String,
    #[serde(default = "default_relay_local_socks_port")]
    pub local_socks_port: i32,
    #[serde(default)]
    pub udp_enabled: bool,
    #[serde(default = "default_true")]
    pub tcp_fallback_enabled: bool,
}

impl Default for ProxyUiRelayConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            kind: default_relay_kind(),
            profile_id: String::new(),
            outbound_bind_ip: String::new(),
            server: String::new(),
            server_port: default_relay_server_port(),
            server_name: String::new(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: default_relay_server_port(),
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: default_relay_server_port(),
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            masque_url: String::new(),
            masque_use_http2_fallback: true,
            masque_cloudflare_mode: false,
            local_socks_host: default_relay_local_socks_host(),
            local_socks_port: default_relay_local_socks_port(),
            udp_enabled: false,
            tcp_fallback_enabled: true,
        }
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
    /// When true (default), spawn a background warmup probe after VPN start to
    /// pre-populate the autolearn table with commonly-blocked domains.
    #[serde(default = "default_warmup_probe_enabled")]
    pub warmup_probe_enabled: bool,
    #[serde(default = "default_network_reprobe_enabled")]
    pub network_reprobe_enabled: bool,
}

fn default_warmup_probe_enabled() -> bool {
    true
}

fn default_network_reprobe_enabled() -> bool {
    true
}

impl Default for ProxyUiHostAutolearnConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            penalty_ttl_hours: default_host_autolearn_penalty_ttl_hours(),
            max_hosts: default_host_autolearn_max_hosts(),
            store_path: None,
            network_scope_key: None,
            warmup_probe_enabled: default_warmup_probe_enabled(),
            network_reprobe_enabled: default_network_reprobe_enabled(),
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
    pub adaptive_fallback: ProxyUiAdaptiveFallbackConfig,
    #[serde(default)]
    pub quic: ProxyUiQuicConfig,
    #[serde(default)]
    pub hosts: ProxyUiHostsConfig,
    #[serde(default)]
    pub upstream_relay: ProxyUiRelayConfig,
    #[serde(default)]
    pub warp: ProxyUiWarpConfig,
    #[serde(default)]
    pub host_autolearn: ProxyUiHostAutolearnConfig,
    #[serde(default)]
    pub ws_tunnel: ProxyUiWsTunnelConfig,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub native_log_level: Option<String>,
    #[serde(default)]
    pub root_mode: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub root_helper_socket_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWsTunnelConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub mode: Option<String>,
    #[serde(default)]
    pub fake_sni: Option<String>,
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
    /// True when the VPN service was configured in VPN mode but halted at snapshot capture
    /// time, meaning transport == "none" because the VPN tunnel went down rather than because
    /// the physical network is absent.
    #[serde(default)]
    pub vpn_service_was_active: bool,
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
    vec![
        ProxyUiTcpChainStep {
            kind: "tlsrec".to_string(),
            marker: "extlen".to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: default_ipv6_extension_profile(),
        },
        ProxyUiTcpChainStep {
            kind: "fake".to_string(),
            marker: "host+1".to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            overlap_size: 0,
            fake_mode: default_seqovl_fake_mode(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: default_ipv6_extension_profile(),
        },
    ]
}

fn default_seqovl_fake_mode() -> String {
    SEQOVL_FAKE_MODE_PROFILE.to_string()
}

fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

fn default_fake_tls_source() -> String {
    FAKE_TLS_SOURCE_PROFILE.to_string()
}

fn default_quic_initial_mode() -> String {
    "route_and_cache".to_string()
}

fn default_quic_fake_profile() -> String {
    QUIC_FAKE_PROFILE_DISABLED.to_string()
}

fn default_ipv6_extension_profile() -> String {
    "none".to_string()
}

fn default_warp_route_mode() -> String {
    WARP_ROUTE_MODE_OFF.to_string()
}

fn default_warp_endpoint_selection_mode() -> String {
    WARP_ENDPOINT_SELECTION_AUTOMATIC.to_string()
}

fn default_warp_manual_endpoint_port() -> i32 {
    2408
}

fn default_warp_scanner_parallelism() -> i32 {
    10
}

fn default_warp_scanner_max_rtt_ms() -> i32 {
    1500
}

fn default_warp_local_socks_host() -> String {
    "127.0.0.1".to_string()
}

fn default_warp_local_socks_port() -> i32 {
    11888
}

fn default_relay_kind() -> String {
    RELAY_KIND_OFF.to_string()
}

fn default_relay_server_port() -> i32 {
    443
}

fn default_relay_local_socks_host() -> String {
    "127.0.0.1".to_string()
}

fn default_relay_local_socks_port() -> i32 {
    11980
}

fn default_fake_payload_profile() -> String {
    FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string()
}

fn default_tls_fingerprint_profile() -> String {
    "chrome_stable".to_string()
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

fn default_adaptive_fallback_cache_ttl_secs() -> i64 {
    ADAPTIVE_FALLBACK_DEFAULT_CACHE_TTL_SECS
}

fn default_adaptive_fallback_cache_prefix_v4() -> u8 {
    ADAPTIVE_FALLBACK_DEFAULT_CACHE_PREFIX_V4
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
            vpn_service_was_active: false,
        };

        let json = serde_json::to_value(&snapshot).expect("serialize network snapshot");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("network_snapshot_fields.json", &manifest);
    }
}
