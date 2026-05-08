use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::decision::{ConnectionRoute, TransportProtocol};

use super::super::adaptive::{note_direct_path_udp_suppressed, now_millis};
use super::super::state::RuntimeState;

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
    state
        .policy()
        .select_initial(target, payload, host, allow_unknown_payload, transport)
        .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))
}

pub(in crate::runtime) fn preferred_targets_for_transport(
    state: &RuntimeState,
    original_target: SocketAddr,
    host: Option<&str>,
    transport: TransportProtocol,
) -> Vec<SocketAddr> {
    let decision = state.adaptive_context().preferred_targets(
        state.runtime_context.as_ref(),
        original_target,
        host,
        transport,
        now_millis(),
    );
    if decision.suppressed_udp {
        let _ = note_direct_path_udp_suppressed(state, host, &decision.suppressed_targets);
    }
    decision.targets
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
    state.policy().note_success(target, route, host, transport)
}

pub(in crate::runtime) fn runtime_supports_trigger(state: &RuntimeState, trigger: u32) -> io::Result<bool> {
    Ok(state.policy().supports_trigger(trigger))
}
