use android_support::{drain_warp_events, NativeEventRecord};
use jni::sys::jlong;
use jni::{EnvUnowned, Outcome};
use ripdpi_warp_core::{WarpRuntime, WarpTelemetry};
use serde::Serialize;

use crate::registry;

const IDLE_TELEMETRY_JSON: &str =
    "{\"source\":\"warp\",\"schemaVersion\":1,\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}";

/// Runtime-telemetry payload schema version emitted on every snapshot.
/// Additive forward marker — consumers do not branch on it yet. See
/// `docs/architecture/TELEMETRY_CONTRACT.md`.
const SNAPSHOT_SCHEMA_VERSION: u32 = 1;

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
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct WarpNativeRuntimeSnapshot {
    schema_version: u32,
    #[serde(flatten)]
    telemetry: WarpTelemetry,
    native_events: Vec<NativeRuntimeEvent>,
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
        }
    }
}

fn snapshot_from_telemetry(telemetry: WarpTelemetry) -> WarpNativeRuntimeSnapshot {
    WarpNativeRuntimeSnapshot {
        schema_version: SNAPSHOT_SCHEMA_VERSION,
        telemetry,
        native_events: drain_warp_events().into_iter().map(NativeRuntimeEvent::from).collect(),
    }
}

fn serialize(session: &WarpRuntime) -> Option<String> {
    serde_json::to_string(&snapshot_from_telemetry(session.telemetry())).ok()
}

pub(crate) fn poll(mut env: EnvUnowned<'_>, handle: jlong) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload = registry::get(handle)
                .and_then(|session| serialize(session.as_ref()))
                .unwrap_or_else(|| IDLE_TELEMETRY_JSON.to_string());
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
    use tracing_subscriber::prelude::*;

    fn sample_telemetry() -> WarpTelemetry {
        WarpTelemetry {
            source: "warp",
            state: "running".to_string(),
            health: "healthy".to_string(),
            active_sessions: 1,
            total_sessions: 1,
            listener_address: Some("127.0.0.1:1080".to_string()),
            upstream_address: Some("warp.example.test:2408".to_string()),
            upstream_rtt_ms: Some(25),
            profile_id: Some("warp-profile".to_string()),
            last_error: None,
            captured_at: 0,
        }
    }

    fn snapshot_from_buffers(buffers: &EventRingBuffers) -> WarpNativeRuntimeSnapshot {
        WarpNativeRuntimeSnapshot {
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            telemetry: sample_telemetry(),
            native_events: buffers.drain_warp().into_iter().map(NativeRuntimeEvent::from).collect(),
        }
    }

    #[test]
    fn warp_snapshot_json_carries_schema_version() {
        let snapshot = WarpNativeRuntimeSnapshot {
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            telemetry: sample_telemetry(),
            native_events: Vec::new(),
        };
        let value = serde_json::to_value(&snapshot).expect("serialize warp snapshot");
        assert_eq!(value["schemaVersion"], serde_json::json!(1));
    }

    #[test]
    fn warp_idle_telemetry_json_carries_schema_version() {
        // The unknown-handle fallback in `poll` returns IDLE_TELEMETRY_JSON
        // verbatim instead of going through `snapshot_from_telemetry`, so the
        // hand-written constant needs its own schemaVersion guard. See
        // docs/architecture/TELEMETRY_CONTRACT.md.
        let value: serde_json::Value = serde_json::from_str(IDLE_TELEMETRY_JSON).expect("idle telemetry json is valid");
        assert_eq!(value["schemaVersion"], serde_json::json!(SNAPSHOT_SCHEMA_VERSION));
        assert_eq!(value["source"], serde_json::json!("warp"));
    }

    #[test]
    fn warp_snapshot_drains_runtime_ready_event_once() {
        let buffers = EventRingBuffers::new(RingConfig::default());
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(
                ring = "warp",
                subsystem = "warp",
                source = "warp",
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
    fn warp_snapshot_drains_runtime_stopped_event() {
        let buffers = EventRingBuffers::new(RingConfig::default());
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(
                ring = "warp",
                subsystem = "warp",
                source = "warp",
                kind = "runtime_stopped",
                "listener stopped"
            );

            let snapshot = snapshot_from_buffers(&buffers);
            assert_eq!(snapshot.native_events.len(), 1);
            assert_eq!(snapshot.native_events[0].kind.as_deref(), Some("runtime_stopped"));
        });
    }
}
