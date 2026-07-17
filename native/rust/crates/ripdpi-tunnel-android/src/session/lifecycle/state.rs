use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use super::super::registry::TunnelSessionState;

pub(crate) fn ensure_tunnel_start_allowed(state: &TunnelSessionState) -> Result<(), &'static str> {
    match state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting { .. } | TunnelSessionState::CleanupPending { .. } => {
            Err("Tunnel session is already starting")
        }
        TunnelSessionState::Running { .. } => Err("Tunnel session is already running"),
        TunnelSessionState::Destroyed => Err("Tunnel session has been destroyed"),
    }
}

pub(crate) fn take_running_tunnel(
    state: &mut TunnelSessionState,
) -> Result<(Arc<CancellationToken>, std::thread::JoinHandle<()>), &'static str> {
    match state {
        TunnelSessionState::Running { .. } => {
            let TunnelSessionState::Running { cancel, worker, .. } =
                std::mem::replace(state, TunnelSessionState::Ready)
            else {
                unreachable!("just matched Running");
            };
            Ok((cancel, worker))
        }
        TunnelSessionState::Starting { cancel } => {
            cancel.cancel();
            Err("Tunnel session is still starting; cancellation requested")
        }
        TunnelSessionState::CleanupPending { cancel } => {
            cancel.cancel();
            Err("Tunnel session is still starting; cancellation requested")
        }
        TunnelSessionState::Ready => Err("Tunnel session is not running"),
        TunnelSessionState::Destroyed => Err("Tunnel session has been destroyed"),
    }
}

pub(crate) fn ensure_tunnel_destroyable(state: &TunnelSessionState) -> Result<(), &'static str> {
    match state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting { .. } => Err("Cannot destroy a starting tunnel session"),
        TunnelSessionState::CleanupPending { .. } => Ok(()),
        TunnelSessionState::Running { .. } => Err("Cannot destroy a running tunnel session"),
        TunnelSessionState::Destroyed => Err("Tunnel session has already been destroyed"),
    }
}
