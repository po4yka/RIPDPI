use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_proxy_runtime_adapter::session::SocketType;

use super::super::super::state::RuntimeState;

pub(in crate::runtime) fn resolve_name(
    host: &str,
    _socket_type: SocketType,
    state: &RuntimeState,
) -> Option<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, 0));
    }
    if let Some(loopback) = resolve_localhost(host, state.config.network.ipv6) {
        return Some(loopback);
    }
    if !state.config.network.resolve {
        return None;
    }

    ripdpi_proxy_runtime_adapter::ws_bootstrap::resolve_host_via_encrypted_dns(
        host,
        state.runtime_context.as_ref(),
        state.config.process.protect_path.as_deref(),
        state.config.network.ipv6,
    )
    .ok()
}

fn resolve_localhost(host: &str, ipv6_enabled: bool) -> Option<SocketAddr> {
    if !host.eq_ignore_ascii_case("localhost") && !host.eq_ignore_ascii_case("localhost.") {
        return None;
    }

    let ip = if ipv6_enabled { IpAddr::V6(Ipv6Addr::LOCALHOST) } else { IpAddr::V4(Ipv4Addr::LOCALHOST) };
    Some(SocketAddr::new(ip, 0))
}
