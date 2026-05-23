mod adaptive_fallback;
mod autolearn;
mod chains;
mod common;
mod constants;
mod fake_packets;
mod hosts;
mod listen;
mod network;
mod parser;
mod payload;
mod protocol;
mod quic;
mod relay;
mod runtime_context;
mod ui;
mod warp;

pub use adaptive_fallback::ProxyUiAdaptiveFallbackConfig;
pub use autolearn::ProxyUiHostAutolearnConfig;
pub use chains::{
    ProxyUiChainConfig, ProxyUiTcpChainStep, ProxyUiTcpRotationCandidate, ProxyUiTcpRotationConfig, ProxyUiUdpChainStep,
};
pub use common::{ProxyUiActivationFilter, ProxyUiNumericRange};
pub use constants::{
    ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
    ADAPTIVE_FAKE_TTL_DEFAULT_MIN, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SNI_MODE_RANDOMIZED, FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO, FAKE_TLS_SOURCE_PROFILE, IP_ID_MODE_RND,
    IP_ID_MODE_SEQ, IP_ID_MODE_SEQGROUP, IP_ID_MODE_ZERO, QUIC_FAKE_PROFILE_DISABLED, SEQOVL_DEFAULT_OVERLAP_SIZE,
    SEQOVL_FAKE_MODE_PROFILE, SEQOVL_FAKE_MODE_RAND,
};
// `HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS` is consumed via `super::constants`
// directly (see `autolearn.rs`); it is not re-exported at the `types` root.
pub(crate) use constants::{
    HOSTS_BLACKLIST, HOSTS_DISABLE, HOSTS_WHITELIST, RELAY_KIND_OFF, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT,
    TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE, WARP_ROUTE_MODE_RULES,
};
pub use fake_packets::ProxyUiFakePacketConfig;
pub use hosts::ProxyUiHostsConfig;
pub use listen::ProxyUiListenConfig;
pub use network::{CellularSnapshot, NetworkSnapshot, WifiSnapshot};
pub use parser::ProxyUiParserEvasionConfig;
pub use payload::{ProxyConfigError, ProxyConfigPayload, ProxySessionOverrides, RuntimeConfigEnvelope};
// Schema-version envelope validation -- consumed by the `convert` entry points.
pub(crate) use payload::validate_schema_version;
pub use protocol::ProxyUiProtocolConfig;
pub use quic::ProxyUiQuicConfig;
pub use relay::ProxyUiRelayConfig;
pub use runtime_context::{
    ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyLogContext, ProxyMorphPolicy, ProxyPreferredEdge,
    ProxyRuntimeContext,
};
pub use ui::{ProxyUiConfig, ProxyUiWsTunnelConfig};
pub use warp::ProxyUiWarpConfig;
