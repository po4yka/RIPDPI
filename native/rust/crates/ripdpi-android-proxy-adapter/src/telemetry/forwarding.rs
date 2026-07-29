use jni::Env;
use jni::sys::{jlong, jstring};
use ripdpi_android_bridge_support::JniProxyError;

use super::polling::poll_json;
use crate::registry::lookup_proxy_session;

pub(crate) fn poll_proxy_forwarding_evidence(env: &mut Env<'_>, handle: jlong) -> jstring {
    poll_json(env, proxy_forwarding_evidence_json(handle))
}

fn proxy_forwarding_evidence_json(handle: jlong) -> Result<String, JniProxyError> {
    Ok(serde_json::to_string(&lookup_proxy_session(handle)?.telemetry.forwarding_evidence_snapshot())?)
}
