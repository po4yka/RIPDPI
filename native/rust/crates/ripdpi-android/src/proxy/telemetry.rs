use jni::objects::JObject;
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use crate::errors::JniProxyError;

use super::registry::lookup_proxy_session;

pub(crate) fn poll_proxy_telemetry(env: &mut JNIEnv, handle: jlong) -> jstring {
    let result = env.with_local_frame_returning_local(4, |env| {
        let session = match lookup_proxy_session(handle) {
            Ok(session) => session,
            Err(err) => {
                err.throw(env);
                return Ok(JObject::null());
            }
        };
        match serde_json::to_string(&session.telemetry.snapshot()) {
            Ok(value) => env.new_string(value).map(std::convert::Into::into),
            Err(err) => {
                JniProxyError::Serialization(err).throw(env);
                Ok(JObject::null())
            }
        }
    });
    match result {
        Ok(obj) => obj.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
