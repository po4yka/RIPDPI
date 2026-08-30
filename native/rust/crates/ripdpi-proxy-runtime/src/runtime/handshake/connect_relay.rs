use std::io;
use std::net::{SocketAddr, TcpStream};

mod delay;
mod error;
mod relay;
mod reply;
mod routes;
#[cfg(all(test, not(feature = "loom")))]
mod tests;
mod ws_fallback;
mod ws_first;

use super::super::state::RuntimeState;
use super::protocol_io::HandshakeKind;
use super::ws_tunnel::{WsTunnelResult, run_ws_tunnel, run_ws_tunnel_with_seed};
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::types::RuntimeConnectionRoute;
use crate::runtime::types::RuntimeTransportProtocol;
use delay::{DelayConnect, maybe_delay_connect};
use relay::{connect_after_ws_attempt, delayed_connect_relay, immediate_connect_relay};
use reply::write_success_reply;
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;
use ws_fallback::run_ws_fallback_after_desync;
use ws_first::{AlwaysWsOutcome, run_ws_always_first};

pub(super) use error::{ConnectPolicyRejection, ConnectRelayError};
pub(super) use reply::SuccessReply;

/// Common connect-relay-WS fallback flow used by all protocol handlers except shadowsocks.
///
/// Handles:
/// 1. WS tunnel Always mode attempt
/// 2. Delay connect (read first request before connecting)
/// 3. Route selection + upstream relay
/// 4. WS tunnel Fallback mode on desync failure
///
/// Returns the raw error on failure; callers handle protocol-specific error policy.
pub(super) fn connect_and_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    attempt_token: Option<AttemptCorrelationId>,
    reply: SuccessReply,
) -> Result<(), ConnectRelayError> {
    connect_and_relay_with(
        client,
        target,
        state,
        host_hint,
        reply,
        attempt_token,
        write_success_reply,
        run_ws_tunnel,
        run_ws_tunnel_with_seed,
        maybe_delay_connect,
        immediate_connect_relay,
        delayed_connect_relay,
        connect_after_ws_attempt,
    )
}

#[allow(clippy::too_many_arguments)]
fn connect_and_relay_with<
    WriteSuccessReply,
    RunWsTunnel,
    RunWsTunnelWithSeed,
    MaybeDelayConnect,
    ImmediateConnectRelay,
    DelayedConnectRelay,
    ConnectAfterWsAttempt,
>(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: SuccessReply,
    attempt_token: Option<AttemptCorrelationId>,
    mut write_success_reply_fn: WriteSuccessReply,
    mut run_ws_tunnel_fn: RunWsTunnel,
    mut run_ws_tunnel_with_seed_fn: RunWsTunnelWithSeed,
    mut maybe_delay_connect_fn: MaybeDelayConnect,
    mut immediate_connect_relay_fn: ImmediateConnectRelay,
    mut delayed_connect_relay_fn: DelayedConnectRelay,
    mut connect_after_ws_attempt_fn: ConnectAfterWsAttempt,
) -> Result<(), ConnectRelayError>
where
    WriteSuccessReply: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
    RunWsTunnel: FnMut(TcpStream, &RuntimeState) -> WsTunnelResult,
    RunWsTunnelWithSeed: FnMut(TcpStream, Vec<u8>, &RuntimeState) -> WsTunnelResult,
    MaybeDelayConnect: FnMut(
        &mut TcpStream,
        &RuntimeState,
        SocketAddr,
        Option<&str>,
        Option<HandshakeKind>,
    ) -> Result<DelayConnect, ConnectRelayError>,
    ImmediateConnectRelay: FnMut(
        &mut TcpStream,
        SocketAddr,
        &RuntimeState,
        Option<String>,
        &SuccessReply,
        bool,
        Option<AttemptCorrelationId>,
    ) -> Result<(), ConnectRelayError>,
    DelayedConnectRelay: FnMut(
        &mut TcpStream,
        SocketAddr,
        &RuntimeState,
        Option<String>,
        RuntimeConnectionRoute,
        Vec<u8>,
        Option<AttemptCorrelationId>,
    ) -> Result<(), ConnectRelayError>,
    ConnectAfterWsAttempt:
        FnMut(&mut TcpStream, SocketAddr, &RuntimeState, Option<String>, Vec<u8>) -> Result<(), ConnectRelayError>,
{
    let destination_egress = state.destination_egress(target, host_hint.as_deref(), RuntimeTransportProtocol::Tcp);
    if destination_egress == DestinationEgress::Block {
        return Err(ConnectRelayError::new(
            io::Error::new(io::ErrorKind::PermissionDenied, "destination blocked by routing policy"),
            false,
        ));
    }
    if state.owned_stack_required_for_transparent_target(
        target,
        host_hint.as_deref(),
        super::super::adaptive::now_millis(),
    ) {
        return Err(ConnectRelayError::owned_stack_required());
    }
    let defer_ws_for_destination = destination_egress == DestinationEgress::Direct
        || (host_hint.is_none() && state.destination_policy_may_need_host());

    if !defer_ws_for_destination
        && let Some(outcome) =
            run_ws_always_first(client, target, state, &reply, &mut write_success_reply_fn, &mut run_ws_tunnel_fn)?
    {
        return match outcome {
            AlwaysWsOutcome::Handled => Ok(()),
            AlwaysWsOutcome::FallbackToDesync { seed_request } => {
                connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request)
            }
            AlwaysWsOutcome::Failed(err) => Err(err),
        };
    }

    let desync_result =
        match maybe_delay_connect_fn(client, state, target, host_hint.as_deref(), reply.handshake_kind())? {
            DelayConnect::Immediate { success_reply_sent } => {
                immediate_connect_relay_fn(client, target, state, host_hint, &reply, success_reply_sent, attempt_token)
            }
            DelayConnect::Delayed { route, payload } => {
                delayed_connect_relay_fn(client, target, state, host_hint, route, payload, attempt_token)
            }
            DelayConnect::Closed => Ok(()),
        };

    match desync_result {
        Ok(()) => Ok(()),
        Err(err) if defer_ws_for_destination => Err(err),
        Err(err) => run_ws_fallback_after_desync(
            client,
            target,
            state,
            &reply,
            err,
            &mut write_success_reply_fn,
            &mut run_ws_tunnel_fn,
            &mut run_ws_tunnel_with_seed_fn,
        ),
    }
}
