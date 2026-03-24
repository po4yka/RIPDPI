use android_support::throw_runtime_exception;
use jni::objects::JObject;
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use ripdpi_monitor::MonitorSession;

use super::registry::diagnostics_session;

pub(crate) fn poll_progress(env: &mut JNIEnv, handle: jlong) -> jstring {
    poll_diagnostics_string(env, handle, MonitorSession::poll_progress_json)
}

pub(crate) fn take_report(env: &mut JNIEnv, handle: jlong) -> jstring {
    poll_diagnostics_string(env, handle, MonitorSession::take_report_json)
}

pub(crate) fn poll_passive_events(env: &mut JNIEnv, handle: jlong) -> jstring {
    poll_diagnostics_string(env, handle, MonitorSession::poll_passive_events_json)
}

fn poll_diagnostics_string<F>(env: &mut JNIEnv, handle: jlong, op: F) -> jstring
where
    F: FnOnce(&MonitorSession) -> Result<Option<String>, String>,
{
    let result = env.with_local_frame_returning_local(4, |env| {
        let Some(session) = diagnostics_session(env, handle) else {
            return Ok(JObject::null());
        };
        match op(&session) {
            Ok(Some(value)) => env.new_string(value).map(|s| s.into()),
            Ok(None) => Ok(JObject::null()),
            Err(err) => {
                throw_runtime_exception(env, err);
                Ok(JObject::null())
            }
        }
    });
    match result {
        Ok(obj) => obj.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
