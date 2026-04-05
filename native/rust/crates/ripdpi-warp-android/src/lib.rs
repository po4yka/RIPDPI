use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use android_support::{init_android_logging, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, Arc<WarpSession>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WarpConfig {
    enabled: bool,
    route_mode: String,
    route_hosts: String,
    built_in_rules_enabled: bool,
    endpoint_selection_mode: String,
    manual_endpoint: WarpManualEndpoint,
    scanner_enabled: bool,
    scanner_parallelism: i32,
    scanner_max_rtt_ms: i32,
    amnezia: WarpAmneziaConfig,
    local_socks_host: String,
    local_socks_port: i32,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct WarpManualEndpoint {
    host: String,
    ipv4: String,
    ipv6: String,
    port: i32,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct WarpAmneziaConfig {
    enabled: bool,
    jc: i32,
    jmin: i32,
    jmax: i32,
    h1: i64,
    h2: i64,
    h3: i64,
    h4: i64,
    s1: i32,
    s2: i32,
    s3: i32,
    s4: i32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct WarpTelemetry {
    source: &'static str,
    state: String,
    health: String,
    listener_address: Option<String>,
    last_error: Option<String>,
    captured_at: u64,
}

struct WarpSession {
    config: WarpConfig,
    running: AtomicBool,
    stop_requested: AtomicBool,
}

impl WarpSession {
    fn telemetry(&self) -> WarpTelemetry {
        WarpTelemetry {
            source: "warp",
            state: if self.running.load(Ordering::SeqCst) {
                "running".to_string()
            } else {
                "idle".to_string()
            },
            health: if self.running.load(Ordering::SeqCst) {
                "running".to_string()
            } else {
                "idle".to_string()
            },
            listener_address: Some(format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port)),
            last_error: None,
            captured_at: now_ms(),
        }
    }
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
            let config_json: String = env.get_string(&config_json)?.into();
            let Ok(config) = serde_json::from_str::<WarpConfig>(&config_json) else {
                return Ok(0);
            };
            let handle = {
                let mut next = NEXT_HANDLE.lock().expect("handle mutex");
                let value = *next;
                *next += 1;
                value
            };
            SESSIONS.lock().expect("session mutex").insert(
                handle,
                Arc::new(WarpSession {
                    config,
                    running: AtomicBool::new(false),
                    stop_requested: AtomicBool::new(false),
                }),
            );
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
    session.running.store(true, Ordering::SeqCst);
    while !session.stop_requested.load(Ordering::SeqCst) {
        thread::sleep(Duration::from_millis(100));
    }
    session.running.store(false, Ordering::SeqCst);
    0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Some(session) = session_from_handle(handle) {
        session.stop_requested.store(true, Ordering::SeqCst);
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
            let payload = session_from_handle(handle)
                .and_then(|session| serde_json::to_string(&session.telemetry()).ok())
                .unwrap_or_else(|| "{\"source\":\"warp\",\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}".to_string());
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

fn session_from_handle(handle: jlong) -> Option<Arc<WarpSession>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
