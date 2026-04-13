pub mod presets;

mod convert;
mod types;

#[cfg(test)]
mod tests;

// Public API re-exports -- types
pub use types::{
    CellularSnapshot, NetworkSnapshot, ProxyConfigError, ProxyConfigPayload, ProxyDirectPathCapability,
    ProxyEncryptedDnsContext, ProxyLogContext, ProxyMorphPolicy, ProxyPreferredEdge, ProxyRuntimeContext,
    ProxyUiActivationFilter, ProxyUiChainConfig, ProxyUiConfig, ProxyUiFakePacketConfig, ProxyUiHostAutolearnConfig,
    ProxyUiHostsConfig, ProxyUiListenConfig, ProxyUiNumericRange, ProxyUiParserEvasionConfig, ProxyUiProtocolConfig,
    ProxyUiQuicConfig, ProxyUiTcpChainStep, ProxyUiTcpRotationCandidate, ProxyUiTcpRotationConfig, ProxyUiUdpChainStep,
    RuntimeConfigEnvelope, WifiSnapshot, ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
    ADAPTIVE_FAKE_TTL_DEFAULT_MAX, ADAPTIVE_FAKE_TTL_DEFAULT_MIN, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT,
    FAKE_TLS_SNI_MODE_FIXED, FAKE_TLS_SNI_MODE_RANDOMIZED, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS,
    QUIC_FAKE_PROFILE_DISABLED, SEQOVL_DEFAULT_OVERLAP_SIZE, SEQOVL_FAKE_MODE_PROFILE, SEQOVL_FAKE_MODE_RAND,
};

// Public API re-exports -- conversion functions
pub use convert::{
    normalize_fake_tls_sni_mode, parse_desync_mode, parse_http_fake_profile, parse_proxy_config_json,
    parse_quic_fake_profile, parse_quic_initial_mode, parse_tcp_chain_step_kind, parse_tls_fake_profile,
    parse_udp_chain_step_kind, parse_udp_fake_profile, runtime_config_envelope_from_payload,
    runtime_config_from_command_line, runtime_config_from_payload, runtime_config_from_ui,
};

// Internal re-export for test access
#[allow(unused_imports)]
pub(crate) use types::HOSTS_DISABLE;
