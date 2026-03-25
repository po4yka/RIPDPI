use android_support::throw_illegal_argument;
use jni::sys::{jlong, jlongArray};
use jni::JNIEnv;
use ripdpi_tunnel_core::DnsStatsSnapshot;

use super::registry::{lookup_tunnel_session, TunnelSessionState};

/// Saturate a `u64` to `i64::MAX` so JNI `jlong` values never wrap negative.
fn saturate_u64_to_i64(v: u64) -> i64 {
    if v > i64::MAX as u64 {
        i64::MAX
    } else {
        v as i64
    }
}

pub(crate) fn stats_session(env: &mut JNIEnv, handle: jlong) -> jlongArray {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return std::ptr::null_mut();
        }
    };

    let snapshot = {
        let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        stats_snapshots_for_state(&state).0
    };

    match env.new_long_array(4) {
        Ok(arr) => {
            let values: [i64; 4] = [
                saturate_u64_to_i64(snapshot.0),
                saturate_u64_to_i64(snapshot.1),
                saturate_u64_to_i64(snapshot.2),
                saturate_u64_to_i64(snapshot.3),
            ];
            if env.set_long_array_region(&arr, 0, &values).is_ok() {
                arr.into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

pub(crate) fn stats_snapshots_for_state(state: &TunnelSessionState) -> ((u64, u64, u64, u64), DnsStatsSnapshot) {
    match state {
        TunnelSessionState::Ready | TunnelSessionState::Starting { .. } | TunnelSessionState::Destroyed => {
            ((0, 0, 0, 0), DnsStatsSnapshot::default())
        }
        TunnelSessionState::Running { stats, .. } => (stats.snapshot(), stats.dns_snapshot()),
    }
}
