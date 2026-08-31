use std::sync::Arc;

use android_support::{HandleRegistry, throw_illegal_argument_env};
use jni::Env;
use jni::sys::jlong;
use ripdpi_monitor_engine::MonitorSession;
use ripdpi_monitor_proxy_runtime::ProductionCandidateRuntimeLauncher;

use super::platform_bridge::AndroidMonitorPlatformBridge;
use ripdpi_android_bridge_support::{invalid_handle_message, to_handle, unknown_handle_message};

pub(crate) static DIAGNOSTIC_SESSIONS: std::sync::LazyLock<HandleRegistry<MonitorSession>> =
    std::sync::LazyLock::new(HandleRegistry::new);

pub(crate) fn create_diagnostics_session() -> jlong {
    DIAGNOSTIC_SESSIONS.insert(MonitorSession::with_platform_bridge_and_candidate_runtime_launcher(
        Arc::new(AndroidMonitorPlatformBridge),
        Arc::new(ProductionCandidateRuntimeLauncher::new(Arc::new(ripdpi_ws_tunnel::TelegramWsTransport))),
    )) as jlong
}

pub(crate) fn diagnostics_session(env: &mut Env<'_>, handle: jlong) -> Option<Arc<MonitorSession>> {
    let Some(handle) = to_handle(handle) else {
        throw_illegal_argument_env(env, invalid_handle_message("diagnostics"));
        return None;
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.get(handle) else {
        throw_illegal_argument_env(env, unknown_handle_message("diagnostics"));
        return None;
    };
    Some(session)
}

pub(crate) fn destroy_diagnostics_session(env: &mut Env<'_>, handle: jlong) {
    let Some(handle) = to_handle(handle) else {
        throw_illegal_argument_env(env, invalid_handle_message("diagnostics"));
        return;
    };
    match DIAGNOSTIC_SESSIONS.remove(handle) {
        Some(session) => session.destroy(),
        None => throw_illegal_argument_env(env, unknown_handle_message("diagnostics")),
    }
}
