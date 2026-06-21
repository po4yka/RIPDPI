//! `jniPollTelemetry` body: serialize the runtime's [`AmneziaWgTelemetry`]
//! snapshot (plus an additive `schemaVersion` marker) to a JSON `jstring` the
//! Kotlin `NativeRuntimeSnapshot` parser consumes. Non-blocking; returns the
//! idle constant for an unknown handle.

use jni::sys::jlong;
use jni::{EnvUnowned, Outcome};
use ripdpi_warp_core::{AmneziaWgRuntime, AmneziaWgTelemetry};
use serde::Serialize;

use crate::registry;

/// Runtime-telemetry payload schema version emitted on every snapshot.
/// Additive forward marker -- consumers do not branch on it yet.
const SNAPSHOT_SCHEMA_VERSION: u32 = 1;

const IDLE_TELEMETRY_JSON: &str =
    "{\"source\":\"amneziawg\",\"schemaVersion\":1,\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AmneziaWgNativeRuntimeSnapshot {
    schema_version: u32,
    #[serde(flatten)]
    telemetry: AmneziaWgTelemetry,
}

fn serialize(session: &AmneziaWgRuntime) -> Option<String> {
    let snapshot =
        AmneziaWgNativeRuntimeSnapshot { schema_version: SNAPSHOT_SCHEMA_VERSION, telemetry: session.telemetry() };
    serde_json::to_string(&snapshot).ok()
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

    fn sample_telemetry() -> AmneziaWgTelemetry {
        AmneziaWgTelemetry {
            source: "amneziawg",
            state: "running".to_string(),
            health: "running".to_string(),
            active_sessions: 1,
            total_sessions: 2,
            ws_carrier_handshakes: 3,
            ws_carrier_handshake_failures: 1,
            listener_address: Some("127.0.0.1:11090".to_string()),
            upstream_address: Some("vpn.example.test:51820".to_string()),
            profile_id: Some("awg-profile".to_string()),
            last_error: None,
            captured_at: 0,
        }
    }

    #[test]
    fn snapshot_json_carries_schema_version_and_flattened_telemetry() {
        let snapshot =
            AmneziaWgNativeRuntimeSnapshot { schema_version: SNAPSHOT_SCHEMA_VERSION, telemetry: sample_telemetry() };
        let value = serde_json::to_value(&snapshot).expect("serialize snapshot");
        assert_eq!(value["schemaVersion"], serde_json::json!(1));
        assert_eq!(value["source"], serde_json::json!("amneziawg"));
        assert_eq!(value["state"], serde_json::json!("running"));
        assert_eq!(value["listenerAddress"], serde_json::json!("127.0.0.1:11090"));
        assert!(value.get("upstreamAddress").is_none(), "AWG endpoint field must not be serialized");
        assert_eq!(value["profileId"], serde_json::json!("awg-profile"));
        // The WG-over-WebSocket carrier counters are surfaced through the same
        // flattened telemetry channel the Kotlin NativeRuntimeSnapshot consumes.
        assert_eq!(value["wsCarrierHandshakes"], serde_json::json!(3));
        assert_eq!(value["wsCarrierHandshakeFailures"], serde_json::json!(1));
    }

    #[test]
    fn snapshot_json_does_not_expose_awg_endpoint_host_or_port() {
        let snapshot =
            AmneziaWgNativeRuntimeSnapshot { schema_version: SNAPSHOT_SCHEMA_VERSION, telemetry: sample_telemetry() };
        let json = serde_json::to_string(&snapshot).expect("serialize snapshot");
        assert!(!json.contains("vpn.example.test"), "AWG endpoint host leaked in telemetry JSON: {json}");
        assert!(!json.contains("51820"), "AWG endpoint port leaked in telemetry JSON: {json}");
    }

    #[test]
    fn idle_telemetry_json_is_valid_and_marks_schema_version() {
        let value: serde_json::Value = serde_json::from_str(IDLE_TELEMETRY_JSON).expect("idle telemetry json is valid");
        assert_eq!(value["schemaVersion"], serde_json::json!(SNAPSHOT_SCHEMA_VERSION));
        assert_eq!(value["source"], serde_json::json!("amneziawg"));
        assert_eq!(value["state"], serde_json::json!("idle"));
    }
}
