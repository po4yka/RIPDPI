use std::sync::{Arc, Mutex};

use android_support::{clear_android_log_scope_level, HandleRegistry};
use jni::sys::jlong;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime::EmbeddedProxyControl;

use crate::errors::JniProxyError;
use crate::telemetry::ProxyTelemetryState;
use crate::to_handle;

pub(crate) static SESSIONS: once_cell::sync::Lazy<HandleRegistry<ProxySession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) struct ProxySession {
    pub(crate) config: RuntimeConfig,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    pub(crate) telemetry: Arc<ProxyTelemetryState>,
    pub(crate) state: Mutex<ProxySessionState>,
}

pub(crate) enum ProxySessionState {
    Idle,
    Running { control: Arc<EmbeddedProxyControl> },
    Destroyed,
}

pub(crate) fn lookup_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    SESSIONS.get(handle).ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))
}

pub(crate) fn remove_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    let session =
        SESSIONS.remove(handle).ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))?;
    clear_android_log_scope_level(session.telemetry.log_scope());
    Ok(session)
}

pub(crate) fn try_mark_proxy_running(
    state: &mut ProxySessionState,
    control: Arc<EmbeddedProxyControl>,
) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle => {
            *state = ProxySessionState::Running { control };
            Ok(())
        }
        ProxySessionState::Running { .. } => Err("Proxy session is already running"),
        ProxySessionState::Destroyed => Err("Proxy session has been destroyed"),
    }
}

pub(crate) fn control_for_proxy_stop(state: &ProxySessionState) -> Result<Arc<EmbeddedProxyControl>, &'static str> {
    match state {
        ProxySessionState::Idle => Err("Proxy session is not running"),
        ProxySessionState::Running { control } => Ok(control.clone()),
        ProxySessionState::Destroyed => Err("Proxy session has been destroyed"),
    }
}

pub(crate) fn ensure_proxy_destroyable(state: &ProxySessionState) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle => Ok(()),
        ProxySessionState::Running { .. } => Err("Cannot destroy a running proxy session"),
        ProxySessionState::Destroyed => Err("Proxy session has already been destroyed"),
    }
}
