use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_proxy_runtime_adapter::model::config::{ipv6_enabled, name_resolution_enabled, protect_path};
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
    let ipv6_enabled = ipv6_enabled(&state.config);
    if let Some(loopback) = resolve_localhost(host, ipv6_enabled) {
        return Some(loopback);
    }
    if !name_resolution_enabled(&state.config) {
        return None;
    }

    ripdpi_proxy_runtime_adapter::ws_bootstrap::resolve_host_via_encrypted_dns(
        host,
        state.runtime_context.as_ref(),
        protect_path(&state.config),
        ipv6_enabled,
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
