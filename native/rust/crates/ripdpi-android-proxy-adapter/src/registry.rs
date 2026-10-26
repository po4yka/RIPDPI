use std::sync::{Arc, Mutex};

use android_support::{HandleRegistry, clear_android_log_scope_level};
use jni::sys::jlong;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_api::EmbeddedProxyControl;

use ripdpi_android_bridge_support::JniProxyError;
use ripdpi_android_bridge_support::to_handle;
use ripdpi_android_telemetry_adapter::ProxyTelemetryState;

pub(crate) use crate::session_state::{
    ProxyStartTransition, ensure_proxy_destroyable, request_proxy_stop, try_mark_proxy_running,
};

pub(crate) static SESSIONS: std::sync::LazyLock<HandleRegistry<ProxySession>> =
    std::sync::LazyLock::new(HandleRegistry::new);

pub(crate) struct ProxySession {
    pub(crate) config: RuntimeConfig,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    pub(crate) telemetry: Arc<ProxyTelemetryState>,
    pub(crate) state: Mutex<ProxySessionState>,
}

pub(crate) enum ProxySessionState {
    Idle,
    /// A stop arrived before the session started. The next start consumes it
    /// and returns without running, so a stop issued during startup is never
    /// lost.
    StopRequested,
    Running {
        control: Arc<EmbeddedProxyControl>,
    },
    Destroyed,
}

pub(crate) fn lookup_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(JniProxyError::invalid_handle)?;
    SESSIONS.get(handle).ok_or_else(JniProxyError::unknown_handle)
}

pub(crate) fn remove_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(JniProxyError::invalid_handle)?;
    let session = SESSIONS.remove(handle).ok_or_else(JniProxyError::unknown_handle)?;
    clear_android_log_scope_level(session.telemetry.log_scope());
    Ok(session)
}
