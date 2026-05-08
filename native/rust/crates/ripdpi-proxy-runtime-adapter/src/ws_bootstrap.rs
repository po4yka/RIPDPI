use std::net::{IpAddr, SocketAddr};

use crate::model::config::{ws_tunnel_always_enabled, ws_tunnel_fallback_enabled, RuntimeConfig, WsTunnelSettings};

pub use ripdpi_ws_bootstrap::*;

pub fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
    match target.ip() {
        IpAddr::V4(v4) => ripdpi_ws_bootstrap::dc_from_ip(v4).map(ripdpi_ws_bootstrap::TelegramDc::number),
        IpAddr::V6(_) => None,
    }
}

pub fn telegram_dc_host(dc: u8) -> String {
    format!("telegram-dc{dc}")
}

pub fn tunnel_target(target: SocketAddr) -> Option<ripdpi_ws_bootstrap::TelegramDc> {
    match ripdpi_ws_bootstrap::classify_target(target.ip()) {
        ripdpi_ws_bootstrap::WsTunnelDecision::Tunnel(dc) => Some(dc),
        ripdpi_ws_bootstrap::WsTunnelDecision::Passthrough => None,
    }
}

pub fn should_tunnel_first(target: SocketAddr, config: &RuntimeConfig) -> Option<ripdpi_ws_bootstrap::TelegramDc> {
    ws_tunnel_always_enabled(config).then(|| tunnel_target(target)).flatten()
}

pub fn should_tunnel_first_with(
    target: SocketAddr,
    settings: &WsTunnelSettings,
) -> Option<ripdpi_ws_bootstrap::TelegramDc> {
    settings.always_enabled.then(|| tunnel_target(target)).flatten()
}

pub fn should_tunnel_fallback(target: SocketAddr, config: &RuntimeConfig) -> Option<ripdpi_ws_bootstrap::TelegramDc> {
    ws_tunnel_fallback_enabled(config).then(|| tunnel_target(target)).flatten()
}

pub fn should_tunnel_fallback_with(
    target: SocketAddr,
    settings: &WsTunnelSettings,
) -> Option<ripdpi_ws_bootstrap::TelegramDc> {
    settings.fallback_enabled.then(|| tunnel_target(target)).flatten()
}
