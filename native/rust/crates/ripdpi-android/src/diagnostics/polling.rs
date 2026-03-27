use android_support::throw_runtime_exception_env;
use jni::sys::{jlong, jstring};
use jni::Env;
use ripdpi_monitor::MonitorSession;

use super::registry::diagnostics_session;

pub(crate) fn poll_progress(env: &mut Env<'_>, handle: jlong) -> jstring {
    poll_diagnostics_string(env, handle, MonitorSession::poll_progress_json)
}

pub(crate) fn take_report(env: &mut Env<'_>, handle: jlong) -> jstring {
    poll_diagnostics_string(env, handle, MonitorSession::take_report_json)
}

pub(crate) fn poll_passive_events(env: &mut Env<'_>, handle: jlong) -> jstring {
    poll_diagnostics_string(env, handle, MonitorSession::poll_passive_events_json)
}

fn poll_diagnostics_string<F>(env: &mut Env<'_>, handle: jlong, op: F) -> jstring
where
    F: FnOnce(&MonitorSession) -> Result<Option<String>, String>,
{
    let Some(session) = diagnostics_session(env, handle) else {
        return std::ptr::null_mut();
    };
    match op(&session) {
        Ok(Some(value)) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(err) => {
                throw_runtime_exception_env(env, err.to_string());
                std::ptr::null_mut()
            }
        },
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            throw_runtime_exception_env(env, err);
            std::ptr::null_mut()
        }
    }
}
