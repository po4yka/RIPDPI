use std::net::{SocketAddr, TcpStream};

use super::super::super::state::RuntimeState;
use super::ConnectRelayError;
use super::reply::{SuccessReply, write_success_reply};
use super::routes::{UpstreamRoute, connect_delayed_route, connect_immediate_route, connect_ws_seed_route};
use crate::runtime::types::RuntimeConnectionRoute;

pub(super) fn immediate_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: &SuccessReply,
) -> Result<(), ConnectRelayError> {
    let upstream_route = connect_immediate_route(target, state, host_hint)?;
    write_success_reply(client, reply, Some(&upstream_route.upstream))
        .map_err(|err| ConnectRelayError::new(err, false))?;
    relay_upstream(client, state, target, upstream_route, reply.requires_client_ack())
}

pub(super) fn delayed_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    route: RuntimeConnectionRoute,
    payload: Vec<u8>,
) -> Result<(), ConnectRelayError> {
    let upstream_route = connect_delayed_route(target, state, host_hint, route, payload)?;
    let seed_request = upstream_route.seed_request.clone();
    relay_upstream(client, state, target, upstream_route, true)
        .map_err(|err| ConnectRelayError::with_seed_request(err.into_io_error(), true, seed_request))
}

pub(super) fn connect_after_ws_attempt(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    seed_request: Vec<u8>,
) -> Result<(), ConnectRelayError> {
    let upstream_route = connect_ws_seed_route(target, state, host_hint, seed_request)?;
    relay_upstream(client, state, target, upstream_route, true)
}

fn relay_upstream(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    upstream_route: UpstreamRoute,
    success_reply_sent: bool,
) -> Result<(), ConnectRelayError> {
    super::super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, success_reply_sent))?,
        upstream_route.upstream,
        state,
        target,
        upstream_route.route,
        upstream_route.seed_request,
    )
    .map_err(|err| ConnectRelayError::new(err, success_reply_sent))
}
