use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::extract_payload_host;

use super::super::super::state::RuntimeState;
use super::ConnectRelayError;

pub(super) struct UpstreamRoute {
    pub(super) upstream: TcpStream,
    pub(super) route: ConnectionRoute,
    pub(super) seed_request: Option<Vec<u8>>,
}

pub(super) fn connect_immediate_route(
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
) -> Result<UpstreamRoute, ConnectRelayError> {
    let (upstream, route) = super::super::super::routing::connect_target(target, state, None, false, host_hint)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    Ok(UpstreamRoute { upstream, route, seed_request: None })
}

pub(super) fn connect_delayed_route(
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    route: ConnectionRoute,
    payload: Vec<u8>,
) -> Result<UpstreamRoute, ConnectRelayError> {
    let host = extract_payload_host(&state.config, &payload).or(host_hint);
    let (upstream, route) =
        super::super::super::routing::connect_target_with_route(target, state, route, Some(&payload), host)
            .map_err(|err| ConnectRelayError::with_seed_request(err, true, Some(payload.clone())))?;
    Ok(UpstreamRoute { upstream, route, seed_request: Some(payload) })
}

pub(super) fn connect_ws_seed_route(
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    seed_request: Vec<u8>,
) -> Result<UpstreamRoute, ConnectRelayError> {
    let seed_request = (!seed_request.is_empty()).then_some(seed_request);
    let (upstream, route) =
        super::super::super::routing::connect_target(target, state, seed_request.as_deref(), true, host_hint)
            .map_err(|err| ConnectRelayError::new(err, true))?;
    Ok(UpstreamRoute { upstream, route, seed_request })
}
