use android_support::{throw_illegal_argument_env, throw_runtime_exception_env};
use jni::Env;
use jni::sys::{jlong, jstring};
use ripdpi_tunnel_core::TunForwardingEvidenceSnapshot;
use serde::Serialize;

use super::registry::{TunnelSessionState, lookup_tunnel_session};

pub(crate) fn forwarding_evidence_session(env: &mut Env<'_>, handle: jlong) -> jstring {
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

fn forwarding_evidence_for_state(state: &TunnelSessionState) -> TunForwardingEvidenceSnapshot {
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
