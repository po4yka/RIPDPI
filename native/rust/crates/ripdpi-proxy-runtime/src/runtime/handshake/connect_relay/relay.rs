use std::net::{SocketAddr, TcpStream};

use super::super::super::state::RuntimeState;
use super::ConnectRelayError;
use super::reply::{SuccessReply, write_success_reply};
use super::routes::{UpstreamRoute, connect_delayed_route, connect_immediate_route, connect_ws_seed_route};
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

pub(super) fn immediate_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: &SuccessReply,
    success_reply_sent: bool,
    attempt_token: Option<AttemptCorrelationId>,
) -> Result<(), ConnectRelayError> {
    let upstream_route = connect_immediate_route(target, state, host_hint)
        .map_err(|err| preserve_success_reply_state(err, success_reply_sent))?;
    if !success_reply_sent {
        write_success_reply(client, reply, Some(&upstream_route.upstream))
            .map_err(|err| ConnectRelayError::new(err, false))?;
    }
    relay_upstream(client, state, target, upstream_route, reply.requires_client_ack(), attempt_token)
}

fn preserve_success_reply_state(mut err: ConnectRelayError, success_reply_sent: bool) -> ConnectRelayError {
    if success_reply_sent {
        err.mark_success_reply_sent();
    }
    err
}

pub(super) fn delayed_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    route: RuntimeConnectionRoute,
    payload: Vec<u8>,
    attempt_token: Option<AttemptCorrelationId>,
) -> Result<(), ConnectRelayError> {
    let upstream_route = connect_delayed_route(target, state, host_hint, route, payload)?;
    let seed_request = upstream_route.seed_request.clone();
    relay_upstream(client, state, target, upstream_route, true, attempt_token)
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
    relay_upstream(client, state, target, upstream_route, true, None)
}

fn relay_upstream(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    upstream_route: UpstreamRoute,
    success_reply_sent: bool,
    attempt_token: Option<AttemptCorrelationId>,
) -> Result<(), ConnectRelayError> {
    // Hold the per-exit-IP session slot for the entire relay so the cap counts
    // this connection as in-flight; it is freed when `_cap_guard` drops at the
    // end of this function, after `relay()` returns.
    let _cap_guard = upstream_route.cap_guard;
    let _same_sni_guard = upstream_route.same_sni_guard;
    super::super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, success_reply_sent))?,
        upstream_route.upstream,
        state,
        target,
        upstream_route.route,
        upstream_route.seed_request,
        attempt_token,
    )
    .map_err(|err| ConnectRelayError::new(err, success_reply_sent))
}

#[cfg(test)]
mod tests {
    use std::io;

    use super::{ConnectRelayError, preserve_success_reply_state};

    #[test]
    fn pre_sent_success_is_preserved_on_immediate_route_failure() {
        let err =
            preserve_success_reply_state(ConnectRelayError::new(io::Error::other("upstream unavailable"), false), true);

        assert!(err.success_reply_sent());
    }
}
