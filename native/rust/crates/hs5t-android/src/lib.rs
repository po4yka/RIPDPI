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

fn tunnel_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_session(&mut env, config_json)
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel session creation panicked");
        0
    })
}

fn tunnel_start_entry(mut env: JNIEnv, handle: jlong, tun_fd: jint) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_session(&mut env, handle, tun_fd)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session start panicked"));
}

fn tunnel_stop_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        stop_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session stop panicked"));
}

fn tunnel_stats_entry(mut env: JNIEnv, handle: jlong) -> jlongArray {
    init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        stats_session(&mut env, handle)
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel stats retrieval panicked");
        std::ptr::null_mut()
    })
}

fn tunnel_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        destroy_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session destroy panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniCreate(
    env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    tunnel_create_entry(env, config_json)
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
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniStart(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
    tun_fd: jint,
) {
    tunnel_start_entry(env, handle, tun_fd);
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
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniStop(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    tunnel_stop_entry(env, handle);
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
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniGetStats(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jlongArray {
    tunnel_stats_entry(env, handle)
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
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniDestroy(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    tunnel_destroy_entry(env, handle);
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

    SESSIONS.insert(TunnelSession {
        config,
        last_error: Arc::new(Mutex::new(None)),
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
    if let Err(message) = ensure_tunnel_start_allowed(&state) {
        throw_illegal_state(env, message);
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
        stats_snapshot_for_state(&state)
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

fn parse_tunnel_config_json(json: &str) -> Result<TunnelConfigPayload, String> {
    serde_json::from_str::<TunnelConfigPayload>(json)
        .map_err(|err| format!("Invalid tunnel config JSON: {err}"))
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
    if matches!(*state, TunnelSessionState::Running { .. }) {
        Err("Tunnel session is already running")
    } else {
        Ok(())
    }
}

fn take_running_tunnel(
    state: &mut TunnelSessionState,
) -> Result<(Arc<CancellationToken>, JoinHandle<()>), &'static str> {
    match std::mem::replace(state, TunnelSessionState::Ready) {
        TunnelSessionState::Ready => Err("Tunnel session is not running"),
        TunnelSessionState::Running {
            cancel,
            stats: _,
            worker,
        } => Ok((cancel, worker)),
    }
}

fn stats_snapshot_for_state(state: &TunnelSessionState) -> (u64, u64, u64, u64) {
    match state {
        TunnelSessionState::Ready => (0, 0, 0, 0),
        TunnelSessionState::Running { stats, .. } => stats.snapshot(),
    }
}

fn ensure_tunnel_destroyable(state: &TunnelSessionState) -> Result<(), &'static str> {
    if matches!(*state, TunnelSessionState::Running { .. }) {
        Err("Cannot destroy a running tunnel session")
    } else {
        Ok(())
    }
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
        assert_eq!(
            validate_tun_fd(-1).expect_err("invalid tun fd"),
            "Invalid TUN file descriptor",
        );
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
        assert_eq!(
            stats_snapshot_for_state(&TunnelSessionState::Ready),
            (0, 0, 0, 0)
        );
    }

    #[test]
    fn destroy_removes_ready_tunnel_session() {
        let handle = SESSIONS.insert(TunnelSession {
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            state: Mutex::new(TunnelSessionState::Ready),
        }) as jlong;

        let removed = remove_tunnel_session(handle).expect("removed session");
        assert!(matches!(
            *removed.state.lock().expect("state lock"),
            TunnelSessionState::Ready,
        ));
        assert_eq!(
            match lookup_tunnel_session(handle) {
                Ok(_) => panic!("expected session removal"),
                Err(err) => err,
            },
            "Unknown tunnel handle",
        );
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
