use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::decision::{ConnectionRoute, TransportProtocol};

use super::super::adaptive::now_millis;
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
        .select_initial_route(target, payload, host, allow_unknown_payload, transport)
        .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))
}

pub(in crate::runtime) fn preferred_targets_for_transport(
    state: &RuntimeState,
    original_target: SocketAddr,
    host: Option<&str>,
    transport: TransportProtocol,
) -> Vec<SocketAddr> {
    state.preferred_targets_for_transport(original_target, host, transport, now_millis())
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
    state.note_route_success(target, route, host, transport)
}
