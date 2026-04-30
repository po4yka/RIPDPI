use std::io;
use std::net::{IpAddr, SocketAddr};

use ripdpi_runtime_policy::runtime_policy::{ConnectionRoute, TransportProtocol};

use super::super::adaptive::{
    capability_blocks_transport, direct_path_capability_for_targets, note_direct_path_udp_suppressed, now_millis,
};
use super::super::state::{flush_autolearn_updates, RuntimeState};

const MAX_PREFERRED_EDGE_TARGETS: usize = 2;

pub(in crate::runtime) fn select_route(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
) -> io::Result<ConnectionRoute> {
    select_route_for_transport(state, target, payload, host, allow_unknown_payload, TransportProtocol::Tcp)
}

pub(in crate::runtime) fn select_route_for_transport(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> io::Result<ConnectionRoute> {
    let mut cache = state.cache.write().map_err(|_| io::Error::other("cache lock poisoned"))?;
    cache
        .select_initial(target, payload, host, allow_unknown_payload, transport, &state.config)
        .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))
}

pub(in crate::runtime) fn preferred_targets_for_transport(
    state: &RuntimeState,
    original_target: SocketAddr,
    host: Option<&str>,
    transport: TransportProtocol,
) -> Vec<SocketAddr> {
    let mut targets = Vec::new();
    if let Some(host) = host.map(str::trim).filter(|host| !host.is_empty()) {
        if let Some(runtime_context) = state.runtime_context.as_ref() {
            let normalized = host.to_ascii_lowercase();
            if let Some(edges) =
                runtime_context.preferred_edges.get(host).or_else(|| runtime_context.preferred_edges.get(&normalized))
            {
                for edge in
                    edges.iter().filter(|edge| preferred_edge_matches_transport(edge.transport_kind.trim(), transport))
                {
                    let Ok(ip) = edge.ip.trim().parse::<IpAddr>() else {
                        continue;
                    };
                    let candidate = SocketAddr::new(ip, original_target.port());
                    if !targets.contains(&candidate) {
                        targets.push(candidate);
                    }
                    if targets.len() >= MAX_PREFERRED_EDGE_TARGETS {
                        break;
                    }
                }
            }
        }
    }
    if !targets.contains(&original_target) {
        targets.push(original_target);
    }
    if let Some(capability) = direct_path_capability_for_targets(state.runtime_context.as_ref(), host, &targets) {
        if capability_blocks_transport(capability, transport, now_millis()) {
            if transport == TransportProtocol::Udp && capability.reason_code.as_deref() != Some("NO_TCP_FALLBACK") {
                let _ = note_direct_path_udp_suppressed(state, host, &targets);
            }
            return Vec::new();
        }
    }
    targets
}

fn preferred_edge_matches_transport(transport_kind: &str, transport: TransportProtocol) -> bool {
    match transport {
        TransportProtocol::Tcp => {
            transport_kind.eq_ignore_ascii_case("tcp") || transport_kind.eq_ignore_ascii_case("throughput")
        }
        TransportProtocol::Udp => transport_kind.eq_ignore_ascii_case("quic"),
    }
}

pub(in crate::runtime) fn note_route_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
) -> io::Result<()> {
    note_route_success_for_transport(state, target, route, host, TransportProtocol::Tcp)
}

pub(in crate::runtime) fn note_route_success_for_transport(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
    transport: TransportProtocol,
) -> io::Result<()> {
    let mut cache = state.cache.write().map_err(|_| io::Error::other("cache lock poisoned"))?;
    cache.note_route_success_for_transport(&state.config, target, route, host, transport)?;
    flush_autolearn_updates(state, &mut cache);
    Ok(())
}

pub(in crate::runtime) fn runtime_supports_trigger(state: &RuntimeState, trigger: u32) -> io::Result<bool> {
    let cache = state.cache.read().map_err(|_| io::Error::other("cache lock poisoned"))?;
    Ok(cache.supports_trigger(trigger))
}
