use std::sync::Arc;

use android_support::{throw_illegal_argument, HandleRegistry};
use jni::sys::jlong;
use jni::JNIEnv;
use ripdpi_monitor::MonitorSession;

use crate::to_handle;

pub(crate) static DIAGNOSTIC_SESSIONS: once_cell::sync::Lazy<HandleRegistry<MonitorSession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) fn create_diagnostics_session() -> jlong {
    DIAGNOSTIC_SESSIONS.insert(MonitorSession::new()) as jlong
}

pub(crate) fn diagnostics_session(env: &mut JNIEnv, handle: jlong) -> Option<Arc<MonitorSession>> {
    let Some(handle) = to_handle(handle) else {
        throw_illegal_argument(env, "Invalid diagnostics handle");
        return None;
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return None;
    };
    Some(session)
}

pub(crate) fn destroy_diagnostics_session(env: &mut JNIEnv, handle: jlong) {
    let Some(handle) = to_handle(handle) else {
        throw_illegal_argument(env, "Invalid diagnostics handle");
        return;
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.remove(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return;
    };
    session.destroy();
}
