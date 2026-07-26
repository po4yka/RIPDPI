use jni::Env;
use jni::sys::jlong;

use super::registry::lookup_tunnel_session;
use super::stats::{icmp_ingress_packets_for_state, saturate_u64_to_i64};

pub(crate) fn icmp_ingress_packets_session(env: &mut Env<'_>, handle: jlong) -> jlong {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            android_support::throw_illegal_argument_env(env, message);
            return 0;
        }
    };
    let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    saturate_u64_to_i64(icmp_ingress_packets_for_state(&state))
}
