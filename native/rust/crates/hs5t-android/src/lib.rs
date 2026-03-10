use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

#[cfg(test)]
use std::time::{Duration, Instant};

use android_support::{
    init_android_logging, throw_illegal_argument, throw_illegal_state, throw_io_exception,
    throw_runtime_exception, HandleRegistry, JNI_VERSION,
};
use hs5t_config::{Config, MapDnsConfig, MiscConfig, Socks5Config, TunnelConfig};
use hs5t_core::Stats;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::{Lazy, OnceCell};
use serde::Deserialize;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

static RUNTIME: OnceCell<Runtime> = OnceCell::new();
static SESSIONS: Lazy<HandleRegistry<TunnelSession>> = Lazy::new(HandleRegistry::new);

struct TunnelSession {
    config: Arc<Config>,
    last_error: Arc<Mutex<Option<String>>>,
    state: Mutex<TunnelSessionState>,
}

enum TunnelSessionState {
    Ready,
    Running {
        cancel: Arc<CancellationToken>,
        stats: Arc<Stats>,
        worker: JoinHandle<()>,
    },
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
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    init_android_logging("hs5t-native");
    JNI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniCreate(
    mut env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_session(&mut env, config_json)
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel session creation panicked");
        0
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniStart(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
    tun_fd: jint,
) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_session(&mut env, handle, tun_fd)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session start panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniStop(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        stop_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session stop panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniGetStats(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jlongArray {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        stats_session(&mut env, handle)
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel stats retrieval panicked");
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniDestroy(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        destroy_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session destroy panicked"));
}

fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid tunnel config payload");
            return 0;
        }
    };
    let payload = match serde_json::from_str::<TunnelConfigPayload>(&json) {
        Ok(payload) => payload,
        Err(err) => {
            throw_illegal_argument(env, format!("Invalid tunnel config JSON: {err}"));
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

    SESSIONS.insert(TunnelSession {
        config,
        last_error: Arc::new(Mutex::new(None)),
        state: Mutex::new(TunnelSessionState::Ready),
    }) as jlong
}

fn start_session(env: &mut JNIEnv, handle: jlong, tun_fd: jint) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid tunnel handle");
            return;
        }
    };
    if tun_fd < 0 {
        throw_illegal_argument(env, "Invalid TUN file descriptor");
        return;
    }
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown tunnel handle");
        return;
    };
    let runtime = match get_runtime() {
        Some(runtime) => runtime,
        None => {
            throw_io_exception(env, "Failed to initialize Tokio runtime");
            return;
        }
    };

    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());
    let config = session.config.clone();
    let last_error = session.last_error.clone();

    let mut state = session.state.lock().expect("tunnel session poisoned");
    if matches!(*state, TunnelSessionState::Running { .. }) {
        throw_illegal_state(env, "Tunnel session is already running");
        return;
    }
    drop(state);

    if let Ok(mut guard) = session.last_error.lock() {
        *guard = None;
    }

    let worker_cancel = cancel.clone();
    let worker_stats = stats.clone();
    let worker = std::thread::spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime.block_on(hs5t_core::run_tunnel(
                config,
                tun_fd,
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
            }
            Err(_) => {
                log::error!("tunnel worker panicked");
                if let Ok(mut guard) = last_error.lock() {
                    *guard = Some("Tunnel worker panicked".to_string());
                }
            }
        }
    });

    state = session.state.lock().expect("tunnel session poisoned");
    match &*state {
        TunnelSessionState::Ready => {
            *state = TunnelSessionState::Running {
                cancel,
                stats,
                worker,
            };
        }
        TunnelSessionState::Running { .. } => {
            drop(state);
            cancel.cancel();
            if worker.join().is_err() {
                log::error!("tunnel worker panicked while abandoning duplicate start");
            }
            throw_illegal_state(env, "Tunnel session is already running");
        }
    }
}

fn stop_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid tunnel handle");
            return;
        }
    };
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown tunnel handle");
        return;
    };

    let running = {
        let mut state = session.state.lock().expect("tunnel session poisoned");
        match std::mem::replace(&mut *state, TunnelSessionState::Ready) {
            TunnelSessionState::Ready => {
                throw_illegal_state(env, "Tunnel session is not running");
                return;
            }
            TunnelSessionState::Running {
                cancel,
                stats: _,
                worker,
            } => (cancel, worker),
        }
    };

    running.0.cancel();
    if running.1.join().is_err() {
        log::error!("tunnel worker panicked during shutdown");
    }
}

fn stats_session(env: &mut JNIEnv, handle: jlong) -> jlongArray {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid tunnel handle");
            return std::ptr::null_mut();
        }
    };
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown tunnel handle");
        return std::ptr::null_mut();
    };

    let snapshot = {
        let state = session.state.lock().expect("tunnel session poisoned");
        match &*state {
            TunnelSessionState::Ready => (0, 0, 0, 0),
            TunnelSessionState::Running { stats, .. } => stats.snapshot(),
        }
    };

    match env.new_long_array(4) {
        Ok(arr) => {
            let values: [i64; 4] = [
                snapshot.0 as i64,
                snapshot.1 as i64,
                snapshot.2 as i64,
                snapshot.3 as i64,
            ];
            if env.set_long_array_region(&arr, 0, &values).is_ok() {
                arr.into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid tunnel handle");
            return;
        }
    };
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown tunnel handle");
        return;
    };
    let state = session.state.lock().expect("tunnel session poisoned");
    if matches!(*state, TunnelSessionState::Running { .. }) {
        throw_illegal_state(env, "Cannot destroy a running tunnel session");
        return;
    }
    drop(state);
    let _ = SESSIONS.remove(handle);
}

fn get_runtime() -> Option<&'static Runtime> {
    RUNTIME
        .get_or_try_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
        })
        .ok()
}

fn config_from_payload(payload: TunnelConfigPayload) -> Result<Config, String> {
    if payload.socks5_address.is_blank() {
        return Err("socks5Address must not be blank".to_string());
    }
    if payload.tunnel_name.is_blank() {
        return Err("tunnelName must not be blank".to_string());
    }

    let mut misc = MiscConfig {
        task_stack_size: payload.task_stack_size,
        log_level: payload.log_level,
        ..MiscConfig::default()
    };
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

    let mapdns = payload.mapdns_address.map(|address| MapDnsConfig {
        address,
        port: payload.mapdns_port.unwrap_or(53),
        network: payload.mapdns_network,
        netmask: payload.mapdns_netmask,
        cache_size: payload.mapdns_cache_size.unwrap_or(10_000),
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

fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
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
        }
    }

    #[test]
    fn builds_config_from_json_payload() {
        let config = config_from_payload(sample_payload()).expect("config");
        assert_eq!(config.socks5.address, "127.0.0.1");
        assert_eq!(config.misc.task_stack_size, 81_920);
    }

    #[test]
    fn rejects_invalid_handle() {
        assert!(to_handle(0).is_none());
        assert!(to_handle(-1).is_none());
    }

    #[test]
    #[ignore = "startup latency smoke"]
    fn startup_latency_smoke() {
        let start = Instant::now();
        let _ = config_from_payload(sample_payload()).expect("config");
        assert!(
            start.elapsed() < Duration::from_millis(50),
            "tunnel config startup path regressed"
        );
    }
}
