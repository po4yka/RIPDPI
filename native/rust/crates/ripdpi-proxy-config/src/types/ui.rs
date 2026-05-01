use serde::{Deserialize, Serialize};

use super::adaptive_fallback::ProxyUiAdaptiveFallbackConfig;
use super::autolearn::ProxyUiHostAutolearnConfig;
use super::chains::ProxyUiChainConfig;
use super::fake_packets::ProxyUiFakePacketConfig;
use super::hosts::ProxyUiHostsConfig;
use super::listen::ProxyUiListenConfig;
use super::parser::ProxyUiParserEvasionConfig;
use super::protocol::ProxyUiProtocolConfig;
use super::quic::ProxyUiQuicConfig;
use super::relay::ProxyUiRelayConfig;
use super::warp::ProxyUiWarpConfig;

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
    /// Coarse environment classification supplied by the platform-side
    /// `EnvironmentDetector`. Wire form is one of
    /// `"Unknown"` / `"Field"` / `"Emulator"`; an absent or unrecognised
    /// value falls back to `EnvironmentKind::Unknown` so the bandit's
    /// per-context HashMap keys stay stable.
    #[serde(default)]
    pub environment_kind: Option<String>,
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
