use std::io;
use std::net::{SocketAddr, TcpStream};

use super::super::super::state::RuntimeState;
use super::super::ws_tunnel::{should_ws_tunnel_fallback, WsTunnelResult};
use super::reply::SuccessReply;
use super::ConnectRelayError;

pub(super) fn run_ws_fallback_after_desync<WriteSuccessReply, RunWsTunnel, RunWsTunnelWithSeed>(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    reply: &SuccessReply,
    mut err: ConnectRelayError,
    write_success_reply_fn: &mut WriteSuccessReply,
    run_ws_tunnel_fn: &mut RunWsTunnel,
    run_ws_tunnel_with_seed_fn: &mut RunWsTunnelWithSeed,
) -> Result<(), ConnectRelayError>
where
    WriteSuccessReply: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
    RunWsTunnel: FnMut(TcpStream, &RuntimeState) -> WsTunnelResult,
    RunWsTunnelWithSeed: FnMut(TcpStream, Vec<u8>, &RuntimeState) -> WsTunnelResult,
{
    if should_ws_tunnel_fallback(target, state).is_none() {
        return Err(err);
    }

    let ws_result = if let Some(seed_request) = err.seed_request().map(ToOwned::to_owned) {
        tracing::info!("WS tunnel fallback: reusing preserved first request after desync exhaustion");
        if !err.success_reply_sent() {
            write_success_reply_fn(client, reply, None)
                .map_err(|write_err| ConnectRelayError::new(write_err, false))?;
            err.mark_success_reply_sent();
        }
        Some(run_ws_tunnel_with_seed_fn(
            client.try_clone().map_err(|clone_err| ConnectRelayError::new(clone_err, true))?,
            seed_request,
            state,
        ))
    } else if !err.success_reply_sent() {
        tracing::info!("WS tunnel fallback: reading MTProto seed after desync connect failure");
        write_success_reply_fn(client, reply, None).map_err(|write_err| ConnectRelayError::new(write_err, false))?;
        err.mark_success_reply_sent();
        Some(run_ws_tunnel_fn(client.try_clone().map_err(|clone_err| ConnectRelayError::new(clone_err, true))?, state))
    } else {
        None
    };

    let Some(result) = ws_result else {
        return Err(err);
    };

    if handle_ws_fallback_result(target, state, result) {
        Ok(())
    } else {
        Err(err)
    }
}

fn handle_ws_fallback_result(target: SocketAddr, state: &RuntimeState, result: WsTunnelResult) -> bool {
    match result {
        WsTunnelResult::ValidatedMtproto { dc } => {
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc.number(), true);
            }
            true
        }
        WsTunnelResult::NotMtproto { .. } => {
            tracing::debug!("WS tunnel fallback: preserved request is not MTProto");
            record_ws_tunnel_failure(target, state);
            false
        }
        WsTunnelResult::UnmappableDc { raw_dc, dc, .. } => {
            tracing::info!("WS tunnel fallback: decoded non-tunnelable MTProto DC raw={} normalized={:?}", raw_dc, dc);
            record_ws_tunnel_failure(target, state);
            false
        }
        WsTunnelResult::ShortInit { error, .. } => {
            tracing::debug!("WS tunnel fallback: short init while waiting for MTProto seed: {error}");
            record_ws_tunnel_failure(target, state);
            false
        }
        WsTunnelResult::BootstrapFailed { dc, error, .. } => {
            tracing::warn!(
                "WS tunnel fallback: bootstrap failed for raw DC {} (class {:?}): {error}",
                dc.raw(),
                dc.class()
            );
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc.number(), false);
            }
            false
        }
        WsTunnelResult::WsOpenOrRelayFailed { dc, error, .. } => {
            tracing::warn!(
                "WS tunnel fallback: relay failed for raw DC {} (class {:?}): {error}",
                dc.raw(),
                dc.class()
            );
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc.number(), false);
            }
            false
        }
    }
}

fn record_ws_tunnel_failure(target: SocketAddr, state: &RuntimeState) {
    if let Some(telemetry) = &state.telemetry {
        if let Some(dc) = super::super::ws_tunnel::detect_telegram_dc(target) {
            telemetry.on_ws_tunnel_escalation(target, dc, false);
        }
    }
}
