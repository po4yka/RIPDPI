use std::sync::Arc;

use android_support::{throw_illegal_argument_env, HandleRegistry};
use jni::sys::jlong;
use jni::Env;
use ripdpi_monitor::MonitorSession;

use super::platform_bridge::AndroidMonitorPlatformBridge;
use crate::to_handle;

pub(crate) static DIAGNOSTIC_SESSIONS: once_cell::sync::Lazy<HandleRegistry<MonitorSession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) fn create_diagnostics_session() -> jlong {
    DIAGNOSTIC_SESSIONS.insert(MonitorSession::with_platform_bridge(Arc::new(AndroidMonitorPlatformBridge))) as jlong
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
    let Some(session) = DIAGNOSTIC_SESSIONS.remove(handle) else {
        throw_illegal_argument_env(env, "Unknown diagnostics handle");
        return;
    };
    session.destroy();
}
