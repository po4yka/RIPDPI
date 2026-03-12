use std::collections::VecDeque;
use std::net::IpAddr;
use std::os::fd::AsRawFd;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use android_support::{
    init_android_logging, throw_illegal_argument, throw_illegal_state, throw_io_exception, throw_runtime_exception,
    HandleRegistry, JNI_VERSION,
};
use ciadpi_config::{QuicFakeProfile, QuicInitialMode, RuntimeConfig, TcpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use ripdpi_monitor::{MonitorSession, ScanRequest};
use ripdpi_proxy_config::{
    parse_proxy_config_json as shared_parse_proxy_config_json,
    runtime_config_from_command_line as shared_runtime_config_from_command_line,
    runtime_config_from_payload as shared_runtime_config_from_payload,
    runtime_config_from_ui as shared_runtime_config_from_ui,
    ProxyConfigPayload, ProxyConfigError, ProxyUiConfig, ProxyUiTcpChainStep, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SNI_MODE_RANDOMIZED, QUIC_FAKE_PROFILE_DISABLED,
};
use ripdpi_runtime::{clear_runtime_telemetry, install_runtime_telemetry, process, runtime, RuntimeTelemetrySink};
use serde::Serialize;

const HOSTS_DISABLE: &str = "disable";
const HOSTS_BLACKLIST: &str = "blacklist";
const HOSTS_WHITELIST: &str = "whitelist";

static SESSIONS: once_cell::sync::Lazy<HandleRegistry<ProxySession>> = once_cell::sync::Lazy::new(HandleRegistry::new);
static DIAGNOSTIC_SESSIONS: once_cell::sync::Lazy<HandleRegistry<MonitorSession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

#[derive(Debug, thiserror::Error)]
enum JniProxyError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("{0}")]
    InvalidArgument(String),

    #[error("{0}")]
    #[allow(dead_code)]
    IllegalState(&'static str),

    #[error("I/O failure: {0}")]
    Io(#[from] std::io::Error),

    #[error("{0}")]
    Serialization(#[from] serde_json::Error),
}

impl JniProxyError {
    fn throw(self, env: &mut JNIEnv) {
        let (class, msg) = match &self {
            Self::InvalidConfig(_) | Self::InvalidArgument(_) => {
                ("java/lang/IllegalArgumentException", self.to_string())
            }
            Self::IllegalState(_) => ("java/lang/IllegalStateException", self.to_string()),
            Self::Io(_) => ("java/io/IOException", self.to_string()),
            Self::Serialization(_) => ("java/lang/RuntimeException", self.to_string()),
        };
        let _ = env.throw_new(class, &msg);
    }
}

fn extract_panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    payload
        .downcast_ref::<String>()
        .map(String::as_str)
        .or_else(|| payload.downcast_ref::<&str>().copied())
        .unwrap_or("unknown panic")
        .to_string()
}

struct ProxySession {
    config: RuntimeConfig,
    telemetry: Arc<ProxyTelemetryState>,
    state: Mutex<ProxySessionState>,
}

enum ProxySessionState {
    Idle,
    Running { listener_fd: i32 },
}

/// Returns true for I/O errors caused by transient network conditions
/// (e.g., Doze mode, network handover, airplane mode toggle).
/// These are separated from permanent failures in telemetry so the UI
/// can distinguish recoverable connectivity blips from real bugs.
fn is_transient_network_error(error: &std::io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(libc::ENETUNREACH | libc::EHOSTUNREACH | libc::ETIMEDOUT | libc::ECONNREFUSED | libc::ECONNRESET
            | libc::ECONNABORTED | libc::ENETDOWN | libc::EPIPE)
    )
}

const MAX_PROXY_EVENTS: usize = 128;

fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeRuntimeEvent {
    source: String,
    level: String,
    message: String,
    created_at: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TunnelStatsSnapshot {
    tx_packets: u64,
    tx_bytes: u64,
    rx_packets: u64,
    rx_bytes: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeRuntimeSnapshot {
    source: String,
    state: String,
    health: String,
    active_sessions: u64,
    total_sessions: u64,
    total_errors: u64,
    network_errors: u64,
    route_changes: u64,
    last_route_group: Option<i32>,
    listener_address: Option<String>,
    upstream_address: Option<String>,
    last_target: Option<String>,
    last_host: Option<String>,
    last_error: Option<String>,
    autolearn_enabled: bool,
    learned_host_count: i32,
    penalized_host_count: i32,
    last_autolearn_host: Option<String>,
    last_autolearn_group: Option<i32>,
    last_autolearn_action: Option<String>,
    tunnel_stats: TunnelStatsSnapshot,
    native_events: Vec<NativeRuntimeEvent>,
    captured_at: u64,
}

struct TelemetryStrings {
    listener_address: Option<String>,
    upstream_address: Option<String>,
    last_target: Option<String>,
    last_host: Option<String>,
    last_error: Option<String>,
    last_autolearn_host: Option<String>,
    last_autolearn_action: Option<String>,
    events: VecDeque<NativeRuntimeEvent>,
}

struct ProxyTelemetryState {
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    network_errors: AtomicU64,
    route_changes: AtomicU64,
    last_route_group: AtomicI64,
    autolearn_enabled: AtomicBool,
    learned_host_count: AtomicU64,
    penalized_host_count: AtomicU64,
    last_autolearn_group: AtomicI64,
    strings: Mutex<TelemetryStrings>,
}

impl ProxyTelemetryState {
    fn new() -> Self {
        Self {
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            network_errors: AtomicU64::new(0),
            route_changes: AtomicU64::new(0),
            last_route_group: AtomicI64::new(-1),
            autolearn_enabled: AtomicBool::new(false),
            learned_host_count: AtomicU64::new(0),
            penalized_host_count: AtomicU64::new(0),
            last_autolearn_group: AtomicI64::new(-1),
            strings: Mutex::new(TelemetryStrings {
                listener_address: None,
                upstream_address: None,
                last_target: None,
                last_host: None,
                last_error: None,
                last_autolearn_host: None,
                last_autolearn_action: None,
                events: VecDeque::with_capacity(MAX_PROXY_EVENTS),
            }),
        }
    }

    fn mark_running(&self, bind_addr: String, max_clients: usize, group_count: usize) {
        self.running.store(true, Ordering::Relaxed);
        let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
        log::info!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.listener_address = Some(bind_addr);
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    fn mark_stopped(&self) {
        self.running.store(false, Ordering::Relaxed);
        self.active_sessions.store(0, Ordering::Relaxed);
        let message = "listener stopped".to_string();
        log::info!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    fn on_client_accepted(&self) {
        self.active_sessions.fetch_add(1, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
    }

    fn on_client_finished(&self) {
        self.active_sessions
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| Some(value.saturating_sub(1)))
            .ok();
    }

    fn on_client_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        let message = format!("client error: {error}");
        log::warn!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_error = Some(error);
            Self::push_event_to(&mut guard.events, "proxy", "warn", message);
        }
    }

    fn on_client_io_error(&self, error: &std::io::Error) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if is_transient_network_error(error) {
            self.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        let error_str = error.to_string();
        let message = format!("client error: {error_str}");
        log::warn!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_error = Some(error_str);
            Self::push_event_to(&mut guard.events, "proxy", "warn", message);
        }
    }

    fn on_route_selected(&self, target: String, group_index: usize, host: Option<String>, phase: &str) {
        self.last_route_group.store(group_index.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route selected phase={} group={} target={} host={}",
            phase,
            group_index,
            target,
            host.as_deref().unwrap_or("<none>")
        );
        log::info!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_target = Some(target);
            guard.last_host = host;
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    fn on_route_advanced(
        &self,
        target: String,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<String>,
    ) {
        self.route_changes.fetch_add(1, Ordering::Relaxed);
        self.last_route_group.store(to_group.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route advanced target={} from={} to={} trigger={} host={}",
            target,
            from_group,
            to_group,
            trigger,
            host.as_deref().unwrap_or("<none>")
        );
        log::warn!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_target = Some(target);
            guard.last_host = host;
            Self::push_event_to(&mut guard.events, "proxy", "warn", message);
        }
    }

    fn set_autolearn_state(&self, enabled: bool, learned_host_count: usize, penalized_host_count: usize) {
        self.autolearn_enabled.store(enabled, Ordering::Relaxed);
        self.learned_host_count.store(learned_host_count as u64, Ordering::Relaxed);
        self.penalized_host_count.store(penalized_host_count as u64, Ordering::Relaxed);
    }

    fn on_autolearn_event(&self, action: &'static str, host: Option<String>, group_index: Option<usize>) {
        self.last_autolearn_group.store(
            group_index.and_then(|value| i64::try_from(value).ok()).unwrap_or(-1),
            Ordering::Relaxed,
        );
        let level = if action == "group_penalized" { "warn" } else { "info" };
        let message = format!(
            "autolearn action={} host={} group={}",
            action,
            host.as_deref().unwrap_or("<none>"),
            group_index
                .map(|value| value.to_string())
                .unwrap_or_else(|| "<none>".to_string())
        );
        match level {
            "warn" => log::warn!("{message}"),
            _ => log::info!("{message}"),
        }
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_autolearn_host = host;
            guard.last_autolearn_action = Some(action.to_string());
            Self::push_event_to(&mut guard.events, "autolearn", level, message);
        }
    }

    fn snapshot(&self) -> NativeRuntimeSnapshot {
        let (listener_address, upstream_address, last_target, last_host, last_error, last_autolearn_host, last_autolearn_action, native_events) =
            if let Ok(mut guard) = self.strings.lock() {
                (
                    guard.listener_address.clone(),
                    guard.upstream_address.clone(),
                    guard.last_target.clone(),
                    guard.last_host.clone(),
                    guard.last_error.clone(),
                    guard.last_autolearn_host.clone(),
                    guard.last_autolearn_action.clone(),
                    guard.events.drain(..).collect(),
                )
            } else {
                (None, None, None, None, None, None, None, Vec::new())
            };

        NativeRuntimeSnapshot {
            source: "proxy".to_string(),
            state: if self.running.load(Ordering::Relaxed) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::Relaxed) {
                if self.total_errors.load(Ordering::Relaxed) == 0 {
                    "healthy".to_string()
                } else {
                    "degraded".to_string()
                }
            } else {
                "idle".to_string()
            },
            active_sessions: self.active_sessions.load(Ordering::Relaxed),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            network_errors: self.network_errors.load(Ordering::Relaxed),
            route_changes: self.route_changes.load(Ordering::Relaxed),
            last_route_group: match self.last_route_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            listener_address,
            upstream_address,
            last_target,
            last_host,
            last_error,
            autolearn_enabled: self.autolearn_enabled.load(Ordering::Relaxed),
            learned_host_count: i32::try_from(self.learned_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            penalized_host_count: i32::try_from(self.penalized_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            last_autolearn_host,
            last_autolearn_group: match self.last_autolearn_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_autolearn_action,
            tunnel_stats: TunnelStatsSnapshot { tx_packets: 0, tx_bytes: 0, rx_packets: 0, rx_bytes: 0 },
            native_events,
            captured_at: now_ms(),
        }
    }

    fn clear_last_error(&self) {
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_error = None;
        }
    }

    fn push_event(&self, source: &str, level: &str, message: String) {
        match level {
            "warn" => log::warn!("{message}"),
            "error" => log::error!("{message}"),
            _ => log::info!("{message}"),
        }
        if let Ok(mut guard) = self.strings.lock() {
            Self::push_event_to(&mut guard.events, source, level, message);
        }
    }

    fn push_event_to(events: &mut VecDeque<NativeRuntimeEvent>, source: &str, level: &str, message: String) {
        if events.len() >= MAX_PROXY_EVENTS {
            events.pop_front();
        }
        events.push_back(NativeRuntimeEvent {
            source: source.to_string(),
            level: level.to_string(),
            message,
            created_at: now_ms(),
        });
    }
}

struct ProxyTelemetryObserver {
    state: Arc<ProxyTelemetryState>,
}

impl RuntimeTelemetrySink for ProxyTelemetryObserver {
    fn on_listener_started(&self, bind_addr: std::net::SocketAddr, max_clients: usize, group_count: usize) {
        self.state.mark_running(bind_addr.to_string(), max_clients, group_count);
    }

    fn on_listener_stopped(&self) {
        self.state.mark_stopped();
    }

    fn on_client_accepted(&self) {
        self.state.on_client_accepted();
    }

    fn on_client_finished(&self) {
        self.state.on_client_finished();
    }

    fn on_client_error(&self, error: &std::io::Error) {
        self.state.on_client_io_error(error);
    }

    fn on_route_selected(
        &self,
        target: std::net::SocketAddr,
        group_index: usize,
        host: Option<&str>,
        phase: &'static str,
    ) {
        self.state.on_route_selected(target.to_string(), group_index, host.map(ToOwned::to_owned), phase);
    }

    fn on_route_advanced(
        &self,
        target: std::net::SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        self.state.on_route_advanced(target.to_string(), from_group, to_group, trigger, host.map(ToOwned::to_owned));
    }

    fn on_host_autolearn_state(&self, enabled: bool, learned_host_count: usize, penalized_host_count: usize) {
        self.state.set_autolearn_state(enabled, learned_host_count, penalized_host_count);
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        self.state
            .on_autolearn_event(action, host.map(ToOwned::to_owned), group_index);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-native");
    JNI_VERSION
}

fn proxy_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session creation panicked: {msg}"));
            0
        },
    )
}

fn proxy_start_entry(mut env: JNIEnv, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session start panicked: {msg}"));
            libc::EINVAL
        },
    )
}

fn proxy_stop_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle)))
        .map_err(|panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session stop panicked: {msg}"));
        });
}

fn proxy_poll_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| poll_proxy_telemetry(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy telemetry polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

fn proxy_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle)))
        .map_err(|panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session destroy panicked: {msg}"));
        });
}

macro_rules! export_diagnostics_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(env: JNIEnv, _thiz: JObject, $($arg: $arg_ty),*) -> $ret {
            $entry(env, $($arg),*)
        }
    };
}

fn diagnostics_create_entry(mut env: JNIEnv) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        DIAGNOSTIC_SESSIONS.insert(MonitorSession::new()) as jlong
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics session creation panicked: {msg}"));
        0
    })
}

fn diagnostics_start_scan_entry(mut env: JNIEnv, handle: jlong, request_json: JString, session_id: JString) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_diagnostics_scan(&mut env, handle, request_json, session_id);
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics scan start panicked: {msg}"));
    });
}

fn diagnostics_cancel_scan_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        diagnostics_session(&mut env, handle)?.cancel_scan();
        Some(())
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics cancel panicked: {msg}"));
    });
}

fn diagnostics_poll_progress_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, ripdpi_monitor::MonitorSession::poll_progress_json)
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics progress polling panicked: {msg}"));
        std::ptr::null_mut()
    })
}

fn diagnostics_take_report_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, ripdpi_monitor::MonitorSession::take_report_json)
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics report polling panicked: {msg}"));
        std::ptr::null_mut()
    })
}

fn diagnostics_poll_passive_events_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, ripdpi_monitor::MonitorSession::poll_passive_events_json)
    }))
    .unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics passive polling panicked: {msg}"));
        std::ptr::null_mut()
    })
}

fn diagnostics_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_diagnostics_session(&mut env, handle)))
        .map_err(|panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics session destroy panicked: {msg}"));
        });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate(
    env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    proxy_create_entry(env, config_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    proxy_start_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    proxy_stop_entry(env, handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jstring {
    proxy_poll_telemetry_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    proxy_destroy_entry(env, handle);
}

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate,
    (),
    jlong,
    diagnostics_create_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan,
    (handle: jlong, request_json: JString, session_id: JString),
    (),
    diagnostics_start_scan_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan,
    (handle: jlong),
    (),
    diagnostics_cancel_scan_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress,
    (handle: jlong),
    jstring,
    diagnostics_poll_progress_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport,
    (handle: jlong),
    jstring,
    diagnostics_take_report_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents,
    (handle: jlong),
    jstring,
    diagnostics_poll_passive_events_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    diagnostics_destroy_entry
);

fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid proxy config payload");
            return 0;
        }
    };

    let payload = match parse_proxy_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };

    let config = match runtime_config_from_payload(payload) {
        Ok(config) => config,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };

    if let Err(err) = runtime::create_listener(&config) {
        JniProxyError::Io(err).throw(env);
        return 0;
    }

    let autolearn_enabled = config.host_autolearn_enabled;
    let telemetry = Arc::new(ProxyTelemetryState::new());
    telemetry.set_autolearn_state(autolearn_enabled, 0, 0);

    SESSIONS.insert(ProxySession { config, telemetry, state: Mutex::new(ProxySessionState::Idle) }) as jlong
}

fn start_session(env: &mut JNIEnv, handle: jlong) -> jint {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return libc::EINVAL;
        }
    };

    let config = session.config.clone();
    let (listener, listener_fd) = match open_proxy_listener(&config, &session.telemetry) {
        Ok(parts) => parts,
        Err(err) => {
            throw_io_exception(env, format!("Failed to open proxy listener: {err}"));
            return libc::EINVAL;
        }
    };

    {
        let mut state = session.state.lock().expect("proxy session poisoned");
        if let Err(message) = try_mark_proxy_running(&mut state, listener_fd) {
            throw_illegal_state(env, message);
            return libc::EINVAL;
        }
    }

    session.telemetry.clear_last_error();
    install_runtime_telemetry(Arc::new(ProxyTelemetryObserver { state: session.telemetry.clone() }));
    process::prepare_embedded();
    let result = runtime::run_proxy_with_listener(config, listener);
    clear_runtime_telemetry();

    let mut state = session.state.lock().expect("proxy session poisoned");
    *state = ProxySessionState::Idle;
    if let Err(err) = &result {
        session.telemetry.on_client_error(err.to_string());
    }

    match result {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}

fn stop_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };

    let listener_fd = {
        let state = session.state.lock().expect("proxy session poisoned");
        match listener_fd_for_proxy_stop(&state) {
            Ok(listener_fd) => listener_fd,
            Err(message) => {
                throw_illegal_state(env, message);
                return;
            }
        }
    };

    process::request_shutdown();
    if let Err(err) = shutdown_proxy_listener(listener_fd) {
        throw_io_exception(env, format!("Failed to stop proxy listener: {err}"));
        session.telemetry.on_client_error(err.to_string());
    }
    session.telemetry.push_event("proxy", "info", "stop requested".to_string());
}

fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };
    let state = session.state.lock().expect("proxy session poisoned");
    if let Err(message) = ensure_proxy_destroyable(&state) {
        throw_illegal_state(env, message);
        return;
    }
    drop(state);
    let _ = remove_proxy_session(handle);
}

fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_payload(payload).map_err(proxy_config_error)
}

fn runtime_config_from_command_line(args: Vec<String>) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_command_line(args).map_err(proxy_config_error)
}

fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_ui(payload).map_err(proxy_config_error)
}

fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, JniProxyError> {
    shared_parse_proxy_config_json(json).map_err(proxy_config_error)
}

fn proxy_config_error(err: ProxyConfigError) -> JniProxyError {
    JniProxyError::InvalidConfig(err.to_string())
}

fn diagnostics_session(env: &mut JNIEnv, handle: jlong) -> Option<std::sync::Arc<MonitorSession>> {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid diagnostics handle");
            return None;
        }
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return None;
    };
    Some(session)
}

fn start_diagnostics_scan(env: &mut JNIEnv, handle: jlong, request_json: JString, session_id: JString) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    let request_json: String = match env.get_string(&request_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics request JSON");
            return;
        }
    };
    let session_id: String = match env.get_string(&session_id) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics session id");
            return;
        }
    };
    let request: ScanRequest = match serde_json::from_str(&request_json) {
        Ok(request) => request,
        Err(err) => {
            throw_illegal_argument(env, format!("Invalid diagnostics request: {err}"));
            return;
        }
    };
    if let Err(err) = session.start_scan(session_id, request) {
        throw_illegal_state(env, err);
    }
}

fn poll_diagnostics_string<F>(env: &mut JNIEnv, handle: jlong, op: F) -> jstring
where
    F: FnOnce(&MonitorSession) -> Result<Option<String>, String>,
{
    let Some(session) = diagnostics_session(env, handle) else {
        return std::ptr::null_mut();
    };
    match op(&session) {
        Ok(Some(value)) => env.new_string(value).map(jni::objects::JString::into_raw).unwrap_or(std::ptr::null_mut()),
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            throw_runtime_exception(env, err);
            std::ptr::null_mut()
        }
    }
}

fn destroy_diagnostics_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid diagnostics handle");
            return;
        }
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.remove(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return;
    };
    session.destroy();
}

fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

fn lookup_proxy_session(handle: jlong) -> Result<std::sync::Arc<ProxySession>, JniProxyError> {
    let handle =
        to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    SESSIONS
        .get(handle)
        .ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))
}

fn poll_proxy_telemetry(env: &mut JNIEnv, handle: jlong) -> jstring {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return std::ptr::null_mut();
        }
    };
    match serde_json::to_string(&session.telemetry.snapshot()) {
        Ok(value) => env.new_string(value).map(jni::objects::JString::into_raw).unwrap_or(std::ptr::null_mut()),
        Err(err) => {
            JniProxyError::Serialization(err).throw(env);
            std::ptr::null_mut()
        }
    }
}

fn remove_proxy_session(handle: jlong) -> Result<std::sync::Arc<ProxySession>, JniProxyError> {
    let handle =
        to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    SESSIONS
        .remove(handle)
        .ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))
}

fn try_mark_proxy_running(state: &mut ProxySessionState, listener_fd: i32) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle => {
            *state = ProxySessionState::Running { listener_fd };
            Ok(())
        }
        ProxySessionState::Running { .. } => Err("Proxy session is already running"),
    }
}

fn listener_fd_for_proxy_stop(state: &ProxySessionState) -> Result<i32, &'static str> {
    match *state {
        ProxySessionState::Idle => Err("Proxy session is not running"),
        ProxySessionState::Running { listener_fd } => Ok(listener_fd),
    }
}

fn ensure_proxy_destroyable(state: &ProxySessionState) -> Result<(), &'static str> {
    if matches!(*state, ProxySessionState::Running { .. }) {
        Err("Cannot destroy a running proxy session")
    } else {
        Ok(())
    }
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

fn open_proxy_listener(
    config: &RuntimeConfig,
    telemetry: &ProxyTelemetryState,
) -> Result<(std::net::TcpListener, i32), std::io::Error> {
    match runtime::create_listener(config) {
        Ok(listener) => {
            let listener_fd = listener.as_raw_fd();
            Ok((listener, listener_fd))
        }
        Err(err) => {
            telemetry.on_client_error(format!("listener open failed: {err}"));
            Err(err)
        }
    }
}

fn shutdown_proxy_listener(listener_fd: i32) -> Result<(), std::io::Error> {
    let rc = unsafe { libc::shutdown(listener_fd, libc::SHUT_RDWR) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};

    use golden_test_support::{assert_text_golden, canonicalize_json_with};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use serde_json::Value;

    fn lossy_string(max_len: usize) -> impl Strategy<Value = String> {
        vec(any::<u8>(), 0..max_len).prop_map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
    }

    fn proxy_ui_config_strategy() -> impl Strategy<Value = ProxyUiConfig> {
        let core = (lossy_string(48), -32i32..65_536i32, -16i32..4_096i32, -16i32..65_536i32, -16i32..512i32);
        let toggles = (any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>());
        let desync = (
            prop_oneof![
                Just("none".to_string()),
                Just("split".to_string()),
                Just("disorder".to_string()),
                Just("fake".to_string()),
                Just("oob".to_string()),
                Just("disoob".to_string()),
                lossy_string(16),
            ],
            proptest::option::of(lossy_string(24)),
            -64i32..64i32,
            any::<bool>(),
            -16i32..512i32,
            lossy_string(64),
            any::<u8>(),
        );
        let mutations = (
            any::<bool>(),
            any::<bool>(),
            any::<bool>(),
            any::<bool>(),
            proptest::option::of(lossy_string(24)),
            -64i32..64i32,
            any::<bool>(),
        );
        let hosts = (
            prop_oneof![
                Just(HOSTS_DISABLE.to_string()),
                Just(HOSTS_BLACKLIST.to_string()),
                Just(HOSTS_WHITELIST.to_string()),
                lossy_string(16),
            ],
            proptest::option::of(lossy_string(64)),
            any::<bool>(),
            -8i32..16i32,
            any::<bool>(),
            proptest::option::of(lossy_string(24)),
            -64i32..64i32,
        );
        let quic = (
            prop_oneof![
                Just(Some("disabled".to_string())),
                Just(Some("route".to_string())),
                Just(Some("route_and_cache".to_string())),
                Just(None),
                proptest::option::of(lossy_string(24)),
            ],
            any::<bool>(),
            any::<bool>(),
            prop_oneof![
                Just(QUIC_FAKE_PROFILE_DISABLED.to_string()),
                Just("compat_default".to_string()),
                Just("realistic_initial".to_string()),
                lossy_string(24),
            ],
            lossy_string(64),
        );
        let autolearn = (
            any::<bool>(),
            -32i64..(HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS * 4),
            0usize..2_048usize,
            proptest::option::of(lossy_string(96)),
        );

        (core, toggles, desync, mutations, hosts, quic, autolearn).prop_map(
            |(
                (ip, port, max_connections, buffer_size, default_ttl),
                (custom_ttl, no_domain, desync_http, desync_https, desync_udp),
                (desync_method, split_marker, split_position, split_at_host, fake_ttl, fake_sni, oob_char),
                (
                    host_mixed_case,
                    domain_mixed_case,
                    host_remove_spaces,
                    tls_record_split,
                    tls_record_split_marker,
                    tls_record_split_position,
                    tls_record_split_at_sni,
                ),
                (hosts_mode, hosts, tcp_fast_open, udp_fake_count, drop_sack, fake_offset_marker, fake_offset),
                (quic_initial_mode, quic_support_v1, quic_support_v2, quic_fake_profile, quic_fake_host),
                (host_autolearn_enabled, host_autolearn_penalty_ttl_secs, host_autolearn_max_hosts, host_autolearn_store_path),
            )| ProxyUiConfig {
                ip,
                port,
                max_connections,
                buffer_size,
                default_ttl,
                custom_ttl,
                no_domain,
                desync_http,
                desync_https,
                desync_udp,
                desync_method,
                split_marker,
                tcp_chain_steps: Vec::new(),
                split_position,
                split_at_host,
                fake_ttl,
                fake_sni,
                http_fake_profile: "compat_default".to_string(),
                fake_tls_use_original: false,
                fake_tls_randomize: false,
                fake_tls_dup_session_id: false,
                fake_tls_pad_encap: false,
                fake_tls_size: 0,
                fake_tls_sni_mode: default_fake_tls_sni_mode(),
                tls_fake_profile: "compat_default".to_string(),
                oob_char,
                host_mixed_case,
                domain_mixed_case,
                host_remove_spaces,
                tls_record_split,
                tls_record_split_marker,
                tls_record_split_position,
                tls_record_split_at_sni,
                hosts_mode,
                hosts,
                tcp_fast_open,
                udp_fake_count,
                udp_chain_steps: Vec::new(),
                udp_fake_profile: "compat_default".to_string(),
                drop_sack,
                fake_offset_marker,
                fake_offset,
                quic_initial_mode,
                quic_support_v1,
                quic_support_v2,
                quic_fake_profile,
                quic_fake_host,
                host_autolearn_enabled,
                host_autolearn_penalty_ttl_secs,
                host_autolearn_max_hosts,
                host_autolearn_store_path,
            },
        )
    }

    #[test]
    fn parses_ui_config_payload() {
        let payload = ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "fake".to_string(),
            split_marker: Some("host+1".to_string()),
            tcp_chain_steps: Vec::new(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: true,
            fake_tls_randomize: true,
            fake_tls_dup_session_id: true,
            fake_tls_pad_encap: true,
            fake_tls_size: 192,
            fake_tls_sni_mode: FAKE_TLS_SNI_MODE_RANDOMIZED.to_string(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route".to_string()),
            quic_support_v1: false,
            quic_support_v2: true,
            quic_fake_profile: "realistic_initial".to_string(),
            quic_fake_host: "video.example.test".to_string(),
            host_autolearn_enabled: true,
            host_autolearn_penalty_ttl_secs: 3_600,
            host_autolearn_max_hosts: 128,
            host_autolearn_store_path: Some("/tmp/host-autolearn-v1.json".to_string()),
        });

        let config = runtime_config_from_payload(payload).expect("ui config");
        assert_eq!(config.listen.listen_port, 1080);
        assert_eq!(config.groups.len(), 2);
        assert_eq!(config.quic_initial_mode, QuicInitialMode::Route);
        assert!(!config.quic_support_v1);
        assert!(config.quic_support_v2);
        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].quic_fake_host.as_deref(), Some("video.example.test"));
        assert_eq!(config.groups[0].fake_mod, FM_ORIG | FM_RAND | FM_DUPSID | FM_PADENCAP | FM_RNDSNI);
        assert_eq!(config.groups[0].fake_tls_size, 192);
        assert!(config.groups[0].fake_sni_list.is_empty());
        assert!(config.host_autolearn_enabled);
        assert_eq!(config.host_autolearn_penalty_ttl_secs, 3_600);
        assert_eq!(config.host_autolearn_max_hosts, 128);
        assert_eq!(config.host_autolearn_store_path.as_deref(), Some("/tmp/host-autolearn-v1.json"));
    }

    #[test]
    fn parses_hostfake_tcp_chain_step_payload() {
        let payload = ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: Some("1".to_string()),
            tcp_chain_steps: vec![ProxyUiTcpChainStep {
                kind: "hostfake".to_string(),
                marker: "endhost+8".to_string(),
                midhost_marker: Some("midsld".to_string()),
                fake_host_template: Some("googlevideo.com".to_string()),
                fragment_count: 0,
                min_fragment_size: 0,
                max_fragment_size: 0,
            }],
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        });

        let config = runtime_config_from_payload(payload).expect("hostfake ui config");
        let group = &config.groups[0];

        assert_eq!(group.tcp_chain.len(), 1);
        assert!(matches!(group.tcp_chain[0].kind, TcpChainStepKind::HostFake));
        assert_eq!(group.tcp_chain[0].midhost_offset, Some(ciadpi_config::OffsetExpr::marker(ciadpi_config::OffsetBase::MidSld, 0)));
        assert_eq!(group.tcp_chain[0].fake_host_template.as_deref(), Some("googlevideo.com"));
    }

    #[test]
    fn parses_tlsrandrec_tcp_chain_step_payload() {
        let payload = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "maxConnections": 512,
                "bufferSize": 16384,
                "defaultTtl": 0,
                "customTtl": false,
                "noDomain": false,
                "desyncHttp": true,
                "desyncHttps": true,
                "desyncUdp": false,
                "desyncMethod": "disorder",
                "splitMarker": "1",
                "tcpChainSteps": [
                    {
                        "kind": "tlsrandrec",
                        "marker": "sniext+4",
                        "fragmentCount": 5,
                        "minFragmentSize": 24,
                        "maxFragmentSize": 48
                    }
                ],
                "splitPosition": 1,
                "splitAtHost": false,
                "fakeTtl": 8,
                "fakeSni": "www.iana.org",
                "oobChar": 97,
                "hostMixedCase": false,
                "domainMixedCase": false,
                "hostRemoveSpaces": false,
                "tlsRecordSplit": false,
                "tlsRecordSplitMarker": null,
                "tlsRecordSplitPosition": 0,
                "tlsRecordSplitAtSni": false,
                "hostsMode": "disable",
                "hosts": null,
                "tcpFastOpen": false,
                "udpFakeCount": 0,
                "udpChainSteps": [],
                "dropSack": false,
                "fakeOffsetMarker": null,
                "fakeOffset": 0,
                "quicInitialMode": "route_and_cache",
                "quicSupportV1": true,
                "quicSupportV2": true,
                "quicFakeProfile": "disabled",
                "quicFakeHost": ""
            })
            .to_string(),
        )
        .expect("parse tlsrandrec payload");

        let config = runtime_config_from_payload(payload).expect("tlsrandrec ui config");
        let step = &config.groups[0].tcp_chain[0];

        assert!(matches!(step.kind, TcpChainStepKind::TlsRandRec));
        assert_eq!(step.fragment_count, 5);
        assert_eq!(step.min_fragment_size, 24);
        assert_eq!(step.max_fragment_size, 48);
    }

    #[test]
    fn rejects_tlsrandrec_fragment_fields_on_non_tlsrandrec_steps() {
        let payload = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "maxConnections": 512,
                "bufferSize": 16384,
                "defaultTtl": 0,
                "customTtl": false,
                "noDomain": false,
                "desyncHttp": true,
                "desyncHttps": true,
                "desyncUdp": false,
                "desyncMethod": "disorder",
                "splitMarker": "1",
                "tcpChainSteps": [
                    {
                        "kind": "split",
                        "marker": "host+1",
                        "fragmentCount": 5
                    }
                ],
                "splitPosition": 1,
                "splitAtHost": false,
                "fakeTtl": 8,
                "fakeSni": "www.iana.org",
                "oobChar": 97,
                "hostMixedCase": false,
                "domainMixedCase": false,
                "hostRemoveSpaces": false,
                "tlsRecordSplit": false,
                "tlsRecordSplitMarker": null,
                "tlsRecordSplitPosition": 0,
                "tlsRecordSplitAtSni": false,
                "hostsMode": "disable",
                "hosts": null,
                "tcpFastOpen": false,
                "udpFakeCount": 0,
                "udpChainSteps": [],
                "dropSack": false,
                "fakeOffsetMarker": null,
                "fakeOffset": 0,
                "quicInitialMode": "route_and_cache",
                "quicSupportV1": true,
                "quicSupportV2": true,
                "quicFakeProfile": "disabled",
                "quicFakeHost": ""
            })
            .to_string(),
        )
        .expect("parse invalid payload");

        let err = runtime_config_from_payload(payload).expect_err("non-tlsrandrec fragment fields should fail");

        assert!(err.to_string().contains("tlsrandrec fragment fields are only supported"));
    }

    #[test]
    fn parses_command_line_payloads_for_runtime_config() {
        let config = runtime_config_from_payload(ProxyConfigPayload::CommandLine {
            args: vec![
                "ciadpi".to_string(),
                "--ip".to_string(),
                "127.0.0.1".to_string(),
                "--port".to_string(),
                "2080".to_string(),
                "--split".to_string(),
                "1+s".to_string(),
            ],
        })
        .expect("command-line config");

        assert_eq!(config.listen.listen_ip, IpAddr::from_str("127.0.0.1").unwrap());
        assert_eq!(config.listen.listen_port, 2080);
    }

    #[test]
    fn rejects_non_runnable_command_line_payloads() {
        let err = runtime_config_from_command_line(vec!["ciadpi".to_string(), "--help".to_string()])
            .expect_err("help payload should not run");

        assert!(err.to_string().contains("runnable config"));
    }

    #[test]
    fn rejects_invalid_ui_proxy_port() {
        let err = runtime_config_from_payload(ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 0,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        }))
        .expect_err("port zero should be rejected");

        assert!(err.to_string().contains("Invalid proxy port"));
    }

    #[test]
    fn ui_payload_defaults_quic_settings_when_omitted() {
        let payload = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "maxConnections": 512,
                "bufferSize": 16384,
                "defaultTtl": 0,
                "customTtl": false,
                "noDomain": false,
                "desyncHttp": true,
                "desyncHttps": true,
                "desyncUdp": false,
                "desyncMethod": "disorder",
                "splitMarker": "host+1",
                "tcpChainSteps": [],
                "splitPosition": 1,
                "splitAtHost": false,
                "fakeTtl": 8,
                "fakeSni": "www.iana.org",
                "oobChar": 97,
                "hostMixedCase": false,
                "domainMixedCase": false,
                "hostRemoveSpaces": false,
                "tlsRecordSplit": false,
                "tlsRecordSplitMarker": null,
                "tlsRecordSplitPosition": 0,
                "tlsRecordSplitAtSni": false,
                "hostsMode": "disable",
                "hosts": null,
                "tcpFastOpen": false,
                "udpFakeCount": 0,
                "udpChainSteps": [],
                "dropSack": false,
                "fakeOffsetMarker": null,
                "fakeOffset": 0
            })
            .to_string(),
        )
        .expect("parse ui payload");

        let config = runtime_config_from_payload(payload).expect("ui config");

        assert_eq!(config.quic_initial_mode, QuicInitialMode::RouteAndCache);
        assert!(config.quic_support_v1);
        assert!(config.quic_support_v2);
        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::Disabled);
        assert_eq!(config.groups[0].quic_fake_host, None);
    }

    #[test]
    fn rejects_unknown_quic_initial_mode_in_ui_payload() {
        let err = runtime_config_from_payload(ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("bogus".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        }))
        .expect_err("unknown quic mode should be rejected");

        assert!(err.to_string().contains("Unknown quicInitialMode"));
    }

    #[test]
    fn rejects_unknown_quic_fake_profile_in_ui_payload() {
        let err = runtime_config_from_payload(ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: true,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 1,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: "bogus".to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        }))
        .expect_err("unknown quic fake profile should be rejected");

        assert!(err.to_string().contains("Unknown quicFakeProfile"));
    }

    #[test]
    fn invalid_quic_fake_host_normalizes_to_absent() {
        let payload = ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: true,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 1,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: "realistic_initial".to_string(),
            quic_fake_host: "127.0.0.1".to_string(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        });

        let config = runtime_config_from_payload(payload).expect("ui config");

        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].quic_fake_host, None);
    }

    #[test]
    fn rejects_invalid_proxy_json_payload() {
        let err = parse_proxy_config_json("{").expect_err("invalid json");

        assert!(err.to_string().contains("Invalid proxy config JSON"));
    }

    #[test]
    fn rejects_enabled_autolearn_without_store_path() {
        let err = runtime_config_from_payload(ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: Some("host+1".to_string()),
            tcp_chain_steps: Vec::new(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: true,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        }))
        .expect_err("missing autolearn path should be rejected");

        assert!(err.to_string().contains("hostAutolearnStorePath is required when hostAutolearnEnabled is true"));
    }

    #[test]
    fn open_proxy_listener_records_telemetry_when_bind_fails() {
        let busy = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind busy listener");
        let mut config = RuntimeConfig::default();
        config.listen.listen_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
        config.listen.listen_port = busy.local_addr().expect("busy listener addr").port();
        let telemetry = ProxyTelemetryState::new();

        let err = open_proxy_listener(&config, &telemetry).expect_err("listener bind should fail");
        let snapshot = telemetry.snapshot();

        assert!(err.kind() == std::io::ErrorKind::AddrInUse || err.raw_os_error().is_some());
        assert_eq!(snapshot.total_errors, 1);
        assert!(snapshot.last_error.expect("listener error").contains("listener open failed"));
        assert_eq!(snapshot.health, "idle");
    }

    #[test]
    fn shutdown_proxy_listener_rejects_invalid_descriptor() {
        let err = shutdown_proxy_listener(-1).expect_err("invalid listener fd should fail");
        assert!(err.raw_os_error().is_some());
    }

    #[test]
    fn rejects_invalid_handle() {
        assert!(to_handle(0).is_none());
        assert!(to_handle(-1).is_none());
    }

    #[test]
    fn rejects_unknown_proxy_handle_lookup() {
        let err = match lookup_proxy_session(99) {
            Ok(_) => panic!("expected unknown handle error"),
            Err(err) => err,
        };

        assert_eq!(err.to_string(), "Unknown proxy handle");
    }

    #[test]
    fn proxy_state_rejects_duplicate_start() {
        let mut state = ProxySessionState::Idle;

        try_mark_proxy_running(&mut state, 7).expect("first start");
        let err = try_mark_proxy_running(&mut state, 8).expect_err("duplicate start");

        assert_eq!(err, "Proxy session is already running");
    }

    #[test]
    fn proxy_state_rejects_stop_when_idle() {
        let err = listener_fd_for_proxy_stop(&ProxySessionState::Idle).expect_err("idle stop");

        assert_eq!(err, "Proxy session is not running");
    }

    #[test]
    fn proxy_state_rejects_destroy_when_running() {
        let err =
            ensure_proxy_destroyable(&ProxySessionState::Running { listener_fd: 9 }).expect_err("running destroy");

        assert_eq!(err, "Cannot destroy a running proxy session");
    }

    #[test]
    fn proxy_telemetry_observer_updates_snapshot_and_drains_events() {
        let state = Arc::new(ProxyTelemetryState::new());
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_listener_started(listener, 256, 3);
        observer.on_client_accepted();
        observer.on_route_selected(target, 1, Some("example.org"), "connect");
        observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
        observer.on_host_autolearn_state(true, 4, 1);
        observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
        observer.on_client_error(&std::io::Error::other("boom"));
        observer.on_client_finished();

        let first = state.snapshot();
        assert_eq!(first.state, "running");
        assert_eq!(first.health, "degraded");
        assert_eq!(first.active_sessions, 0);
        assert_eq!(first.total_sessions, 1);
        assert_eq!(first.total_errors, 1);
        assert_eq!(first.route_changes, 1);
        assert_eq!(first.last_route_group, Some(2));
        assert_eq!(first.listener_address.as_deref(), Some("127.0.0.1:1080"));
        assert_eq!(first.last_target.as_deref(), Some("203.0.113.10:443"));
        assert_eq!(first.last_host.as_deref(), Some("example.org"));
        assert_eq!(first.last_error.as_deref(), Some("boom"));
        assert!(first.autolearn_enabled);
        assert_eq!(first.learned_host_count, 4);
        assert_eq!(first.penalized_host_count, 1);
        assert_eq!(first.last_autolearn_host.as_deref(), Some("example.org"));
        assert_eq!(first.last_autolearn_group, Some(2));
        assert_eq!(first.last_autolearn_action.as_deref(), Some("host_promoted"));
        assert_eq!(first.native_events.len(), 5);

        let second = state.snapshot();
        assert!(second.native_events.is_empty());
        assert_eq!(second.total_sessions, 1);

        observer.on_listener_stopped();
        let stopped = state.snapshot();
        assert_eq!(stopped.state, "idle");
        assert_eq!(stopped.active_sessions, 0);
        assert_eq!(stopped.native_events.len(), 1);
    }

    #[test]
    fn proxy_telemetry_snapshots_match_goldens() {
        let idle = ProxyTelemetryState::new().snapshot();
        assert_proxy_snapshot_golden("proxy_idle", &idle);

        let state = Arc::new(ProxyTelemetryState::new());
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_listener_started(listener, 256, 3);
        observer.on_client_accepted();
        observer.on_route_selected(target, 1, Some("example.org"), "connect");
        observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
        observer.on_host_autolearn_state(true, 4, 1);
        observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
        observer.on_client_error(&std::io::Error::other("boom"));
        observer.on_client_finished();

        let running = state.snapshot();
        assert_proxy_snapshot_golden("proxy_running_degraded_first_poll", &running);

        let drained = state.snapshot();
        assert_proxy_snapshot_golden("proxy_running_degraded_second_poll", &drained);

        observer.on_listener_stopped();
        let stopped = state.snapshot();
        assert_proxy_snapshot_golden("proxy_stopped", &stopped);
    }

    #[test]
    fn destroy_removes_idle_proxy_session() {
        let handle = SESSIONS.insert(ProxySession {
            config: RuntimeConfig::default(),
            telemetry: Arc::new(ProxyTelemetryState::new()),
            state: Mutex::new(ProxySessionState::Idle),
        }) as jlong;

        let removed = remove_proxy_session(handle).expect("removed session");
        assert!(matches!(*removed.state.lock().expect("state lock"), ProxySessionState::Idle,));
        assert_eq!(
            match lookup_proxy_session(handle) {
                Ok(_) => panic!("expected session removal"),
                Err(err) => err.to_string(),
            },
            "Unknown proxy handle",
        );
    }

    #[derive(Clone, Copy, Debug)]
    enum ProxyStateCommand {
        EnsureCreated,
        Start,
        Stop,
        Destroy,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum ProxyModelState {
        Absent,
        Idle,
        Running,
    }

    struct ProxySessionHarness {
        active_handle: Option<jlong>,
        stale_handle: Option<jlong>,
        next_listener_fd: i32,
    }

    impl Default for ProxySessionHarness {
        fn default() -> Self {
            Self { active_handle: None, stale_handle: None, next_listener_fd: 32 }
        }
    }

    fn assert_proxy_snapshot_golden(name: &str, snapshot: &NativeRuntimeSnapshot) {
        let actual = canonicalize_json_with(
            &serde_json::to_string(snapshot).expect("serialize proxy snapshot"),
            scrub_runtime_timestamps,
        )
        .expect("canonicalize proxy telemetry");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn scrub_runtime_timestamps(value: &mut Value) {
        match value {
            Value::Array(items) => {
                for item in items {
                    scrub_runtime_timestamps(item);
                }
            }
            Value::Object(map) => {
                for (key, item) in map.iter_mut() {
                    if matches!(key.as_str(), "createdAt" | "capturedAt") {
                        *item = Value::from(0);
                    } else {
                        scrub_runtime_timestamps(item);
                    }
                }
            }
            _ => {}
        }
    }

    impl ProxySessionHarness {
        fn tracked_handle(&self) -> jlong {
            self.active_handle.or(self.stale_handle).unwrap_or(0)
        }

        fn ensure_created(&mut self) -> jlong {
            if let Some(handle) = self.active_handle {
                return handle;
            }

            let handle = SESSIONS.insert(ProxySession {
                config: RuntimeConfig::default(),
                telemetry: Arc::new(ProxyTelemetryState::new()),
                state: Mutex::new(ProxySessionState::Idle),
            }) as jlong;
            self.active_handle = Some(handle);
            self.stale_handle = Some(handle);
            handle
        }

        fn start(&mut self) -> Result<i32, String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let listener_fd = self.next_listener_fd;
            let mut state = session.state.lock().expect("proxy state lock");
            try_mark_proxy_running(&mut state, listener_fd)?;
            self.next_listener_fd += 1;
            Ok(listener_fd)
        }

        fn stop(&mut self) -> Result<i32, String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let mut state = session.state.lock().expect("proxy state lock");
            let listener_fd = listener_fd_for_proxy_stop(&state)?;
            *state = ProxySessionState::Idle;
            Ok(listener_fd)
        }

        fn destroy(&mut self) -> Result<(), String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let state = session.state.lock().expect("proxy state lock");
            ensure_proxy_destroyable(&state)?;
            drop(state);
            let handle = self.active_handle.take().unwrap_or_else(|| self.tracked_handle());
            self.stale_handle = Some(handle);
            let _ = remove_proxy_session(handle).map_err(|e| e.to_string())?;
            Ok(())
        }

        fn cleanup(&mut self) {
            if let Some(handle) = self.active_handle.take() {
                if let Ok(session) = lookup_proxy_session(handle) {
                    if let Ok(mut state) = session.state.lock() {
                        *state = ProxySessionState::Idle;
                    }
                }
                let _ = remove_proxy_session(handle);
                self.stale_handle = Some(handle);
            }
        }
    }

    impl Drop for ProxySessionHarness {
        fn drop(&mut self) {
            self.cleanup();
        }
    }

    fn proxy_absent_error(handle: jlong) -> String {
        if to_handle(handle).is_some() {
            "Unknown proxy handle".to_string()
        } else {
            "Invalid proxy handle".to_string()
        }
    }

    fn proxy_state_command_strategy() -> impl Strategy<Value = Vec<ProxyStateCommand>> {
        vec(
            prop_oneof![
                Just(ProxyStateCommand::EnsureCreated),
                Just(ProxyStateCommand::Start),
                Just(ProxyStateCommand::Stop),
                Just(ProxyStateCommand::Destroy),
            ],
            1..32,
        )
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(256))]

        #[test]
        fn fuzz_proxy_json_parser_never_panics(input in lossy_string(512)) {
            let _ = parse_proxy_config_json(&input);
        }

        #[test]
        fn fuzz_command_line_parser_never_panics(args in vec(lossy_string(32), 0..12)) {
            let _ = runtime_config_from_command_line(args);
        }

        #[test]
        fn fuzz_ui_payload_mapping_never_panics(payload in proxy_ui_config_strategy()) {
            let _ = runtime_config_from_ui(payload);
        }

        #[test]
        fn valid_ui_payloads_preserve_core_fields(
            ip in prop_oneof![
                Just("127.0.0.1".to_string()),
                Just("0.0.0.0".to_string()),
                Just("::1".to_string()),
            ],
            port in 1i32..65_536i32,
            max_connections in 1i32..4_096i32,
            buffer_size in 1i32..65_536i32,
            split_position in -64i32..64i32,
            split_at_host in any::<bool>(),
            tls_record_split in any::<bool>(),
            tls_record_split_position in -64i32..64i32,
            tls_record_split_at_sni in any::<bool>(),
            tcp_fast_open in any::<bool>(),
            drop_sack in any::<bool>(),
            fake_offset in -64i32..64i32,
            udp_fake_count in 0i32..8i32,
            desync_method in prop_oneof![
                Just("none".to_string()),
                Just("split".to_string()),
                Just("disorder".to_string()),
                Just("fake".to_string()),
                Just("oob".to_string()),
                Just("disoob".to_string()),
            ],
            hosts_mode in prop_oneof![
                Just(HOSTS_DISABLE.to_string()),
                Just(HOSTS_BLACKLIST.to_string()),
                Just(HOSTS_WHITELIST.to_string()),
            ],
        ) {
            let hosts = match hosts_mode.as_str() {
                HOSTS_DISABLE => None,
                _ => Some("example.org".to_string()),
            };

            let config = runtime_config_from_ui(ProxyUiConfig {
                ip: ip.clone(),
                port,
                max_connections,
                buffer_size,
                default_ttl: 64,
                custom_ttl: true,
                no_domain: false,
                desync_http: true,
                desync_https: true,
                desync_udp: false,
                desync_method,
                split_marker: None,
                tcp_chain_steps: Vec::new(),
                split_position,
                split_at_host,
                fake_ttl: 8,
                fake_sni: "www.iana.org".to_string(),
                http_fake_profile: "compat_default".to_string(),
                fake_tls_use_original: false,
                fake_tls_randomize: false,
                fake_tls_dup_session_id: false,
                fake_tls_pad_encap: false,
                fake_tls_size: 0,
                fake_tls_sni_mode: default_fake_tls_sni_mode(),
                tls_fake_profile: "compat_default".to_string(),
                oob_char: b'a',
                host_mixed_case: false,
                domain_mixed_case: false,
                host_remove_spaces: false,
                tls_record_split,
                tls_record_split_marker: None,
                tls_record_split_position,
                tls_record_split_at_sni,
                hosts_mode,
                hosts,
                tcp_fast_open,
                udp_fake_count,
                udp_chain_steps: Vec::new(),
                udp_fake_profile: "compat_default".to_string(),
                drop_sack,
                fake_offset_marker: None,
                fake_offset,
                quic_initial_mode: Some("route_and_cache".to_string()),
                quic_support_v1: true,
                quic_support_v2: true,
                quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
                quic_fake_host: String::new(),
                host_autolearn_enabled: false,
                host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
                host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
                host_autolearn_store_path: None,
            }).expect("valid payload");

            prop_assert_eq!(config.listen.listen_ip, IpAddr::from_str(&ip).expect("valid ip"));
            prop_assert_eq!(config.listen.listen_port, u16::try_from(port).expect("valid port"));
            prop_assert_eq!(config.max_open, max_connections);
            prop_assert_eq!(config.buffer_size, usize::try_from(buffer_size).expect("valid buffer size"));
            prop_assert_eq!(config.tfo, tcp_fast_open);
            prop_assert!(!config.groups.is_empty());
        }

        #[test]
        fn proxy_session_state_machine(commands in proxy_state_command_strategy()) {
            let mut harness = ProxySessionHarness::default();
            let mut model = ProxyModelState::Absent;
            let mut expected_listener_fd = 32;

            for command in commands {
                match command {
                    ProxyStateCommand::EnsureCreated => {
                        let handle = harness.ensure_created();
                        prop_assert!(lookup_proxy_session(handle).is_ok());
                        if matches!(model, ProxyModelState::Absent) {
                            model = ProxyModelState::Idle;
                        }
                    }
                    ProxyStateCommand::Start => {
                        match model {
                            ProxyModelState::Absent => {
                                let err = harness.start().expect_err("absent start must fail");
                                prop_assert_eq!(err, proxy_absent_error(harness.tracked_handle()));
                            }
                            ProxyModelState::Idle => {
                                let listener_fd = harness.start().expect("idle start");
                                prop_assert_eq!(listener_fd, expected_listener_fd);
                                expected_listener_fd += 1;
                                model = ProxyModelState::Running;
                            }
                            ProxyModelState::Running => {
                                let err = harness.start().expect_err("duplicate start must fail");
                                prop_assert_eq!(err, "Proxy session is already running");
                            }
                        }
                    }
                    ProxyStateCommand::Stop => {
                        match model {
                            ProxyModelState::Absent => {
                                let err = harness.stop().expect_err("absent stop must fail");
                                prop_assert_eq!(err, proxy_absent_error(harness.tracked_handle()));
                            }
                            ProxyModelState::Idle => {
                                let err = harness.stop().expect_err("idle stop must fail");
                                prop_assert_eq!(err, "Proxy session is not running");
                            }
                            ProxyModelState::Running => {
                                let listener_fd = harness.stop().expect("running stop");
                                prop_assert!(listener_fd >= 32);
                                model = ProxyModelState::Idle;
                            }
                        }
                    }
                    ProxyStateCommand::Destroy => {
                        match model {
                            ProxyModelState::Absent => {
                                let err = harness.destroy().expect_err("absent destroy must fail");
                                prop_assert_eq!(err, proxy_absent_error(harness.tracked_handle()));
                            }
                            ProxyModelState::Idle => {
                                harness.destroy().expect("idle destroy");
                                model = ProxyModelState::Absent;
                            }
                            ProxyModelState::Running => {
                                let err = harness.destroy().expect_err("running destroy must fail");
                                prop_assert_eq!(err, "Cannot destroy a running proxy session");
                            }
                        }
                    }
                }

                match model {
                    ProxyModelState::Absent => {
                        if to_handle(harness.tracked_handle()).is_some() {
                            let err = match lookup_proxy_session(harness.tracked_handle()) {
                                Ok(_) => panic!("absent session must be removed"),
                                Err(err) => err.to_string(),
                            };
                            prop_assert_eq!(err, "Unknown proxy handle");
                        }
                    }
                    ProxyModelState::Idle => {
                        let session = lookup_proxy_session(harness.tracked_handle()).expect("idle session");
                        let state = session.state.lock().expect("proxy state lock");
                        prop_assert!(matches!(*state, ProxySessionState::Idle));
                    }
                    ProxyModelState::Running => {
                        let session = lookup_proxy_session(harness.tracked_handle()).expect("running session");
                        let state = session.state.lock().expect("proxy state lock");
                        let is_running = matches!(*state, ProxySessionState::Running { .. });
                        prop_assert!(is_running);
                    }
                }
            }
        }
    }
}
