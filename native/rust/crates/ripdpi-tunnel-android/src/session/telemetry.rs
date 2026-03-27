use android_support::{throw_illegal_argument_env, throw_runtime_exception_env};
use jni::sys::jlong;
use jni::Env;

use crate::config::mapdns_resolver_protocol;

use super::registry::lookup_tunnel_session;
use super::stats::stats_snapshots_for_state;

pub(crate) fn telemetry_session(env: &mut Env<'_>, handle: jlong) -> jni::sys::jstring {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return std::ptr::null_mut();
        }
    };
    let (traffic_stats, dns_stats) = {
        let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        stats_snapshots_for_state(&state)
    };
    let resolver_id = session.config.mapdns.as_ref().and_then(|mapdns| mapdns.resolver_id.clone());
    let resolver_protocol = session.config.mapdns.as_ref().and_then(mapdns_resolver_protocol);
    match serde_json::to_string(&session.telemetry.snapshot(traffic_stats, dns_stats, resolver_id, resolver_protocol)) {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(err) => {
                throw_runtime_exception_env(env, err.to_string());
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            throw_runtime_exception_env(env, err.to_string());
            std::ptr::null_mut()
        }
    }
}
