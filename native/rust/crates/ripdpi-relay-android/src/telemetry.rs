use std::sync::Arc;

use android_support::{NativeEventRecord, drain_relay_events_for_runtime};
use ripdpi_quality::{ConnectionQualitySnapshot, QualitySample, QualityWindow, TransportKind};
use ripdpi_relay_core::TcpConnectObservation;
use serde::Serialize;

use crate::runtime::SessionRuntime;
use std::sync::LazyLock;

/// Process-wide quality window for the relay runtime. Receives observations
/// from the quality observer installed in `install_quality_observer`.
static QUALITY_WINDOW: LazyLock<Arc<QualityWindow>> =
    LazyLock::new(|| Arc::new(QualityWindow::new(TransportKind::UdpRelay)));

pub(crate) const IDLE_TELEMETRY_JSON: &str =
    "{\"source\":\"relay\",\"schemaVersion\":3,\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}";

/// Runtime-telemetry payload schema version emitted on every snapshot.
const SNAPSHOT_SCHEMA_VERSION: u32 = 3;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeRuntimeEvent {
    source: String,
    level: String,
    message: String,
    created_at: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    runtime_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    policy_signature: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    fingerprint_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    subsystem: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attempt_id: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attempt_sequence: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stage: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    outcome: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    failure_stage: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    failure_class: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    io_error_kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    os_error_code: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    peer_close_phase: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    carrier_disposition: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeRuntimeSnapshot<T> {
    schema_version: u32,
    #[serde(flatten)]
    telemetry: T,
    native_events: Vec<NativeRuntimeEvent>,
    native_events_dropped: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    connection_quality: Option<ConnectionQualitySnapshot>,
}

impl From<NativeEventRecord> for NativeRuntimeEvent {
    fn from(value: NativeEventRecord) -> Self {
        Self {
            source: value.source,
            level: value.level,
            message: value.message,
            created_at: value.created_at,
            kind: value.kind,
            runtime_id: value.runtime_id,
            mode: value.mode,
            policy_signature: value.policy_signature,
            fingerprint_hash: value.fingerprint_hash,
            subsystem: value.subsystem,
            attempt_id: value.attempt_id,
            attempt_sequence: value.attempt_sequence,
            stage: value.stage,
            outcome: value.outcome,
            duration_ms: value.duration_ms,
            failure_stage: value.failure_stage,
            failure_class: value.failure_class,
            io_error_kind: value.io_error_kind,
            os_error_code: value.os_error_code,
            peer_close_phase: value.peer_close_phase,
            carrier_disposition: value.carrier_disposition,
        }
    }
}

pub(crate) fn serialize_runtime_telemetry(session: &SessionRuntime, runtime_id: &str) -> Option<String> {
    match session {
        SessionRuntime::Standard(session) => serialize_telemetry(session.telemetry(), runtime_id),
        SessionRuntime::AppsScript(session) => serialize_telemetry(session.telemetry(), runtime_id),
    }
}

fn serialize_telemetry<T>(telemetry: T, runtime_id: &str) -> Option<String>
where
    T: Serialize,
{
    serde_json::to_string(&snapshot_from_telemetry(telemetry, runtime_id)).ok()
}

fn snapshot_from_telemetry<T>(telemetry: T, runtime_id: &str) -> NativeRuntimeSnapshot<T> {
    let drained = drain_relay_events_for_runtime(runtime_id);
    NativeRuntimeSnapshot {
        schema_version: SNAPSHOT_SCHEMA_VERSION,
        telemetry,
        native_events: drained.events.into_iter().map(NativeRuntimeEvent::from).collect(),
        native_events_dropped: drained.dropped_event_count,
        connection_quality: QUALITY_WINDOW.snapshot(),
    }
}

/// Install a quality observer on `session` that records every upstream TCP
/// connect observation into the process-wide `QUALITY_WINDOW`.
///
/// `SessionRuntime::AppsScript` has no observer API; the arm is a silent
/// no-op. Only `SessionRuntime::Standard` (`ripdpi_relay_core::RelayRuntime`)
/// is wired.
///
/// Called once per session in `relay_create_entry`, before `insert_session`.
///
/// Cancel-safety: synchronous; no `.await` inside.
pub(crate) fn install_quality_observer(session: &SessionRuntime) {
    let window = QUALITY_WINDOW.clone();
    let observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync> = Arc::new(move |obs| {
        if obs.succeeded {
            if obs.rtt_ms > 0 {
                window.record(QualitySample { rtt_ms: obs.rtt_ms, succeeded: true, loss_pct: 0.0 });
            }
        } else {
            window.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
        }
    });
    match session {
        SessionRuntime::Standard(runtime) => runtime.set_quality_observer(observer),
        SessionRuntime::AppsScript(_) => { /* no observer API; skip */ }
    }
}

#[cfg(test)]
mod tests {
    use android_support::{EventRingBuffers, EventRingLayer, NativeEventRecord, RingConfig};
    use golden_test_support::{assert_contract_fixture, extract_field_paths};
    use ripdpi_relay_core::RelayTelemetry as StandardRelayTelemetry;
    use tracing_subscriber::prelude::*;

    use super::{IDLE_TELEMETRY_JSON, NativeRuntimeEvent, NativeRuntimeSnapshot, SNAPSHOT_SCHEMA_VERSION};

    fn sample_telemetry() -> StandardRelayTelemetry {
        StandardRelayTelemetry {
            source: "relay",
            state: "running".to_string(),
            health: "healthy".to_string(),
            active_sessions: 1,
            total_sessions: 1,
            listener_address: Some("127.0.0.1:1080".to_string()),
            upstream_address: Some("relay.example.test:443".to_string()),
            last_target: None,
            last_error: None,
            profile_id: Some("relay-profile".to_string()),
            protocol_kind: Some("vless".to_string()),
            tcp_capable: Some(true),
            udp_capable: Some(false),
            xudp_telemetry: None,
            fallback_mode: None,
            last_handshake_error: None,
            chain_entry_state: None,
            chain_entry_latency_ms: None,
            chain_exit_state: None,
            chain_exit_latency_ms: None,
            chain_intermediate_hops: Vec::new(),
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: Some("chrome_stable".to_string()),
            tls_profile_catalog_version: Some("v1".to_string()),
            morph_policy_id: None,
            quic_migration_status: None,
            quic_migration_reason: None,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            confirm_good_dpi_eligible: false,
            confirm_good_dpi_evidence: None,
            captured_at: 0,
        }
    }

    fn snapshot_from_buffers(buffers: &EventRingBuffers) -> NativeRuntimeSnapshot<StandardRelayTelemetry> {
        NativeRuntimeSnapshot {
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            telemetry: sample_telemetry(),
            native_events: buffers.drain_relay().into_iter().map(NativeRuntimeEvent::from).collect(),
            native_events_dropped: 0,
            connection_quality: None,
        }
    }

    #[test]
    fn relay_snapshot_json_carries_schema_version() {
        let snapshot = NativeRuntimeSnapshot {
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            telemetry: sample_telemetry(),
            native_events: Vec::<NativeRuntimeEvent>::new(),
            native_events_dropped: 0,
            connection_quality: None,
        };
        let value = serde_json::to_value(&snapshot).expect("serialize relay snapshot");
        assert_eq!(value["schemaVersion"], serde_json::json!(3));
    }

    #[test]
    fn relay_snapshot_field_manifest_matches_contract_fixture() {
        let snapshot = NativeRuntimeSnapshot {
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            telemetry: sample_telemetry(),
            native_events: vec![sample_stage_event()],
            native_events_dropped: 3,
            connection_quality: None,
        };
        let paths = extract_field_paths(&serde_json::to_value(&snapshot).expect("serialize relay snapshot"));
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize relay snapshot paths");
        assert_contract_fixture("relay_snapshot_fields.json", &manifest);
    }

    #[test]
    fn relay_event_field_manifest_matches_contract_fixture() {
        let paths = extract_field_paths(&serde_json::to_value(sample_stage_event()).expect("serialize relay event"));
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize relay event paths");
        assert_contract_fixture("relay_event_fields.json", &manifest);
    }

    fn sample_stage_event() -> NativeRuntimeEvent {
        NativeEventRecord {
            source: "relay".to_string(),
            level: "error".to_string(),
            message: "event=relay_attempt_stage".to_string(),
            created_at: 1,
            kind: Some("relay_attempt_stage".to_string()),
            runtime_id: Some("runtime-1".to_string()),
            mode: Some("vpn".to_string()),
            policy_signature: Some("policy".to_string()),
            fingerprint_hash: Some("fingerprint".to_string()),
            diagnostics_session_id: Some("diagnostics-1".to_string()),
            subsystem: Some("relay".to_string()),
            attempt_id: Some(1),
            attempt_sequence: Some(2),
            stage: Some("reality_tls".to_string()),
            outcome: Some("failed".to_string()),
            duration_ms: Some(3),
            failure_stage: Some("reality_tls".to_string()),
            failure_class: Some("io_error".to_string()),
            io_error_kind: Some("connection_refused".to_string()),
            os_error_code: Some(111),
            peer_close_phase: Some("before_response".to_string()),
            carrier_disposition: Some("carrier_created".to_string()),
        }
        .into()
    }

    #[test]
    fn relay_idle_telemetry_json_carries_schema_version() {
        // The unknown-handle fallback in `relay_poll_telemetry_entry` returns
        // IDLE_TELEMETRY_JSON verbatim instead of going through
        // `snapshot_from_telemetry`, so the hand-written constant needs its own
        // schemaVersion guard. See docs/architecture/TELEMETRY_CONTRACT.md.
        let value: serde_json::Value = serde_json::from_str(IDLE_TELEMETRY_JSON).expect("idle telemetry json is valid");
        assert_eq!(value["schemaVersion"], serde_json::json!(SNAPSHOT_SCHEMA_VERSION));
        assert_eq!(value["source"], serde_json::json!("relay"));
    }

    #[test]
    fn relay_snapshot_drains_runtime_ready_event_once() {
        let buffers = EventRingBuffers::new(RingConfig::default());
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(
                ring = "relay",
                subsystem = "relay",
                source = "relay",
                kind = "runtime_ready",
                "listener started addr=127.0.0.1:1080"
            );

            let first = snapshot_from_buffers(&buffers);
            assert_eq!(first.native_events.len(), 1);
            assert_eq!(first.native_events[0].kind.as_deref(), Some("runtime_ready"));

            let second = snapshot_from_buffers(&buffers);
            assert!(second.native_events.is_empty());
        });
    }

    #[test]
    fn relay_snapshot_drains_runtime_stopped_event() {
        let buffers = EventRingBuffers::new(RingConfig::default());
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(
                ring = "relay",
                subsystem = "relay",
                source = "relay",
                kind = "runtime_stopped",
                "listener stopped"
            );

            let snapshot = snapshot_from_buffers(&buffers);
            assert_eq!(snapshot.native_events.len(), 1);
            assert_eq!(snapshot.native_events[0].kind.as_deref(), Some("runtime_stopped"));
        });
    }
}
