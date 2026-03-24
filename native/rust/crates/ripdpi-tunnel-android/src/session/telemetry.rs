use android_support::{throw_illegal_argument, throw_runtime_exception};
use jni::objects::JObject;
use jni::sys::jlong;
use jni::JNIEnv;

use crate::config::mapdns_resolver_protocol;

use super::registry::lookup_tunnel_session;
use super::stats::stats_snapshots_for_state;

pub(crate) fn telemetry_session(env: &mut JNIEnv, handle: jlong) -> jni::sys::jstring {
    let result = env.with_local_frame_returning_local(4, |env| {
        let session = match lookup_tunnel_session(handle) {
            Ok(session) => session,
            Err(message) => {
                throw_illegal_argument(env, message);
                return Ok(JObject::null());
            }
        };
        let (traffic_stats, dns_stats) = {
            let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            stats_snapshots_for_state(&state)
        };
        let resolver_id = session.config.mapdns.as_ref().and_then(|mapdns| mapdns.resolver_id.clone());
        let resolver_protocol = session.config.mapdns.as_ref().and_then(mapdns_resolver_protocol);
        match serde_json::to_string(&session.telemetry.snapshot(
            traffic_stats,
            dns_stats,
            resolver_id,
            resolver_protocol,
        )) {
            Ok(value) => env.new_string(value).map(std::convert::Into::into),
            Err(err) => {
                throw_runtime_exception(env, err.to_string());
                Ok(JObject::null())
            }
        }
    });
    match result {
        Ok(obj) => obj.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
