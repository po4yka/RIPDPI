use android_support::throw_runtime_exception_env;
use jni::sys::{jlong, jstring};
use jni::Env;

use crate::errors::JniProxyError;

use super::registry::lookup_proxy_session;

pub(crate) fn poll_proxy_telemetry(env: &mut Env<'_>, handle: jlong) -> jstring {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return std::ptr::null_mut();
        }
    };
    match serde_json::to_string(&session.telemetry.snapshot()) {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(err) => {
                throw_runtime_exception_env(env, err.to_string());
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            JniProxyError::Serialization(err).throw(env);
            std::ptr::null_mut()
        }
    }
}
