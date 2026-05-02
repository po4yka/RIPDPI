use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use super::super::registry::{TunnelSession, TunnelSessionState};

pub(crate) fn ensure_tunnel_start_allowed(state: &TunnelSessionState) -> Result<(), &'static str> {
    match state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting { .. } => Err("Tunnel session is already starting"),
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
        TunnelSessionState::Ready => Err("Tunnel session is not running"),
        TunnelSessionState::Destroyed => Err("Tunnel session has been destroyed"),
    }
}

pub(crate) fn ensure_tunnel_destroyable(state: &TunnelSessionState) -> Result<(), &'static str> {
    match state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting { .. } => Err("Cannot destroy a starting tunnel session"),
        TunnelSessionState::Running { .. } => Err("Cannot destroy a running tunnel session"),
        TunnelSessionState::Destroyed => Err("Tunnel session has already been destroyed"),
    }
}

pub(crate) fn rollback_failed_tunnel_start(session: &TunnelSession, message: String) {
    {
        let mut guard = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = Some(message.clone());
    }
    session.telemetry.record_error(message);
    session.telemetry.mark_stopped();
    {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *state = TunnelSessionState::Ready;
    }
}
