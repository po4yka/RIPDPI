use std::io;
use std::net::{SocketAddr, TcpStream};

mod delay;
mod error;
mod relay;
mod reply;
#[cfg(test)]
mod tests;
mod ws_fallback;

use super::super::state::RuntimeState;
use super::protocol_io::HandshakeKind;
use super::ws_tunnel::{run_ws_tunnel, run_ws_tunnel_with_seed, WsTunnelResult};
use delay::{maybe_delay_connect, DelayConnect};
use relay::{connect_after_ws_attempt, delayed_connect_relay, immediate_connect_relay};
use reply::write_success_reply;
use ws_fallback::{run_ws_always_first, run_ws_fallback_after_desync, AlwaysWsOutcome};

pub(super) use error::ConnectRelayError;
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
    reply: SuccessReply,
) -> Result<(), ConnectRelayError> {
    connect_and_relay_with(
        client,
        target,
        state,
        host_hint,
        reply,
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
        HandshakeKind,
    ) -> Result<DelayConnect, ConnectRelayError>,
    ImmediateConnectRelay: FnMut(
        &mut TcpStream,
        SocketAddr,
        &RuntimeState,
        Option<String>,
        &SuccessReply,
    ) -> Result<(), ConnectRelayError>,
    DelayedConnectRelay: FnMut(
        &mut TcpStream,
        SocketAddr,
        &RuntimeState,
        Option<String>,
        ripdpi_runtime_policy::runtime_policy::ConnectionRoute,
        Vec<u8>,
    ) -> Result<(), ConnectRelayError>,
    ConnectAfterWsAttempt:
        FnMut(&mut TcpStream, SocketAddr, &RuntimeState, Option<String>, Vec<u8>) -> Result<(), ConnectRelayError>,
{
    if let Some(outcome) =
        run_ws_always_first(client, target, state, &reply, &mut write_success_reply_fn, &mut run_ws_tunnel_fn)?
    {
        return match outcome {
            AlwaysWsOutcome::Handled => Ok(()),
            AlwaysWsOutcome::FallbackToDesync { seed_request } => {
                connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request)
            }
        };
    }

    let desync_result = match reply.handshake_kind() {
        Some(kind) => match maybe_delay_connect_fn(client, state, target, host_hint.as_deref(), kind)? {
            DelayConnect::Immediate => immediate_connect_relay_fn(client, target, state, host_hint, &reply),
            DelayConnect::Delayed { route, payload } => {
                delayed_connect_relay_fn(client, target, state, host_hint, route, payload)
            }
            DelayConnect::Closed => Ok(()),
        },
        None => immediate_connect_relay_fn(client, target, state, host_hint, &reply),
    };

    match desync_result {
        Ok(()) => Ok(()),
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
