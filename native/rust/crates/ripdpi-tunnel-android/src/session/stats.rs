use android_support::throw_illegal_argument_env;
use jni::objects::JLongArray;
use jni::sys::{jlong, jlongArray};
use jni::Env;
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

pub(crate) fn stats_session(env: &mut Env<'_>, handle: jlong) -> jlongArray {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return std::ptr::null_mut();
        }
    };

    let snapshot = {
        let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        stats_snapshots_for_state(&state).0
    };

    match env.new_long_array(4) {
        Ok(arr) => {
            let arr: JLongArray<'_> = arr;
            let values: [i64; 4] = [
                saturate_u64_to_i64(snapshot.0),
                saturate_u64_to_i64(snapshot.1),
                saturate_u64_to_i64(snapshot.2),
                saturate_u64_to_i64(snapshot.3),
            ];
            if arr.set_region(env, 0, &values).is_ok() {
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

#[cfg(test)]
mod tests {
    use super::*;

    use golden_test_support::assert_contract_fixture;
    use serde_json::json;

    /// Field names and their indices in the JNI `jlongArray` returned by `jniGetStats`.
    /// This ordering is a binary contract between Rust and Kotlin.
    const STATS_FIELD_NAMES: [&str; 4] = ["txPackets", "txBytes", "rxPackets", "rxBytes"];

    #[test]
    fn tunnel_stats_layout_matches_contract_fixture() {
        let mut indices = serde_json::Map::new();
        for (i, name) in STATS_FIELD_NAMES.iter().enumerate() {
            indices.insert(name.to_string(), json!(i));
        }

        let layout = json!({
            "arrayLength": STATS_FIELD_NAMES.len(),
            "indices": indices,
        });

        let actual = serde_json::to_string_pretty(&layout).expect("serialize layout");
        assert_contract_fixture("tunnel_stats_layout.json", &actual);
    }

    #[test]
    fn saturation_caps_at_i64_max() {
        assert_eq!(saturate_u64_to_i64(0), 0);
        assert_eq!(saturate_u64_to_i64(100), 100);
        assert_eq!(saturate_u64_to_i64(i64::MAX as u64), i64::MAX);
        assert_eq!(saturate_u64_to_i64(u64::MAX), i64::MAX);
    }
}
