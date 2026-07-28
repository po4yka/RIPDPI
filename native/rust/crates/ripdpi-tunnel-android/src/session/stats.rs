use android_support::{throw_illegal_argument_env, throw_runtime_exception_env};
use jni::Env;
use jni::objects::JLongArray;
use jni::sys::{jlong, jlongArray};
use ripdpi_tunnel_core::{DnsStatsSnapshot, TunForwardingEvidenceSnapshot};
use serde::Serialize;

use super::registry::{TunnelSessionState, lookup_tunnel_session};

/// Saturate a `u64` to `i64::MAX` so JNI `jlong` values never wrap negative.
pub(super) fn saturate_u64_to_i64(v: u64) -> i64 {
    if v > i64::MAX as u64 { i64::MAX } else { v as i64 }
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
            if arr.set_region(env, 0, &values).is_ok() { arr.into_raw() } else { std::ptr::null_mut() }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

pub(crate) fn stats_snapshots_for_state(state: &TunnelSessionState) -> ((u64, u64, u64, u64), DnsStatsSnapshot) {
    match state {
        TunnelSessionState::Ready
        | TunnelSessionState::Starting { .. }
        | TunnelSessionState::CleanupPending { .. }
        | TunnelSessionState::Destroyed => ((0, 0, 0, 0), DnsStatsSnapshot::default()),
        TunnelSessionState::Running { stats, .. } => (stats.snapshot(), stats.dns_snapshot()),
    }
}

pub(crate) fn icmp_ingress_packets_for_state(state: &TunnelSessionState) -> u64 {
    match state {
        TunnelSessionState::Running { stats, .. } => stats.icmp_ingress_packets(),
        _ => 0,
    }
}

pub(crate) fn forwarding_evidence_session(env: &mut Env<'_>, handle: jlong) -> jni::sys::jstring {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return std::ptr::null_mut();
        }
    };

    let snapshot = {
        let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        forwarding_evidence_for_state(&state)
    };

    let wire = TunForwardingEvidenceWire::from(snapshot);
    match serde_json::to_string(&wire) {
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

pub(crate) fn forwarding_evidence_for_state(state: &TunnelSessionState) -> TunForwardingEvidenceSnapshot {
    match state {
        TunnelSessionState::Running { stats, .. } => stats.tun_forwarding_evidence_snapshot(),
        _ => TunForwardingEvidenceSnapshot::default(),
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TunForwardingEvidenceWire {
    tun_read_packets: u64,
    tun_read_bytes: u64,
    tun_write_packets: u64,
    tun_write_bytes: u64,
    tun_read_errors: u64,
    tun_write_errors: u64,
    tun_parse_failures: u64,
    tun_policy_drops: u64,
    tun_interceptor_drops: u64,
    tun_queue_drops: u64,
    first_tun_write_at_epoch_ms: Option<u64>,
    last_tun_write_at_epoch_ms: Option<u64>,
}

impl From<TunForwardingEvidenceSnapshot> for TunForwardingEvidenceWire {
    fn from(snapshot: TunForwardingEvidenceSnapshot) -> Self {
        Self {
            tun_read_packets: snapshot.tun_read_packets,
            tun_read_bytes: snapshot.tun_read_bytes,
            tun_write_packets: snapshot.tun_write_packets,
            tun_write_bytes: snapshot.tun_write_bytes,
            tun_read_errors: snapshot.tun_read_errors,
            tun_write_errors: snapshot.tun_write_errors,
            tun_parse_failures: snapshot.tun_parse_failures,
            tun_policy_drops: snapshot.tun_policy_drops,
            tun_interceptor_drops: snapshot.tun_interceptor_drops,
            tun_queue_drops: snapshot.tun_queue_drops,
            first_tun_write_at_epoch_ms: snapshot.first_tun_write_at_epoch_ms,
            last_tun_write_at_epoch_ms: snapshot.last_tun_write_at_epoch_ms,
        }
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

    #[test]
    fn forwarding_evidence_when_ready_is_zero() {
        assert_eq!(forwarding_evidence_for_state(&TunnelSessionState::Ready), TunForwardingEvidenceSnapshot::default());
    }

    #[test]
    fn forwarding_evidence_wire_uses_camel_case_counts_only() {
        let wire = TunForwardingEvidenceWire::from(TunForwardingEvidenceSnapshot {
            tun_read_packets: 2,
            tun_read_bytes: 128,
            tun_write_packets: 1,
            tun_write_bytes: 64,
            tun_read_errors: 3,
            tun_write_errors: 4,
            tun_parse_failures: 5,
            tun_policy_drops: 6,
            tun_interceptor_drops: 7,
            tun_queue_drops: 8,
            first_tun_write_at_epoch_ms: Some(10),
            last_tun_write_at_epoch_ms: Some(12),
        });

        let json = serde_json::to_value(wire).expect("serialize forwarding evidence");
        assert_eq!(json["tunReadPackets"], 2);
        assert_eq!(json["tunWriteBytes"], 64);
        assert_eq!(json["tunParseFailures"], 5);
        assert_eq!(json["firstTunWriteAtEpochMs"], 10);
        assert!(json.get("lastTarget").is_none(), "evidence must not carry endpoint identity");
    }
}
