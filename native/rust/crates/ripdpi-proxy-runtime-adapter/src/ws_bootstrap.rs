use std::net::SocketAddr;

use crate::model::config::{RuntimeConfig, WsTunnelSettings, ws_tunnel_always_enabled, ws_tunnel_fallback_enabled};

pub use ripdpi_ws_bootstrap::*;

pub fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
    tunnel_target(target).map(ripdpi_ws_bootstrap::TelegramDc::number)
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

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use crate::model::config::WsTunnelSettings;

    use super::*;

    #[test]
    fn detect_telegram_dc_uses_same_ipv4_and_ipv6_classifier_as_tunnel_target() {
        let targets = [
            (SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443)), Some(2)),
            (SocketAddr::new("2001:67c:4e8:0:1::1".parse::<IpAddr>().expect("parse amsterdam v6"), 443), Some(2)),
            (SocketAddr::new("2001:b28:f23f::1".parse::<IpAddr>().expect("parse singapore v6"), 443), Some(3)),
            (SocketAddr::new("2001:4860:4860::8888".parse::<IpAddr>().expect("parse unrelated v6"), 443), None),
        ];

        for (target, expected_dc) in targets {
            assert_eq!(detect_telegram_dc(target), expected_dc, "wrong DC for {target}");
            assert_eq!(tunnel_target(target).map(ripdpi_ws_bootstrap::TelegramDc::number), expected_dc);
        }
    }

    #[test]
    fn ws_tunnel_mode_checks_accept_known_telegram_ipv6_targets() {
        let target = SocketAddr::new("2001:67c:4e8::1".parse::<IpAddr>().expect("parse v6"), 443);
        let always = WsTunnelSettings {
            always_enabled: true,
            fallback_enabled: false,
            protect_path: None,
            connect_timeout: None,
            fake_sni: None,
            allow_insecure_sni: false,
            worker_route: None,
        };
        let fallback = WsTunnelSettings {
            always_enabled: false,
            fallback_enabled: true,
            protect_path: None,
            connect_timeout: None,
            fake_sni: None,
            allow_insecure_sni: false,
            worker_route: None,
        };

        assert_eq!(should_tunnel_first_with(target, &always).map(ripdpi_ws_bootstrap::TelegramDc::number), Some(2));
        assert_eq!(should_tunnel_fallback_with(target, &always), None);
        assert_eq!(
            should_tunnel_fallback_with(target, &fallback).map(ripdpi_ws_bootstrap::TelegramDc::number),
            Some(2)
        );
        assert_eq!(should_tunnel_first_with(target, &fallback), None);
    }
}
