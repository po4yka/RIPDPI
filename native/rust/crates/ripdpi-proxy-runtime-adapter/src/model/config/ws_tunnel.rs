use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_config::{RuntimeConfig, WsTunnelMode};

use super::{connect_timeout, protect_path_owned};

pub fn ws_tunnel_always_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.ws_tunnel_mode == WsTunnelMode::Always
}

pub fn ws_tunnel_fallback_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.ws_tunnel_mode == WsTunnelMode::Fallback
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WsTunnelSettings {
    pub always_enabled: bool,
    pub fallback_enabled: bool,
    pub protect_path: Option<String>,
    pub connect_timeout: Option<Duration>,
    pub fake_sni: Option<String>,
}

pub fn ws_tunnel_settings(config: &RuntimeConfig) -> WsTunnelSettings {
    WsTunnelSettings {
        always_enabled: ws_tunnel_always_enabled(config),
        fallback_enabled: ws_tunnel_fallback_enabled(config),
        protect_path: protect_path_owned(config),
        connect_timeout: connect_timeout(config),
        fake_sni: config.adaptive.ws_tunnel_fake_sni.clone(),
    }
}

pub fn ws_tunnel_config(
    config: &RuntimeConfig,
    resolved_addr: Option<SocketAddr>,
) -> ripdpi_ws_bootstrap::WsTunnelConfig {
    ws_tunnel_config_with(&ws_tunnel_settings(config), resolved_addr)
}

pub fn ws_tunnel_config_with(
    settings: &WsTunnelSettings,
    resolved_addr: Option<SocketAddr>,
) -> ripdpi_ws_bootstrap::WsTunnelConfig {
    ripdpi_ws_bootstrap::WsTunnelConfig {
        protect_path: settings.protect_path.clone(),
        resolved_addr,
        connect_timeout: settings.connect_timeout,
        fake_sni: settings.fake_sni.clone(),
        // Safe-by-default: fake-SNI cert-bypass is only honoured when the
        // operator opts in. Plumbing this from RuntimeConfig (via a new
        // WsTunnelSettings.allow_insecure_sni field) is tracked under
        // docs/tasks/issues/gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry.md.
        // Until then, fake_sni values in catalog profiles will be refused
        // by ripdpi_ws_bootstrap with PermissionDenied rather than silently
        // bypassing TLS verification.
        allow_insecure_sni: false,
    }
}
