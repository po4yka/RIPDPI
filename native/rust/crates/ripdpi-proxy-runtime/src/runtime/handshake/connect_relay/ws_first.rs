use std::io;
use std::net::{SocketAddr, TcpStream};

use super::super::super::state::RuntimeState;
use super::super::ws_tunnel::{should_ws_tunnel_first, WsTunnelResult};
use super::reply::SuccessReply;
use super::ConnectRelayError;

pub(super) enum AlwaysWsOutcome {
    Handled,
    FallbackToDesync { seed_request: Vec<u8> },
    Failed(ConnectRelayError),
}

pub(super) fn run_ws_always_first<WriteSuccessReply, RunWsTunnel>(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    reply: &SuccessReply,
    write_success_reply_fn: &mut WriteSuccessReply,
    run_ws_tunnel_fn: &mut RunWsTunnel,
) -> Result<Option<AlwaysWsOutcome>, ConnectRelayError>
where
    WriteSuccessReply: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
    RunWsTunnel: FnMut(TcpStream, &RuntimeState) -> WsTunnelResult,
{
    if should_ws_tunnel_first(target, state).is_none() {
        return Ok(None);
    }

    write_success_reply_fn(client, reply, None).map_err(|err| ConnectRelayError::new(err, false))?;
    let result = run_ws_tunnel_fn(client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?, state);
    Ok(Some(always_ws_outcome(result)))
}

fn always_ws_outcome(result: WsTunnelResult) -> AlwaysWsOutcome {
    match result {
        WsTunnelResult::ValidatedMtproto { .. } => AlwaysWsOutcome::Handled,
        WsTunnelResult::NotMtproto { seed_request } => {
            tracing::debug!("WS tunnel Always mode: first request is not MTProto, falling back to desync");
            AlwaysWsOutcome::FallbackToDesync { seed_request }
        }
        WsTunnelResult::UnmappableDc { raw_dc, dc, seed_request } => {
            tracing::info!(
                "WS tunnel Always mode: decoded non-tunnelable MTProto DC raw={} normalized={:?}, falling back",
                raw_dc,
                dc
            );
            AlwaysWsOutcome::FallbackToDesync { seed_request }
        }
        WsTunnelResult::ShortInit { seed_request, error } => {
            tracing::debug!("WS tunnel Always mode: short init while sniffing MTProto: {error}");
            AlwaysWsOutcome::FallbackToDesync { seed_request }
        }
        WsTunnelResult::BootstrapFailed { dc, seed_request, error } => {
            tracing::warn!(
                "WS tunnel Always mode: bootstrap failed for validated MTProto raw DC {} (class {:?}), failing closed: {error}",
                dc.raw(),
                dc.class()
            );
            AlwaysWsOutcome::Failed(ConnectRelayError::with_seed_request(error, true, Some(seed_request)))
        }
        WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request, error } => {
            tracing::warn!(
                "WS tunnel Always mode: relay failed for validated MTProto raw DC {} (class {:?}), failing closed: {error}",
                dc.raw(),
                dc.class()
            );
            AlwaysWsOutcome::Failed(ConnectRelayError::with_seed_request(error, true, Some(seed_request)))
        }
    }
}
