//! Pure state transitions of a [`ProxySessionState`], applied under its mutex.

use std::sync::Arc;

use ripdpi_runtime_api::EmbeddedProxyControl;

use crate::registry::ProxySessionState;

/// Result of a successful start transition.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum ProxyStartTransition {
    Running,
    StoppedBeforeStart,
}

pub(crate) fn try_mark_proxy_running(
    state: &mut ProxySessionState,
    control: Arc<EmbeddedProxyControl>,
) -> Result<ProxyStartTransition, &'static str> {
    match *state {
        ProxySessionState::Idle => {
            *state = ProxySessionState::Running { control };
            Ok(ProxyStartTransition::Running)
        }
        ProxySessionState::StopRequested => {
            *state = ProxySessionState::Idle;
            Ok(ProxyStartTransition::StoppedBeforeStart)
        }
        ProxySessionState::Running { .. } => Err("Proxy session is already running"),
        ProxySessionState::Destroyed => Err("Proxy session has been destroyed"),
    }
}

/// Record a stop request. A running session returns its control for
/// shutdown; a session that has not started yet latches the request for the
/// next start.
pub(crate) fn request_proxy_stop(
    state: &mut ProxySessionState,
) -> Result<Option<Arc<EmbeddedProxyControl>>, &'static str> {
    match state {
        ProxySessionState::Idle | ProxySessionState::StopRequested => {
            *state = ProxySessionState::StopRequested;
            Ok(None)
        }
        ProxySessionState::Running { control } => Ok(Some(control.clone())),
        ProxySessionState::Destroyed => Err("Proxy session has been destroyed"),
    }
}

pub(crate) fn ensure_proxy_destroyable(state: &ProxySessionState) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle | ProxySessionState::StopRequested => Ok(()),
        ProxySessionState::Running { .. } => Err("Cannot destroy a running proxy session"),
        ProxySessionState::Destroyed => Err("Proxy session has already been destroyed"),
    }
}
