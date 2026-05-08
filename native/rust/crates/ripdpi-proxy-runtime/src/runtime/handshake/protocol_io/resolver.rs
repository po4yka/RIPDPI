use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_proxy_runtime_adapter::model::session::SocketType;

use super::super::super::state::RuntimeState;

pub(in crate::runtime) fn resolve_name(
    host: &str,
    _socket_type: SocketType,
    state: &RuntimeState,
) -> Option<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, 0));
    }
    let handshake_settings = state.handshake_settings();
    let ipv6_enabled = handshake_settings.session_config.ipv6;
    if let Some(loopback) = resolve_localhost(host, ipv6_enabled) {
        return Some(loopback);
    }
    if !handshake_settings.session_config.resolve {
        return None;
    }

    state.resolve_encrypted_dns_host(host, handshake_settings.protect_path.as_deref(), ipv6_enabled).ok()
}

fn resolve_localhost(host: &str, ipv6_enabled: bool) -> Option<SocketAddr> {
    if !host.eq_ignore_ascii_case("localhost") && !host.eq_ignore_ascii_case("localhost.") {
        return None;
    }

    let ip = if ipv6_enabled { IpAddr::V6(Ipv6Addr::LOCALHOST) } else { IpAddr::V4(Ipv4Addr::LOCALHOST) };
    Some(SocketAddr::new(ip, 0))
}
