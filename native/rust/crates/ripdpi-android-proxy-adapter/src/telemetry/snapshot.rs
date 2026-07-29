use jni::Env;
use jni::sys::{jlong, jstring};
use ripdpi_android_bridge_support::JniProxyError;

use super::polling::poll_json;
use crate::quality_sink::serialize_proxy_telemetry;
use crate::registry::lookup_proxy_session;

pub(crate) fn poll_proxy_telemetry(env: &mut Env<'_>, handle: jlong) -> jstring {
    poll_json(env, proxy_telemetry_json(handle))
}

fn proxy_telemetry_json(handle: jlong) -> Result<String, JniProxyError> {
    Ok(serialize_proxy_telemetry(&lookup_proxy_session(handle)?.telemetry.snapshot())?)
}
