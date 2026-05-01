use std::net::{SocketAddr, TcpStream};

use ripdpi_runtime_policy::runtime_policy::extract_host;

use super::super::super::state::RuntimeState;
use super::reply::{write_success_reply, SuccessReply};
use super::ConnectRelayError;

pub(super) fn immediate_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: &SuccessReply,
) -> Result<(), ConnectRelayError> {
    let (upstream, route) = super::super::super::routing::connect_target(target, state, None, false, host_hint)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    write_success_reply(client, reply, Some(&upstream)).map_err(|err| ConnectRelayError::new(err, false))?;
    super::super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, reply.requires_client_ack()))?,
        upstream,
        state,
        target,
        route,
        None,
    )
    .map_err(|err| ConnectRelayError::new(err, reply.requires_client_ack()))
}

pub(super) fn delayed_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    route: ripdpi_runtime_policy::runtime_policy::ConnectionRoute,
    payload: Vec<u8>,
) -> Result<(), ConnectRelayError> {
    let host = extract_host(&state.config, &payload).or(host_hint);
    let (upstream, route) =
        super::super::super::routing::connect_target_with_route(target, state, route, Some(&payload), host)
            .map_err(|err| ConnectRelayError::with_seed_request(err, true, Some(payload.clone())))?;
    super::super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?,
        upstream,
        state,
        target,
        route,
        Some(payload.clone()),
    )
    .map_err(|err| ConnectRelayError::with_seed_request(err, true, Some(payload)))
}

pub(super) fn connect_after_ws_attempt(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    seed_request: Vec<u8>,
) -> Result<(), ConnectRelayError> {
    let seed_request = (!seed_request.is_empty()).then_some(seed_request);
    let (upstream, route) =
        super::super::super::routing::connect_target(target, state, seed_request.as_deref(), true, host_hint)
            .map_err(|err| ConnectRelayError::new(err, true))?;
    super::super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?,
        upstream,
        state,
        target,
        route,
        seed_request,
    )
    .map_err(|err| ConnectRelayError::new(err, true))
}
