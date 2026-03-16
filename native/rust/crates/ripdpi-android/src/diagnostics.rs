use android_support::{
    init_android_logging, throw_illegal_argument, throw_illegal_state, throw_runtime_exception, HandleRegistry,
};
use jni::objects::JString;
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use ripdpi_monitor::{MonitorSession, ScanRequest};

use crate::errors::extract_panic_message;
use crate::to_handle;

pub(crate) static DIAGNOSTIC_SESSIONS: once_cell::sync::Lazy<HandleRegistry<MonitorSession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) fn diagnostics_create_entry(mut env: JNIEnv) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        DIAGNOSTIC_SESSIONS.insert(MonitorSession::new()) as jlong
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics session creation panicked: {msg}"));
        0
    })
}

pub(crate) fn diagnostics_start_scan_entry(mut env: JNIEnv, handle: jlong, request_json: JString, session_id: JString) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_diagnostics_scan(&mut env, handle, request_json, session_id);
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics scan start panicked: {msg}"));
    });
}

pub(crate) fn diagnostics_cancel_scan_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        diagnostics_session(&mut env, handle)?.cancel_scan();
        Some(())
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics cancel panicked: {msg}"));
    });
}

pub(crate) fn diagnostics_poll_progress_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, MonitorSession::poll_progress_json)
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics progress polling panicked: {msg}"));
        std::ptr::null_mut()
    })
}

pub(crate) fn diagnostics_take_report_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, MonitorSession::take_report_json)
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics report polling panicked: {msg}"));
        std::ptr::null_mut()
    })
}

pub(crate) fn diagnostics_poll_passive_events_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, MonitorSession::poll_passive_events_json)
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics passive polling panicked: {msg}"));
        std::ptr::null_mut()
    })
}

pub(crate) fn diagnostics_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_diagnostics_session(&mut env, handle)))
        .map_err(|panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics session destroy panicked: {msg}"));
        });
}

fn diagnostics_session(env: &mut JNIEnv, handle: jlong) -> Option<std::sync::Arc<MonitorSession>> {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid diagnostics handle");
            return None;
        }
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return None;
    };
    Some(session)
}

fn start_diagnostics_scan(env: &mut JNIEnv, handle: jlong, request_json: JString, session_id: JString) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    let request_json: String = match env.get_string(&request_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics request JSON");
            return;
        }
    };
    let session_id: String = match env.get_string(&session_id) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics session id");
            return;
        }
    };
    let request: ScanRequest = match serde_json::from_str(&request_json) {
        Ok(request) => request,
        Err(err) => {
            throw_illegal_argument(env, format!("Invalid diagnostics request: {err}"));
            return;
        }
    };
    if let Err(err) = session.start_scan(session_id, request) {
        throw_illegal_state(env, err);
    }
}

fn poll_diagnostics_string<F>(env: &mut JNIEnv, handle: jlong, op: F) -> jstring
where
    F: FnOnce(&MonitorSession) -> Result<Option<String>, String>,
{
    let Some(session) = diagnostics_session(env, handle) else {
        return std::ptr::null_mut();
    };
    match op(&session) {
        Ok(Some(value)) => env.new_string(value).map(jni::objects::JString::into_raw).unwrap_or(std::ptr::null_mut()),
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            throw_runtime_exception(env, err);
            std::ptr::null_mut()
        }
    }
}

fn destroy_diagnostics_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid diagnostics handle");
            return;
        }
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.remove(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return;
    };
    session.destroy();
}
