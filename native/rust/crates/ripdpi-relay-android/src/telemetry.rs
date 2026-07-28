use std::sync::Arc;

use android_support::{NativeEventRecord, drain_relay_events};
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
    diagnostics_session_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    subsystem: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeRuntimeSnapshot<T> {
    schema_version: u32,
    #[serde(flatten)]
    telemetry: T,
    native_events: Vec<NativeRuntimeEvent>,
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
            diagnostics_session_id: value.diagnostics_session_id,
            subsystem: value.subsystem,
        }
    }
}

pub(crate) fn serialize_runtime_telemetry(session: &SessionRuntime) -> Option<String> {
    match session {
        SessionRuntime::Standard(session) => serialize_telemetry(session.telemetry()),
        SessionRuntime::AppsScript(session) => serialize_telemetry(session.telemetry()),
    }
}

fn serialize_telemetry<T>(telemetry: T) -> Option<String>
where
    T: Serialize,
{
    serde_json::to_string(&snapshot_from_telemetry(telemetry)).ok()
}

fn snapshot_from_telemetry<T>(telemetry: T) -> NativeRuntimeSnapshot<T> {
    NativeRuntimeSnapshot {
        schema_version: SNAPSHOT_SCHEMA_VERSION,
        telemetry,
        native_events: drain_relay_events().into_iter().map(NativeRuntimeEvent::from).collect(),
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
    use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
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
            connection_quality: None,
        }
    }

    #[test]
    fn relay_snapshot_json_carries_schema_version() {
        let snapshot = NativeRuntimeSnapshot {
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            telemetry: sample_telemetry(),
            native_events: Vec::<NativeRuntimeEvent>::new(),
            connection_quality: None,
        };
        let value = serde_json::to_value(&snapshot).expect("serialize relay snapshot");
        assert_eq!(value["schemaVersion"], serde_json::json!(3));
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
