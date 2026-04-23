mod provisioning;
mod vpn_protect;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use android_support::{clear_warp_events, drain_warp_events, init_android_logging, NativeEventRecord, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::refs::Global;
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};
use once_cell::sync::Lazy;
use ripdpi_warp_core::{ResolvedWarpRuntimeConfig, WarpEndpointProbeRequest, WarpRuntime, WarpTelemetry};
use serde::Serialize;

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, Arc<WarpRuntime>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

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
        telemetry,
        native_events: drain_warp_events().into_iter().map(NativeRuntimeEvent::from).collect(),
    }
}

fn serialize_telemetry(session: &WarpRuntime) -> Option<String> {
    serde_json::to_string(&snapshot_from_telemetry(session.telemetry())).ok()
}

/// # Safety
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-warp-native");
        android_support::install_panic_hook();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniCreate(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = config_json.mutf8_chars(env)?.to_str().into_owned();
            let Ok(config) = serde_json::from_str::<ResolvedWarpRuntimeConfig>(&config_json) else {
                return Ok(0);
            };
            let handle = {
                let mut next = NEXT_HANDLE.lock().expect("handle mutex");
                let value = *next;
                *next += 1;
                value
            };
            clear_warp_events();
            SESSIONS.lock().expect("session mutex").insert(handle, WarpRuntime::new(config));
            Ok(jlong::try_from(handle).unwrap_or(0))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStart(
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
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Some(session) = session_from_handle(handle) {
        session.stop();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniPollTelemetry(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload =
                session_from_handle(handle).and_then(|session| serialize_telemetry(session.as_ref())).unwrap_or_else(
                    || "{\"source\":\"warp\",\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}".to_string(),
                );
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().expect("session mutex").remove(&handle);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniExecuteProvisioning(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    request_json: JString,
) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = provisioning::execute(&request_json).unwrap_or_else(|error| {
                serde_json::json!({
                    "error": error.to_string(),
                })
                .to_string()
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
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniProbeEndpoint(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    request_json: JString,
) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let Ok(request) = serde_json::from_str::<WarpEndpointProbeRequest>(&request_json) else {
                return Ok(std::ptr::null_mut());
            };
            let payload = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .ok()
                .and_then(|runtime| runtime.block_on(ripdpi_warp_core::probe_endpoint(request)).ok())
                .and_then(|result| serde_json::to_string(&result).ok());
            match payload {
                Some(value) => Ok(env.new_string(value)?.into_raw()),
                None => Ok(std::ptr::null_mut()),
            }
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

// @JvmStatic in a Kotlin companion object generates the JNI symbol on the
// class itself (without $Companion / 00024Companion), not on the companion.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniRegisterVpnProtect(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    vpn_service: JObject,
) {
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        let vm = env.get_java_vm()?;
        let global_ref: Global<JObject<'static>> = env.new_global_ref(vpn_service)?;
        vpn_protect::register_vpn_protect(&vm, global_ref);
        Ok(())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
) {
    vpn_protect::unregister_vpn_protect();
}

fn session_from_handle(handle: jlong) -> Option<Arc<WarpRuntime>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
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
            telemetry: sample_telemetry(),
            native_events: buffers.drain_warp().into_iter().map(NativeRuntimeEvent::from).collect(),
        }
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
