use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

#[cfg(test)]
use std::time::{Duration, Instant};

use android_support::{
    init_android_logging, throw_illegal_argument, throw_illegal_state, throw_io_exception, throw_runtime_exception,
    HandleRegistry, JNI_VERSION,
};
use hs5t_config::{Config, MapDnsConfig, MiscConfig, Socks5Config, TunnelConfig};
use hs5t_core::{DnsStatsSnapshot, Stats};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

static SESSIONS: Lazy<HandleRegistry<TunnelSession>> = Lazy::new(HandleRegistry::new);

struct TunnelSession {
    runtime: Arc<Runtime>,
    config: Arc<Config>,
    last_error: Arc<Mutex<Option<String>>>,
    telemetry: Arc<TunnelTelemetryState>,
    state: Mutex<TunnelSessionState>,
}

enum TunnelSessionState {
    Ready,
    Starting,
    Running { cancel: Arc<CancellationToken>, stats: Arc<Stats>, worker: JoinHandle<()> },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TunnelConfigPayload {
    tunnel_name: String,
    tunnel_mtu: u32,
    multi_queue: bool,
    tunnel_ipv4: Option<String>,
    tunnel_ipv6: Option<String>,
    socks5_address: String,
    socks5_port: u16,
    socks5_udp: Option<String>,
    socks5_udp_address: Option<String>,
    socks5_pipeline: Option<bool>,
    username: Option<String>,
    password: Option<String>,
    mapdns_address: Option<String>,
    mapdns_port: Option<u16>,
    mapdns_network: Option<String>,
    mapdns_netmask: Option<String>,
    mapdns_cache_size: Option<u32>,
    encrypted_dns_resolver_id: Option<String>,
    encrypted_dns_protocol: Option<String>,
    encrypted_dns_host: Option<String>,
    encrypted_dns_port: Option<u16>,
    encrypted_dns_tls_server_name: Option<String>,
    encrypted_dns_doh_url: Option<String>,
    encrypted_dns_dnscrypt_provider_name: Option<String>,
    encrypted_dns_dnscrypt_public_key: Option<String>,
    // Deprecated compatibility fields kept for older payloads.
    doh_resolver_id: Option<String>,
    doh_url: Option<String>,
    #[serde(default)]
    doh_bootstrap_ips: Vec<String>,
    #[serde(default)]
    encrypted_dns_bootstrap_ips: Vec<String>,
    dns_query_timeout_ms: Option<u32>,
    resolver_fallback_active: Option<bool>,
    resolver_fallback_reason: Option<String>,
    task_stack_size: u32,
    tcp_buffer_size: Option<u32>,
    udp_recv_buffer_size: Option<u32>,
    udp_copy_buffer_nums: Option<u32>,
    max_session_count: Option<u32>,
    connect_timeout_ms: Option<u32>,
    tcp_read_write_timeout_ms: Option<u32>,
    udp_read_write_timeout_ms: Option<u32>,
    log_level: String,
    limit_nofile: Option<u32>,
    #[serde(default)]
    filter_injected_resets: Option<bool>,
}

const MAX_TUNNEL_EVENTS: usize = 128;

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
    route_changes: u64,
    last_route_group: Option<i32>,
    listener_address: Option<String>,
    upstream_address: Option<String>,
    resolver_id: Option<String>,
    resolver_protocol: Option<String>,
    resolver_endpoint: Option<String>,
    resolver_latency_ms: Option<u64>,
    resolver_latency_avg_ms: Option<u64>,
    resolver_fallback_active: bool,
    resolver_fallback_reason: Option<String>,
    network_handover_class: Option<String>,
    last_target: Option<String>,
    last_host: Option<String>,
    last_error: Option<String>,
    dns_queries_total: u64,
    dns_cache_hits: u64,
    dns_cache_misses: u64,
    dns_failures_total: u64,
    last_dns_host: Option<String>,
    last_dns_error: Option<String>,
    tunnel_stats: TunnelStatsSnapshot,
    native_events: Vec<NativeRuntimeEvent>,
    captured_at: u64,
}

struct TunnelTelemetryState {
    running: AtomicBool,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    upstream_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    events: Mutex<VecDeque<NativeRuntimeEvent>>,
}

impl TunnelTelemetryState {
    fn new() -> Self {
        Self {
            running: AtomicBool::new(false),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            upstream_address: Mutex::new(None),
            last_error: Mutex::new(None),
            events: Mutex::new(VecDeque::with_capacity(MAX_TUNNEL_EVENTS)),
        }
    }

    fn mark_started(&self, upstream: String) {
        self.running.store(true, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut guard) = self.upstream_address.lock() {
            *guard = Some(upstream.clone());
        }
        log::info!("tunnel started upstream={upstream}");
        self.push_event("tunnel", "info", format!("tunnel started upstream={upstream}"));
    }

    fn mark_stop_requested(&self) {
        log::info!("tunnel stop requested");
        self.push_event("tunnel", "info", "tunnel stop requested".to_string());
    }

    fn mark_stopped(&self) {
        self.running.store(false, Ordering::Relaxed);
        log::info!("tunnel stopped");
        self.push_event("tunnel", "info", "tunnel stopped".to_string());
    }

    fn record_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = Some(error.clone());
        }
        log::warn!("tunnel error: {error}");
        self.push_event("tunnel", "warn", format!("tunnel error: {error}"));
    }

    fn snapshot(
        &self,
        traffic_stats: (u64, u64, u64, u64),
        dns_stats: DnsStatsSnapshot,
        resolver_id: Option<String>,
        resolver_protocol: Option<String>,
    ) -> NativeRuntimeSnapshot {
        NativeRuntimeSnapshot {
            source: "tunnel".to_string(),
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
            active_sessions: u64::from(self.running.load(Ordering::Relaxed)),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            route_changes: 0,
            last_route_group: None,
            listener_address: None,
            upstream_address: self.upstream_address.lock().ok().and_then(|guard| guard.clone()),
            resolver_id,
            resolver_protocol,
            resolver_endpoint: dns_stats.resolver_endpoint,
            resolver_latency_ms: dns_stats.resolver_latency_ms,
            resolver_latency_avg_ms: dns_stats.resolver_latency_avg_ms,
            resolver_fallback_active: dns_stats.resolver_fallback_active,
            resolver_fallback_reason: dns_stats.resolver_fallback_reason,
            network_handover_class: None,
            last_target: None,
            last_host: dns_stats.last_host,
            last_error: self.last_error.lock().ok().and_then(|guard| guard.clone()),
            dns_queries_total: dns_stats.dns_queries_total,
            dns_cache_hits: dns_stats.dns_cache_hits,
            dns_cache_misses: dns_stats.dns_cache_misses,
            dns_failures_total: dns_stats.dns_failures_total,
            last_dns_host: dns_stats.last_dns_host,
            last_dns_error: dns_stats.last_dns_error,
            tunnel_stats: TunnelStatsSnapshot {
                tx_packets: traffic_stats.0,
                tx_bytes: traffic_stats.1,
                rx_packets: traffic_stats.2,
                rx_bytes: traffic_stats.3,
            },
            native_events: self.drain_events(),
            captured_at: now_ms(),
        }
    }

    fn drain_events(&self) -> Vec<NativeRuntimeEvent> {
        if let Ok(mut guard) = self.events.lock() {
            guard.drain(..).collect()
        } else {
            Vec::new()
        }
    }

    fn push_event(&self, source: &str, level: &str, message: String) {
        if let Ok(mut guard) = self.events.lock() {
            if guard.len() >= MAX_TUNNEL_EVENTS {
                guard.pop_front();
            }
            guard.push_back(NativeRuntimeEvent {
                source: source.to_string(),
                level: level.to_string(),
                message,
                created_at: now_ms(),
            });
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("hs5t-native");
    JNI_VERSION
}

fn tunnel_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel session creation panicked");
            0
        },
    )
}

fn tunnel_start_entry(mut env: JNIEnv, handle: jlong, tun_fd: jint) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle, tun_fd)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session start panicked"));
}

fn tunnel_stop_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session stop panicked"));
}

fn tunnel_stats_entry(mut env: JNIEnv, handle: jlong) -> jlongArray {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stats_session(&mut env, handle))).unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel stats retrieval panicked");
        std::ptr::null_mut()
    })
}

fn tunnel_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jni::sys::jstring {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| telemetry_session(&mut env, handle))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel telemetry retrieval panicked");
            std::ptr::null_mut()
        },
    )
}

fn tunnel_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session destroy panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
    env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    tunnel_create_entry(env, config_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
    tun_fd: jint,
) {
    tunnel_start_entry(env, handle, tun_fd);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    tunnel_stop_entry(env, handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jlongArray {
    tunnel_stats_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    tunnel_telemetry_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    tunnel_destroy_entry(env, handle);
}

fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid tunnel config payload");
            return 0;
        }
    };
    let payload = match parse_tunnel_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            throw_illegal_argument(env, err);
            return 0;
        }
    };
    let config = match config_from_payload(payload) {
        Ok(config) => Arc::new(config),
        Err(message) => {
            throw_illegal_argument(env, message);
            return 0;
        }
    };
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_stack_size(1024 * 1024)
        .thread_name("hs5t-tokio")
        .enable_all()
        .build()
    {
        Ok(rt) => Arc::new(rt),
        Err(err) => {
            throw_io_exception(env, format!("Failed to initialize Tokio runtime: {err}"));
            return 0;
        }
    };

    SESSIONS.insert(TunnelSession {
        runtime,
        config,
        last_error: Arc::new(Mutex::new(None)),
        telemetry: Arc::new(TunnelTelemetryState::new()),
        state: Mutex::new(TunnelSessionState::Ready),
    }) as jlong
}

fn start_session(env: &mut JNIEnv, handle: jlong, tun_fd: jint) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return;
        }
    };
    if let Err(message) = validate_tun_fd(tun_fd) {
        throw_illegal_argument(env, message);
        return;
    }
    // Duplicate the fd so run_tunnel owns an independent copy.
    // If VpnService revokes the original fd, the dup'd fd remains valid
    // until run_tunnel closes it via File::from_raw_fd.
    let owned_fd = unsafe { libc::dup(tun_fd) };
    if owned_fd < 0 {
        throw_io_exception(env, format!("Failed to dup TUN fd: {}", std::io::Error::last_os_error()));
        return;
    }
    let runtime = session.runtime.clone();

    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());
    let config = session.config.clone();
    let last_error = session.last_error.clone();
    let telemetry = session.telemetry.clone();

    {
        let mut state = session.state.lock().expect("tunnel session poisoned");
        if let Err(message) = ensure_tunnel_start_allowed(&state) {
            // Bug H4 fix: close the dup'd fd before returning.
            unsafe {
                libc::close(owned_fd);
            }
            throw_illegal_state(env, message);
            return;
        }
        // Bug H3 fix: atomically transition to Starting while holding the lock,
        // so no concurrent call can also see Ready.
        *state = TunnelSessionState::Starting;
    }

    if let Ok(mut guard) = session.last_error.lock() {
        *guard = None;
    }
    telemetry.mark_started(format!("{}:{}", session.config.socks5.address, session.config.socks5.port));

    let worker_cancel = cancel.clone();
    let worker_stats = stats.clone();
    let worker = std::thread::Builder::new()
        .name("hs5t-worker".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                runtime.block_on(hs5t_core::run_tunnel(
                    config,
                    owned_fd,
                    (*worker_cancel).clone(),
                    worker_stats.clone(),
                ))
            }));

            match result {
                Ok(Ok(())) => {}
                Ok(Err(err)) => {
                    log::error!("tunnel worker exited with error: {err}");
                    if let Ok(mut guard) = last_error.lock() {
                        *guard = Some(err.to_string());
                    }
                    telemetry.record_error(err.to_string());
                }
                Err(_) => {
                    log::error!("tunnel worker panicked");
                    if let Ok(mut guard) = last_error.lock() {
                        *guard = Some("Tunnel worker panicked".to_string());
                    }
                    telemetry.record_error("Tunnel worker panicked".to_string());
                }
            }
            telemetry.mark_stopped();
        })
        .expect("failed to spawn tunnel worker thread");

    let mut state = session.state.lock().expect("tunnel session poisoned");
    *state = TunnelSessionState::Running { cancel, stats, worker };
}

fn stop_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return;
        }
    };

    let running = {
        let mut state = session.state.lock().expect("tunnel session poisoned");
        match take_running_tunnel(&mut state) {
            Ok(running) => running,
            Err(message) => {
                throw_illegal_state(env, message);
                return;
            }
        }
    };

    running.0.cancel();
    session.telemetry.mark_stop_requested();
    if running.1.join().is_err() {
        log::error!("tunnel worker panicked during shutdown");
    }
}

fn stats_session(env: &mut JNIEnv, handle: jlong) -> jlongArray {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return std::ptr::null_mut();
        }
    };

    let snapshot = {
        let state = session.state.lock().expect("tunnel session poisoned");
        stats_snapshots_for_state(&state).0
    };

    match env.new_long_array(4) {
        Ok(arr) => {
            let values: [i64; 4] = [snapshot.0 as i64, snapshot.1 as i64, snapshot.2 as i64, snapshot.3 as i64];
            if env.set_long_array_region(&arr, 0, &values).is_ok() {
                arr.into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn telemetry_session(env: &mut JNIEnv, handle: jlong) -> jni::sys::jstring {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return std::ptr::null_mut();
        }
    };
    let (traffic_stats, dns_stats) = {
        let state = session.state.lock().expect("tunnel session poisoned");
        stats_snapshots_for_state(&state)
    };
    let resolver_id = session.config.mapdns.as_ref().and_then(|mapdns| mapdns.resolver_id.clone());
    let resolver_protocol = session.config.mapdns.as_ref().and_then(mapdns_resolver_protocol);
    match serde_json::to_string(&session.telemetry.snapshot(traffic_stats, dns_stats, resolver_id, resolver_protocol)) {
        Ok(value) => env.new_string(value).map(jni::objects::JString::into_raw).unwrap_or(std::ptr::null_mut()),
        Err(err) => {
            throw_runtime_exception(env, err.to_string());
            std::ptr::null_mut()
        }
    }
}

fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return;
        }
    };
    let state = session.state.lock().expect("tunnel session poisoned");
    if let Err(message) = ensure_tunnel_destroyable(&state) {
        throw_illegal_state(env, message);
        return;
    }
    drop(state);
    let _ = remove_tunnel_session(handle);
}

fn config_from_payload(payload: TunnelConfigPayload) -> Result<Config, String> {
    if payload.socks5_address.is_blank() {
        return Err("socks5Address must not be blank".to_string());
    }
    if payload.tunnel_name.is_blank() {
        return Err("tunnelName must not be blank".to_string());
    }

    let mut misc =
        MiscConfig { task_stack_size: payload.task_stack_size, log_level: payload.log_level, ..MiscConfig::default() };
    if let Some(value) = payload.tcp_buffer_size {
        misc.tcp_buffer_size = value;
    }
    if let Some(value) = payload.udp_recv_buffer_size {
        misc.udp_recv_buffer_size = value;
    }
    if let Some(value) = payload.udp_copy_buffer_nums {
        misc.udp_copy_buffer_nums = value;
    }
    if let Some(value) = payload.max_session_count {
        misc.max_session_count = value;
    }
    if let Some(value) = payload.connect_timeout_ms {
        misc.connect_timeout = value;
    }
    if let Some(value) = payload.tcp_read_write_timeout_ms {
        misc.tcp_read_write_timeout = value;
    }
    if let Some(value) = payload.udp_read_write_timeout_ms {
        misc.udp_read_write_timeout = value;
    }
    if let Some(value) = payload.limit_nofile {
        misc.limit_nofile = value;
    }
    if let Some(value) = payload.filter_injected_resets {
        misc.filter_injected_resets = value;
    }

    let mapdns = payload.mapdns_address.map(|address| MapDnsConfig {
        address,
        port: payload.mapdns_port.unwrap_or(53),
        network: payload.mapdns_network,
        netmask: payload.mapdns_netmask,
        cache_size: payload.mapdns_cache_size.unwrap_or(10_000),
        resolver_id: payload.encrypted_dns_resolver_id.or(payload.doh_resolver_id),
        encrypted_dns_protocol: payload.encrypted_dns_protocol,
        encrypted_dns_host: payload.encrypted_dns_host,
        encrypted_dns_port: payload.encrypted_dns_port,
        encrypted_dns_tls_server_name: payload.encrypted_dns_tls_server_name,
        encrypted_dns_bootstrap_ips: payload.encrypted_dns_bootstrap_ips,
        encrypted_dns_doh_url: payload.encrypted_dns_doh_url,
        encrypted_dns_dnscrypt_provider_name: payload.encrypted_dns_dnscrypt_provider_name,
        encrypted_dns_dnscrypt_public_key: payload.encrypted_dns_dnscrypt_public_key,
        doh_url: payload.doh_url,
        doh_bootstrap_ips: payload.doh_bootstrap_ips,
        dns_query_timeout_ms: payload.dns_query_timeout_ms.unwrap_or(4_000),
        resolver_fallback_active: payload.resolver_fallback_active.unwrap_or(false),
        resolver_fallback_reason: payload.resolver_fallback_reason,
    });

    Ok(Config {
        tunnel: TunnelConfig {
            name: payload.tunnel_name,
            mtu: payload.tunnel_mtu,
            multi_queue: payload.multi_queue,
            ipv4: payload.tunnel_ipv4,
            ipv6: payload.tunnel_ipv6,
            post_up_script: None,
            pre_down_script: None,
        },
        socks5: Socks5Config {
            port: payload.socks5_port,
            address: payload.socks5_address,
            udp: payload.socks5_udp,
            udp_address: payload.socks5_udp_address,
            pipeline: payload.socks5_pipeline,
            username: payload.username,
            password: payload.password,
            mark: None,
        },
        mapdns,
        misc,
    })
}

fn parse_tunnel_config_json(json: &str) -> Result<TunnelConfigPayload, String> {
    serde_json::from_str::<TunnelConfigPayload>(json).map_err(|err| format!("Invalid tunnel config JSON: {err}"))
}

fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

fn lookup_tunnel_session(handle: jlong) -> Result<Arc<TunnelSession>, &'static str> {
    let handle = to_handle(handle).ok_or("Invalid tunnel handle")?;
    SESSIONS.get(handle).ok_or("Unknown tunnel handle")
}

fn remove_tunnel_session(handle: jlong) -> Result<Arc<TunnelSession>, &'static str> {
    let handle = to_handle(handle).ok_or("Invalid tunnel handle")?;
    SESSIONS.remove(handle).ok_or("Unknown tunnel handle")
}

fn validate_tun_fd(tun_fd: jint) -> Result<(), &'static str> {
    if tun_fd < 0 {
        Err("Invalid TUN file descriptor")
    } else {
        Ok(())
    }
}

fn ensure_tunnel_start_allowed(state: &TunnelSessionState) -> Result<(), &'static str> {
    match *state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting => Err("Tunnel session is already starting"),
        TunnelSessionState::Running { .. } => Err("Tunnel session is already running"),
    }
}

fn take_running_tunnel(
    state: &mut TunnelSessionState,
) -> Result<(Arc<CancellationToken>, JoinHandle<()>), &'static str> {
    match std::mem::replace(state, TunnelSessionState::Ready) {
        TunnelSessionState::Ready | TunnelSessionState::Starting => Err("Tunnel session is not running"),
        TunnelSessionState::Running { cancel, stats: _, worker } => Ok((cancel, worker)),
    }
}

fn stats_snapshots_for_state(state: &TunnelSessionState) -> ((u64, u64, u64, u64), DnsStatsSnapshot) {
    match state {
        TunnelSessionState::Ready | TunnelSessionState::Starting => ((0, 0, 0, 0), DnsStatsSnapshot::default()),
        TunnelSessionState::Running { stats, .. } => (stats.snapshot(), stats.dns_snapshot()),
    }
}

fn ensure_tunnel_destroyable(state: &TunnelSessionState) -> Result<(), &'static str> {
    match *state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting => Err("Cannot destroy a starting tunnel session"),
        TunnelSessionState::Running { .. } => Err("Cannot destroy a running tunnel session"),
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

fn mapdns_resolver_protocol(mapdns: &MapDnsConfig) -> Option<String> {
    mapdns.encrypted_dns_protocol.clone().or_else(|| mapdns.doh_url.as_ref().map(|_| "doh".to_string()))
}

trait BlankCheck {
    fn is_blank(&self) -> bool;
}

impl BlankCheck for String {
    fn is_blank(&self) -> bool {
        self.trim().is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::{assert_text_golden, canonicalize_json_with};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use serde_json::{json, Value};

    fn lossy_string(max_len: usize) -> impl Strategy<Value = String> {
        vec(any::<u8>(), 0..max_len).prop_map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
    }

    fn non_blank_string(max_len: usize) -> impl Strategy<Value = String> {
        lossy_string(max_len).prop_filter("string must not be blank", |value| !value.trim().is_empty())
    }

    fn ipv4_address() -> impl Strategy<Value = String> {
        (1u8..=223, any::<u8>(), any::<u8>(), 1u8..=254).prop_map(|(a, b, c, d)| format!("{a}.{b}.{c}.{d}"))
    }

    fn tunnel_payload_strategy() -> impl Strategy<Value = TunnelConfigPayload> {
        // proptest implements Strategy for tuples up to 12 elements;
        // nest into sub-tuples to stay within that limit.
        (
            (
                lossy_string(24),
                1u32..9001,
                any::<bool>(),
                prop::option::of(ipv4_address()),
                prop::option::of(prop_oneof![Just("fd00::1".to_string()), Just("2001:db8::1".to_string())]),
                lossy_string(32),
                1u16..=u16::MAX,
                prop::option::of(lossy_string(12)),
                prop::option::of(ipv4_address()),
                prop::option::of(any::<bool>()),
                prop::option::of(lossy_string(16)),
                prop::option::of(lossy_string(16)),
            ),
            (
                prop::option::of(ipv4_address()),
                prop::option::of(1u16..=u16::MAX),
                prop::option::of(Just("172.16.0.0".to_string())),
                prop::option::of(Just("255.240.0.0".to_string())),
                prop::option::of(1u32..50_001),
                1u32..262_145,
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..1025),
                prop::option::of(1u32..120_001),
            ),
            // connect_timeout, tcp/udp rw timeouts, log_level, limit_nofile
            (
                prop::option::of(1u32..120_001),
                prop::option::of(1u32..120_001),
                prop::option::of(1u32..120_001),
                prop_oneof![
                    Just("trace".to_string()),
                    Just("debug".to_string()),
                    Just("info".to_string()),
                    Just("warn".to_string()),
                    Just("error".to_string()),
                ],
                prop::option::of(128u32..65_536),
            ),
        )
            .prop_map(
                |(
                    (
                        tunnel_name,
                        tunnel_mtu,
                        multi_queue,
                        tunnel_ipv4,
                        tunnel_ipv6,
                        socks5_address,
                        socks5_port,
                        socks5_udp,
                        socks5_udp_address,
                        socks5_pipeline,
                        username,
                        password,
                    ),
                    (
                        mapdns_address,
                        mapdns_port,
                        mapdns_network,
                        mapdns_netmask,
                        mapdns_cache_size,
                        task_stack_size,
                        tcp_buffer_size,
                        udp_recv_buffer_size,
                        udp_copy_buffer_nums,
                        max_session_count,
                    ),
                    (connect_timeout_ms, tcp_read_write_timeout_ms, udp_read_write_timeout_ms, log_level, limit_nofile),
                )| TunnelConfigPayload {
                    tunnel_name,
                    tunnel_mtu,
                    multi_queue,
                    tunnel_ipv4,
                    tunnel_ipv6,
                    socks5_address,
                    socks5_port,
                    socks5_udp,
                    socks5_udp_address,
                    socks5_pipeline,
                    username,
                    password,
                    mapdns_address,
                    mapdns_port,
                    mapdns_network,
                    mapdns_netmask,
                    mapdns_cache_size,
                    encrypted_dns_resolver_id: None,
                    encrypted_dns_protocol: None,
                    encrypted_dns_host: None,
                    encrypted_dns_port: None,
                    encrypted_dns_tls_server_name: None,
                    encrypted_dns_doh_url: None,
                    encrypted_dns_dnscrypt_provider_name: None,
                    encrypted_dns_dnscrypt_public_key: None,
                    doh_resolver_id: None,
                    doh_url: None,
                    doh_bootstrap_ips: Vec::new(),
                    encrypted_dns_bootstrap_ips: Vec::new(),
                    dns_query_timeout_ms: None,
                    resolver_fallback_active: None,
                    resolver_fallback_reason: None,
                    task_stack_size,
                    tcp_buffer_size,
                    udp_recv_buffer_size,
                    udp_copy_buffer_nums,
                    max_session_count,
                    connect_timeout_ms,
                    tcp_read_write_timeout_ms,
                    udp_read_write_timeout_ms,
                    log_level,
                    limit_nofile,
                    filter_injected_resets: None,
                },
            )
    }

    fn valid_tunnel_payload_strategy() -> impl Strategy<Value = TunnelConfigPayload> {
        (
            (
                non_blank_string(24),
                1u32..9001,
                any::<bool>(),
                prop::option::of(ipv4_address()),
                prop::option::of(prop_oneof![Just("fd00::1".to_string()), Just("2001:db8::1".to_string())]),
                ipv4_address(),
                1u16..=u16::MAX,
                prop::option::of(non_blank_string(12)),
                prop::option::of(ipv4_address()),
                prop::option::of(any::<bool>()),
                prop::option::of(non_blank_string(16)),
                prop::option::of(non_blank_string(16)),
            ),
            (
                prop::option::of(ipv4_address()),
                prop::option::of(1u16..=u16::MAX),
                prop::option::of(Just("172.16.0.0".to_string())),
                prop::option::of(Just("255.240.0.0".to_string())),
                prop::option::of(1u32..50_001),
                1u32..262_145,
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..1025),
                prop::option::of(1u32..120_001),
            ),
            // connect_timeout, tcp/udp rw timeouts, log_level, limit_nofile
            (
                prop::option::of(1u32..120_001),
                prop::option::of(1u32..120_001),
                prop::option::of(1u32..120_001),
                prop_oneof![
                    Just("trace".to_string()),
                    Just("debug".to_string()),
                    Just("info".to_string()),
                    Just("warn".to_string()),
                    Just("error".to_string()),
                ],
                prop::option::of(128u32..65_536),
            ),
        )
            .prop_map(
                |(
                    (
                        tunnel_name,
                        tunnel_mtu,
                        multi_queue,
                        tunnel_ipv4,
                        tunnel_ipv6,
                        socks5_address,
                        socks5_port,
                        socks5_udp,
                        socks5_udp_address,
                        socks5_pipeline,
                        username,
                        password,
                    ),
                    (
                        mapdns_address,
                        mapdns_port,
                        mapdns_network,
                        mapdns_netmask,
                        mapdns_cache_size,
                        task_stack_size,
                        tcp_buffer_size,
                        udp_recv_buffer_size,
                        udp_copy_buffer_nums,
                        max_session_count,
                    ),
                    (connect_timeout_ms, tcp_read_write_timeout_ms, udp_read_write_timeout_ms, log_level, limit_nofile),
                )| TunnelConfigPayload {
                    tunnel_name,
                    tunnel_mtu,
                    multi_queue,
                    tunnel_ipv4,
                    tunnel_ipv6,
                    socks5_address,
                    socks5_port,
                    socks5_udp,
                    socks5_udp_address,
                    socks5_pipeline,
                    username,
                    password,
                    mapdns_address,
                    mapdns_port,
                    mapdns_network,
                    mapdns_netmask,
                    mapdns_cache_size,
                    encrypted_dns_resolver_id: None,
                    encrypted_dns_protocol: None,
                    encrypted_dns_host: None,
                    encrypted_dns_port: None,
                    encrypted_dns_tls_server_name: None,
                    encrypted_dns_doh_url: None,
                    encrypted_dns_dnscrypt_provider_name: None,
                    encrypted_dns_dnscrypt_public_key: None,
                    doh_resolver_id: None,
                    doh_url: None,
                    doh_bootstrap_ips: Vec::new(),
                    encrypted_dns_bootstrap_ips: Vec::new(),
                    dns_query_timeout_ms: None,
                    resolver_fallback_active: None,
                    resolver_fallback_reason: None,
                    task_stack_size,
                    tcp_buffer_size,
                    udp_recv_buffer_size,
                    udp_copy_buffer_nums,
                    max_session_count,
                    connect_timeout_ms,
                    tcp_read_write_timeout_ms,
                    udp_read_write_timeout_ms,
                    log_level,
                    limit_nofile,
                    filter_injected_resets: None,
                },
            )
    }

    fn sample_payload() -> TunnelConfigPayload {
        TunnelConfigPayload {
            tunnel_name: "tun0".to_string(),
            tunnel_mtu: 8500,
            multi_queue: false,
            tunnel_ipv4: None,
            tunnel_ipv6: None,
            socks5_address: "127.0.0.1".to_string(),
            socks5_port: 1080,
            socks5_udp: Some("udp".to_string()),
            socks5_udp_address: None,
            socks5_pipeline: None,
            username: None,
            password: None,
            mapdns_address: None,
            mapdns_port: None,
            mapdns_network: None,
            mapdns_netmask: None,
            mapdns_cache_size: None,
            encrypted_dns_resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            doh_resolver_id: None,
            doh_url: None,
            doh_bootstrap_ips: Vec::new(),
            encrypted_dns_bootstrap_ips: Vec::new(),
            dns_query_timeout_ms: None,
            resolver_fallback_active: None,
            resolver_fallback_reason: None,
            task_stack_size: 81_920,
            tcp_buffer_size: None,
            udp_recv_buffer_size: None,
            udp_copy_buffer_nums: None,
            max_session_count: None,
            connect_timeout_ms: None,
            tcp_read_write_timeout_ms: None,
            udp_read_write_timeout_ms: None,
            log_level: "warn".to_string(),
            limit_nofile: None,
            filter_injected_resets: None,
        }
    }

    #[test]
    fn builds_config_from_json_payload() {
        let config = config_from_payload(sample_payload()).expect("config");
        assert_eq!(config.socks5.address, "127.0.0.1");
        assert_eq!(config.misc.task_stack_size, 81_920);
    }

    #[test]
    fn preserves_ipv4_and_ipv6_tunnel_addresses() {
        let mut payload = sample_payload();
        payload.tunnel_ipv4 = Some("10.10.10.10/32".to_string());
        payload.tunnel_ipv6 = Some("fd00::1/128".to_string());

        let config = config_from_payload(payload).expect("config");

        assert_eq!(config.tunnel.ipv4.as_deref(), Some("10.10.10.10/32"));
        assert_eq!(config.tunnel.ipv6.as_deref(), Some("fd00::1/128"));
    }

    #[test]
    fn rejects_blank_socks5_address() {
        let mut payload = sample_payload();
        payload.socks5_address = "   ".to_string();

        let err = config_from_payload(payload).expect_err("blank address");

        assert_eq!(err, "socks5Address must not be blank");
    }

    #[test]
    fn rejects_blank_tunnel_name() {
        let mut payload = sample_payload();
        payload.tunnel_name = "   ".to_string();

        let err = config_from_payload(payload).expect_err("blank tunnel name");

        assert_eq!(err, "tunnelName must not be blank");
    }

    #[test]
    fn rejects_invalid_tunnel_json_payload() {
        let err = parse_tunnel_config_json("{").expect_err("invalid json");

        assert!(err.contains("Invalid tunnel config JSON"));
    }

    #[test]
    fn rejects_invalid_handle() {
        assert!(to_handle(0).is_none());
        assert!(to_handle(-1).is_none());
    }

    #[test]
    fn rejects_unknown_tunnel_handle_lookup() {
        let err = match lookup_tunnel_session(99) {
            Ok(_) => panic!("expected unknown handle error"),
            Err(err) => err,
        };

        assert_eq!(err, "Unknown tunnel handle");
    }

    #[test]
    fn rejects_invalid_tun_fd() {
        assert_eq!(validate_tun_fd(-1).expect_err("invalid tun fd"), "Invalid TUN file descriptor",);
    }

    #[test]
    fn tunnel_state_rejects_duplicate_start() {
        let worker = std::thread::spawn(|| {});
        let state = TunnelSessionState::Running {
            cancel: Arc::new(CancellationToken::new()),
            stats: Arc::new(Stats::new()),
            worker,
        };

        let err = ensure_tunnel_start_allowed(&state).expect_err("duplicate start");

        if let TunnelSessionState::Running { worker, .. } = state {
            let _ = worker.join();
        }
        assert_eq!(err, "Tunnel session is already running");
    }

    #[test]
    fn tunnel_state_rejects_stop_when_ready() {
        let mut state = TunnelSessionState::Ready;
        let err = take_running_tunnel(&mut state).expect_err("ready stop");

        assert_eq!(err, "Tunnel session is not running");
    }

    #[test]
    fn tunnel_stats_when_ready_are_zero() {
        assert_eq!(stats_snapshots_for_state(&TunnelSessionState::Ready).0, (0, 0, 0, 0));
    }

    #[test]
    fn tunnel_state_rejects_destroy_when_running() {
        let worker = std::thread::spawn(|| {});
        let state = TunnelSessionState::Running {
            cancel: Arc::new(CancellationToken::new()),
            stats: Arc::new(Stats::new()),
            worker,
        };

        let err = ensure_tunnel_destroyable(&state).expect_err("running destroy");

        if let TunnelSessionState::Running { worker, .. } = state {
            let _ = worker.join();
        }
        assert_eq!(err, "Cannot destroy a running tunnel session");
    }

    #[test]
    fn tunnel_telemetry_snapshot_reports_stats_and_drains_events() {
        let state = TunnelTelemetryState::new();

        state.mark_started("127.0.0.1:1080".to_string());
        state.record_error("boom".to_string());
        state.mark_stop_requested();

        let first = state.snapshot((7, 70, 8, 80), DnsStatsSnapshot::default(), None, None);
        assert_eq!(first.state, "running");
        assert_eq!(first.health, "degraded");
        assert_eq!(first.active_sessions, 1);
        assert_eq!(first.total_sessions, 1);
        assert_eq!(first.total_errors, 1);
        assert_eq!(first.upstream_address.as_deref(), Some("127.0.0.1:1080"));
        assert_eq!(first.last_error.as_deref(), Some("boom"));
        assert_eq!(first.tunnel_stats.tx_packets, 7);
        assert_eq!(first.tunnel_stats.rx_bytes, 80);
        assert_eq!(first.native_events.len(), 3);

        let second = state.snapshot((9, 90, 10, 100), DnsStatsSnapshot::default(), None, None);
        assert!(second.native_events.is_empty());
        assert_eq!(second.total_errors, 1);
        assert_eq!(second.tunnel_stats.tx_packets, 9);

        state.mark_stopped();
        let stopped = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(stopped.state, "idle");
        assert_eq!(stopped.active_sessions, 0);
        assert_eq!(stopped.native_events.len(), 1);
    }

    #[test]
    fn tunnel_telemetry_and_stats_match_goldens() {
        let ready = TunnelTelemetryState::new().snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_ready", &ready);
        assert_tunnel_stats_golden("tunnel_ready_stats", (0, 0, 0, 0));

        let state = TunnelTelemetryState::new();
        state.mark_started("127.0.0.1:1080".to_string());
        state.record_error("boom".to_string());
        state.mark_stop_requested();

        let running = state.snapshot((7, 70, 8, 80), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_running_degraded_first_poll", &running);
        assert_tunnel_stats_golden("tunnel_running_stats", (7, 70, 8, 80));

        let drained = state.snapshot((9, 90, 10, 100), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_running_degraded_second_poll", &drained);

        state.mark_stopped();
        let stopped = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_stopped", &stopped);
    }

    #[test]
    fn destroy_removes_ready_tunnel_session() {
        let handle = SESSIONS.insert(TunnelSession {
            runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new()),
            state: Mutex::new(TunnelSessionState::Ready),
        }) as jlong;

        let removed = remove_tunnel_session(handle).expect("removed session");
        assert!(matches!(*removed.state.lock().expect("state lock"), TunnelSessionState::Ready,));
        assert_eq!(
            match lookup_tunnel_session(handle) {
                Ok(_) => panic!("expected session removal"),
                Err(err) => err,
            },
            "Unknown tunnel handle",
        );
    }

    #[derive(Clone, Copy, Debug)]
    enum TunnelStateCommand {
        EnsureCreated,
        Start,
        Stop,
        Stats,
        Telemetry,
        Destroy,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum TunnelModelState {
        Absent,
        Ready,
        Running,
    }

    #[derive(Default)]
    struct TunnelSessionHarness {
        active_handle: Option<jlong>,
        stale_handle: Option<jlong>,
    }

    impl TunnelSessionHarness {
        fn tracked_handle(&self) -> jlong {
            self.active_handle.or(self.stale_handle).unwrap_or(0)
        }

        fn ensure_created(&mut self) -> jlong {
            if let Some(handle) = self.active_handle {
                return handle;
            }

            let handle = SESSIONS.insert(TunnelSession {
                runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
                config: Arc::new(config_from_payload(sample_payload()).expect("config")),
                last_error: Arc::new(Mutex::new(None)),
                telemetry: Arc::new(TunnelTelemetryState::new()),
                state: Mutex::new(TunnelSessionState::Ready),
            }) as jlong;
            self.active_handle = Some(handle);
            self.stale_handle = Some(handle);
            handle
        }

        fn start(&mut self) -> Result<(), &'static str> {
            let session = lookup_tunnel_session(self.tracked_handle())?;
            let state = session.state.lock().expect("tunnel state lock");
            ensure_tunnel_start_allowed(&state)?;
            drop(state);

            let cancel = Arc::new(CancellationToken::new());
            let stats = Arc::new(Stats::new());
            stats.tx_packets.fetch_add(7, Ordering::Relaxed);
            stats.tx_bytes.fetch_add(70, Ordering::Relaxed);
            stats.rx_packets.fetch_add(8, Ordering::Relaxed);
            stats.rx_bytes.fetch_add(80, Ordering::Relaxed);

            session.telemetry.mark_started(format!("{}:{}", session.config.socks5.address, session.config.socks5.port));

            let worker_cancel = cancel.clone();
            let worker_telemetry = session.telemetry.clone();
            let worker = std::thread::spawn(move || {
                while !worker_cancel.is_cancelled() {
                    std::thread::sleep(Duration::from_millis(1));
                }
                worker_telemetry.mark_stopped();
            });

            let mut state = session.state.lock().expect("tunnel state lock");
            *state = TunnelSessionState::Running { cancel, stats, worker };
            Ok(())
        }

        fn stop(&mut self) -> Result<(), &'static str> {
            let session = lookup_tunnel_session(self.tracked_handle())?;
            let running = {
                let mut state = session.state.lock().expect("tunnel state lock");
                take_running_tunnel(&mut state)?
            };

            session.telemetry.mark_stop_requested();
            running.0.cancel();
            let _ = running.1.join();
            Ok(())
        }

        fn stats(&self) -> Result<(u64, u64, u64, u64), &'static str> {
            let session = lookup_tunnel_session(self.tracked_handle())?;
            let state = session.state.lock().expect("tunnel state lock");
            Ok(stats_snapshots_for_state(&state).0)
        }

        fn telemetry(&self) -> Result<NativeRuntimeSnapshot, &'static str> {
            let session = lookup_tunnel_session(self.tracked_handle())?;
            let state = session.state.lock().expect("tunnel state lock");
            let (traffic, dns) = stats_snapshots_for_state(&state);
            let resolver_id = session.config.mapdns.as_ref().and_then(|mapdns| mapdns.resolver_id.clone());
            let resolver_protocol = session.config.mapdns.as_ref().and_then(mapdns_resolver_protocol);
            Ok(session.telemetry.snapshot(traffic, dns, resolver_id, resolver_protocol))
        }

        fn destroy(&mut self) -> Result<(), &'static str> {
            let session = lookup_tunnel_session(self.tracked_handle())?;
            let state = session.state.lock().expect("tunnel state lock");
            ensure_tunnel_destroyable(&state)?;
            drop(state);
            let handle = self.active_handle.take().unwrap_or_else(|| self.tracked_handle());
            self.stale_handle = Some(handle);
            let _ = remove_tunnel_session(handle)?;
            Ok(())
        }

        fn cleanup(&mut self) {
            if let Some(handle) = self.active_handle.take() {
                if let Ok(session) = lookup_tunnel_session(handle) {
                    let running = {
                        let mut state = session.state.lock().expect("tunnel state lock");
                        take_running_tunnel(&mut state).ok()
                    };
                    if let Some(running) = running {
                        running.0.cancel();
                        let _ = running.1.join();
                    }
                }
                let _ = remove_tunnel_session(handle);
                self.stale_handle = Some(handle);
            }
        }
    }

    impl Drop for TunnelSessionHarness {
        fn drop(&mut self) {
            self.cleanup();
        }
    }

    fn assert_tunnel_snapshot_golden(name: &str, snapshot: &NativeRuntimeSnapshot) {
        let actual = canonicalize_json_with(
            &serde_json::to_string(snapshot).expect("serialize tunnel snapshot"),
            scrub_runtime_timestamps,
        )
        .expect("canonicalize tunnel telemetry");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn assert_tunnel_stats_golden(name: &str, stats: (u64, u64, u64, u64)) {
        let actual = canonicalize_json_with(
            &json!({
                "txPackets": stats.0,
                "txBytes": stats.1,
                "rxPackets": stats.2,
                "rxBytes": stats.3,
            })
            .to_string(),
            |_| {},
        )
        .expect("canonicalize tunnel stats");
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

    fn tunnel_absent_error(handle: jlong) -> &'static str {
        if to_handle(handle).is_some() {
            "Unknown tunnel handle"
        } else {
            "Invalid tunnel handle"
        }
    }

    fn tunnel_state_command_strategy() -> impl Strategy<Value = Vec<TunnelStateCommand>> {
        vec(
            prop_oneof![
                Just(TunnelStateCommand::EnsureCreated),
                Just(TunnelStateCommand::Start),
                Just(TunnelStateCommand::Stop),
                Just(TunnelStateCommand::Stats),
                Just(TunnelStateCommand::Telemetry),
                Just(TunnelStateCommand::Destroy),
            ],
            1..32,
        )
    }

    proptest! {
        #[test]
        fn fuzz_tunnel_json_parser_never_panics(input in vec(any::<u8>(), 0..512)) {
            let payload = String::from_utf8_lossy(&input).into_owned();
            let _ = parse_tunnel_config_json(&payload);
        }

        #[test]
        fn fuzz_tunnel_payload_mapping_never_panics(payload in tunnel_payload_strategy()) {
            let _ = config_from_payload(payload);
        }

        #[test]
        fn valid_tunnel_payloads_preserve_core_fields(payload in valid_tunnel_payload_strategy()) {
            let expected_name = payload.tunnel_name.clone();
            let expected_mtu = payload.tunnel_mtu;
            let expected_multi_queue = payload.multi_queue;
            let expected_address = payload.socks5_address.clone();
            let expected_port = payload.socks5_port;
            let expected_pipeline = payload.socks5_pipeline;
            let expected_stack_size = payload.task_stack_size;
            let expected_log_level = payload.log_level.clone();

            let config = config_from_payload(payload).expect("valid tunnel payload");

            assert_eq!(config.tunnel.name, expected_name);
            assert_eq!(config.tunnel.mtu, expected_mtu);
            assert_eq!(config.tunnel.multi_queue, expected_multi_queue);
            assert_eq!(config.socks5.address, expected_address);
            assert_eq!(config.socks5.port, expected_port);
            assert_eq!(config.socks5.pipeline, expected_pipeline);
            assert_eq!(config.misc.task_stack_size, expected_stack_size);
            assert_eq!(config.misc.log_level, expected_log_level);
        }

        #[test]
        fn tunnel_session_state_machine(commands in tunnel_state_command_strategy()) {
            let mut harness = TunnelSessionHarness::default();
            let mut model = TunnelModelState::Absent;

            for command in commands {
                match command {
                    TunnelStateCommand::EnsureCreated => {
                        let handle = harness.ensure_created();
                        prop_assert!(lookup_tunnel_session(handle).is_ok());
                        if matches!(model, TunnelModelState::Absent) {
                            model = TunnelModelState::Ready;
                        }
                    }
                    TunnelStateCommand::Start => {
                        match model {
                            TunnelModelState::Absent => {
                                let err = harness.start().expect_err("absent start must fail");
                                prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                            }
                            TunnelModelState::Ready => {
                                harness.start().expect("ready start");
                                model = TunnelModelState::Running;
                            }
                            TunnelModelState::Running => {
                                let err = harness.start().expect_err("duplicate start must fail");
                                prop_assert_eq!(err, "Tunnel session is already running");
                            }
                        }
                    }
                    TunnelStateCommand::Stop => {
                        match model {
                            TunnelModelState::Absent => {
                                let err = harness.stop().expect_err("absent stop must fail");
                                prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                            }
                            TunnelModelState::Ready => {
                                let err = harness.stop().expect_err("ready stop must fail");
                                prop_assert_eq!(err, "Tunnel session is not running");
                            }
                            TunnelModelState::Running => {
                                harness.stop().expect("running stop");
                                model = TunnelModelState::Ready;
                            }
                        }
                    }
                    TunnelStateCommand::Stats => {
                        match model {
                            TunnelModelState::Absent => {
                                let err = harness.stats().expect_err("absent stats must fail");
                                prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                            }
                            TunnelModelState::Ready => {
                                prop_assert_eq!(harness.stats().expect("ready stats"), (0, 0, 0, 0));
                            }
                            TunnelModelState::Running => {
                                prop_assert_eq!(harness.stats().expect("running stats"), (7, 70, 8, 80));
                            }
                        }
                    }
                    TunnelStateCommand::Telemetry => {
                        match model {
                            TunnelModelState::Absent => {
                                let err = harness.telemetry().expect_err("absent telemetry must fail");
                                prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                            }
                            TunnelModelState::Ready => {
                                let snapshot = harness.telemetry().expect("ready telemetry");
                                prop_assert_eq!(snapshot.state, "idle");
                                prop_assert_eq!(snapshot.tunnel_stats.tx_packets, 0);
                            }
                            TunnelModelState::Running => {
                                let snapshot = harness.telemetry().expect("running telemetry");
                                prop_assert_eq!(snapshot.state, "running");
                                prop_assert_eq!(snapshot.active_sessions, 1);
                                prop_assert_eq!(snapshot.tunnel_stats.tx_packets, 7);
                                prop_assert_eq!(snapshot.tunnel_stats.rx_bytes, 80);
                            }
                        }
                    }
                    TunnelStateCommand::Destroy => {
                        match model {
                            TunnelModelState::Absent => {
                                let err = harness.destroy().expect_err("absent destroy must fail");
                                prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                            }
                            TunnelModelState::Ready => {
                                harness.destroy().expect("ready destroy");
                                model = TunnelModelState::Absent;
                            }
                            TunnelModelState::Running => {
                                let err = harness.destroy().expect_err("running destroy must fail");
                                prop_assert_eq!(err, "Cannot destroy a running tunnel session");
                            }
                        }
                    }
                }

                match model {
                    TunnelModelState::Absent => {
                        if to_handle(harness.tracked_handle()).is_some() {
                            let err = match lookup_tunnel_session(harness.tracked_handle()) {
                                Ok(_) => panic!("absent tunnel must be removed"),
                                Err(err) => err,
                            };
                            prop_assert_eq!(err, "Unknown tunnel handle");
                        }
                    }
                    TunnelModelState::Ready => {
                        let session = lookup_tunnel_session(harness.tracked_handle()).expect("ready tunnel");
                        let state = session.state.lock().expect("tunnel state lock");
                        prop_assert!(matches!(*state, TunnelSessionState::Ready));
                    }
                    TunnelModelState::Running => {
                        let session = lookup_tunnel_session(harness.tracked_handle()).expect("running tunnel");
                        let state = session.state.lock().expect("tunnel state lock");
                        let is_running = matches!(*state, TunnelSessionState::Running { .. });
                        prop_assert!(is_running);
                    }
                }
            }
        }
    }

    #[test]
    #[ignore = "startup latency smoke"]
    fn startup_latency_smoke() {
        let start = Instant::now();
        let _ = config_from_payload(sample_payload()).expect("config");
        assert!(start.elapsed() < Duration::from_millis(50), "tunnel config startup path regressed");
    }
}
