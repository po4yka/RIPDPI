use std::sync::Arc;

use android_support::{throw_illegal_argument_env, HandleRegistry};
use jni::sys::jlong;
use jni::Env;
use ripdpi_monitor_engine::MonitorSession;
use ripdpi_monitor_proxy_runtime::ProductionCandidateRuntimeLauncher;

use super::platform_bridge::AndroidMonitorPlatformBridge;
use ripdpi_android_bridge_support::to_handle;

pub(crate) static DIAGNOSTIC_SESSIONS: once_cell::sync::Lazy<HandleRegistry<MonitorSession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) fn create_diagnostics_session() -> jlong {
    DIAGNOSTIC_SESSIONS.insert(MonitorSession::with_platform_bridge_and_candidate_runtime_launcher(
        Arc::new(AndroidMonitorPlatformBridge),
        Arc::new(ProductionCandidateRuntimeLauncher),
    )) as jlong
}

pub(crate) fn diagnostics_session(env: &mut Env<'_>, handle: jlong) -> Option<Arc<MonitorSession>> {
    let Some(handle) = to_handle(handle) else {
        throw_illegal_argument_env(env, "Invalid diagnostics handle");
        return None;
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.get(handle) else {
        throw_illegal_argument_env(env, "Unknown diagnostics handle");
        return None;
    };
    Some(session)
}

pub(crate) fn destroy_diagnostics_session(env: &mut Env<'_>, handle: jlong) {
    let Some(handle) = to_handle(handle) else {
        throw_illegal_argument_env(env, "Invalid diagnostics handle");
        return;
    };
    match DIAGNOSTIC_SESSIONS.remove(handle) {
        Some(session) => session.destroy(),
        None => throw_illegal_argument_env(env, "Unknown diagnostics handle"),
    }
}
