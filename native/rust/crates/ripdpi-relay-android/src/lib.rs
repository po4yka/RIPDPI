use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use android_support::{clear_relay_events, drain_relay_events, init_android_logging, NativeEventRecord, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};
use once_cell::sync::Lazy;
use ripdpi_apps_script_core::{AppsScriptRuntimeConfig, RelayRuntime as AppsScriptRelayRuntime};
use ripdpi_relay_core::{RelayRuntime as StandardRelayRuntime, ResolvedRelayRuntimeConfig};
use serde::Serialize;

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, SessionRuntime>>> = Lazy::new(|| Mutex::new(HashMap::new()));

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
struct NativeRuntimeSnapshot<T> {
    #[serde(flatten)]
    telemetry: T,
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

fn snapshot_from_telemetry<T>(telemetry: T) -> NativeRuntimeSnapshot<T> {
    NativeRuntimeSnapshot {
        telemetry,
        native_events: drain_relay_events().into_iter().map(NativeRuntimeEvent::from).collect(),
    }
}

fn serialize_standard_telemetry(session: &StandardRelayRuntime) -> Option<String> {
    serde_json::to_string(&snapshot_from_telemetry(session.telemetry())).ok()
}

fn serialize_apps_script_telemetry(session: &AppsScriptRelayRuntime) -> Option<String> {
    serde_json::to_string(&snapshot_from_telemetry(session.telemetry())).ok()
}

#[derive(Clone)]
enum SessionRuntime {
    Standard(Arc<StandardRelayRuntime>),
    AppsScript(Arc<AppsScriptRelayRuntime>),
}

impl SessionRuntime {
    async fn run(&self) -> std::io::Result<()> {
        match self {
            Self::Standard(session) => session.clone().run().await,
            Self::AppsScript(session) => session.clone().run().await,
        }
    }

    fn stop(&self) {
        match self {
            Self::Standard(session) => session.stop(),
            Self::AppsScript(session) => session.stop(),
        }
    }

    fn telemetry_json(&self) -> Option<String> {
        match self {
            Self::Standard(session) => serialize_standard_telemetry(session.as_ref()),
            Self::AppsScript(session) => serialize_apps_script_telemetry(session.as_ref()),
        }
    }
}

fn create_session(config_json: &str) -> Option<SessionRuntime> {
    if relay_kind(config_json).as_deref() == Some("google_apps_script") {
        let config = AppsScriptRuntimeConfig::from_json(config_json).ok()?;
        return Some(SessionRuntime::AppsScript(AppsScriptRelayRuntime::new(config)));
    }
    let config = serde_json::from_str::<ResolvedRelayRuntimeConfig>(config_json).ok()?;
    Some(SessionRuntime::Standard(StandardRelayRuntime::new(config)))
}

fn relay_kind(config_json: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(config_json).ok()?;
    value.get("kind")?.as_str().map(ToOwned::to_owned)
}

/// # Safety
/// Called once by the JVM at library load; `vm` is a valid `*mut JavaVM` that outlives this call.
/// The function must not panic across the FFI boundary; panic-hook installation and signal masking
/// are handled inside the `catch_unwind` wrapper.
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-relay-native");
        android_support::install_panic_hook();
        let _ = rustls::crypto::ring::default_provider().install_default();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniCreate(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = config_json.mutf8_chars(env)?.to_str().into_owned();
            let Some(session) = create_session(&config_json) else {
                return Ok(0);
            };
            let handle = {
                let mut next = NEXT_HANDLE.lock().expect("handle mutex");
                let value = *next;
                *next += 1;
                value
            };
            clear_relay_events();
            SESSIONS.lock().expect("session mutex").insert(handle, session);
            Ok(jlong::try_from(handle).unwrap_or(0))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStart(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    let Some(session) = session_from_handle(handle) else {
        return 1;
    };
    match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .and_then(|runtime| runtime.block_on(session.run()).map(|_| ()))
    {
        Ok(()) => 0,
        Err(_) => 2,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Some(session) = session_from_handle(handle) {
        session.stop();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniPollTelemetry(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload =
                session_from_handle(handle).and_then(|session| session.telemetry_json()).unwrap_or_else(|| {
                    "{\"source\":\"relay\",\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}".to_string()
                });
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().expect("session mutex").remove(&handle);
    }
}

fn session_from_handle(handle: jlong) -> Option<SessionRuntime> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
    use ripdpi_relay_core::RelayTelemetry as StandardRelayTelemetry;
    use tracing_subscriber::prelude::*;

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
            fallback_mode: None,
            last_handshake_error: None,
            chain_entry_state: None,
            chain_exit_state: None,
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: Some("chrome_stable".to_string()),
            tls_profile_catalog_version: Some("v1".to_string()),
            morph_policy_id: None,
            quic_migration_status: None,
            quic_migration_reason: None,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            captured_at: 0,
        }
    }

    fn snapshot_from_buffers(buffers: &EventRingBuffers) -> NativeRuntimeSnapshot<StandardRelayTelemetry> {
        NativeRuntimeSnapshot {
            telemetry: sample_telemetry(),
            native_events: buffers.drain_relay().into_iter().map(NativeRuntimeEvent::from).collect(),
        }
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
