use android_support::throw_runtime_exception_env;
use jni::Env;
use jni::sys::{jlong, jstring};

use ripdpi_android_bridge_support::JniProxyError;

use super::quality_sink::serialize_proxy_telemetry;
use super::registry::lookup_proxy_session;

pub(crate) fn poll_proxy_telemetry(env: &mut Env<'_>, handle: jlong) -> jstring {
    let telemetry = match proxy_telemetry_json(handle) {
        Ok(value) => value,
        Err(err) => return throw_proxy_error(env, err),
    };
    match env.new_string(telemetry) {
        Ok(value) => value.into_raw(),
        Err(err) => throw_runtime_error(env, err),
    }
}

fn proxy_telemetry_json(handle: jlong) -> Result<String, JniProxyError> {
    Ok(serialize_proxy_telemetry(&lookup_proxy_session(handle)?.telemetry.snapshot())?)
}

fn throw_proxy_error(env: &mut Env<'_>, err: JniProxyError) -> jstring {
    err.throw(env);
    std::ptr::null_mut()
}

fn throw_runtime_error(env: &mut Env<'_>, err: jni::errors::Error) -> jstring {
    throw_runtime_exception_env(env, err.to_string());
    std::ptr::null_mut()
}
