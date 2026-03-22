use std::io;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use android_support::{
    throw_illegal_argument, throw_illegal_state, throw_io_exception, throw_runtime_exception, HandleRegistry,
};
use hs5t_core::{DnsStatsSnapshot, Stats};
use jni::objects::JString;
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;
use once_cell::sync::{Lazy, OnceCell};
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use crate::config::{config_from_payload, mapdns_resolver_protocol, parse_tunnel_config_json, to_hs5t_config};
use crate::telemetry::TunnelTelemetryState;
use crate::to_handle;

pub(crate) static SESSIONS: Lazy<HandleRegistry<TunnelSession>> = Lazy::new(HandleRegistry::new);
static SHARED_TUNNEL_RUNTIME: OnceCell<Arc<Runtime>> = OnceCell::new();

pub(crate) struct TunnelSession {
    pub(crate) runtime: Arc<Runtime>,
    pub(crate) config: Arc<ripdpi_tunnel_config::Config>,
    pub(crate) last_error: Arc<Mutex<Option<String>>>,
    pub(crate) telemetry: Arc<TunnelTelemetryState>,
    pub(crate) state: Mutex<TunnelSessionState>,
}

pub(crate) enum TunnelSessionState {
    Ready,
    Starting,
    Running { cancel: Arc<CancellationToken>, stats: Arc<Stats>, worker: JoinHandle<()> },
}

fn build_shared_tunnel_runtime() -> io::Result<Arc<Runtime>> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_stack_size(1024 * 1024)
        .thread_name("hs5t-tokio")
        .enable_all()
        .build()
        .map(Arc::new)
}

fn shared_tunnel_runtime() -> io::Result<Arc<Runtime>> {
    SHARED_TUNNEL_RUNTIME.get_or_try_init(build_shared_tunnel_runtime).map(Arc::clone)
}

pub(crate) fn tunnel_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    android_support::init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel session creation panicked");
            0
        },
    )
}

pub(crate) fn tunnel_start_entry(mut env: JNIEnv, handle: jlong, tun_fd: jint) {
    android_support::init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle, tun_fd)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session start panicked"));
}

pub(crate) fn tunnel_stop_entry(mut env: JNIEnv, handle: jlong) {
    android_support::init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session stop panicked"));
}

pub(crate) fn tunnel_stats_entry(mut env: JNIEnv, handle: jlong) -> jlongArray {
    android_support::init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stats_session(&mut env, handle))).unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel stats retrieval panicked");
        std::ptr::null_mut()
    })
}

pub(crate) fn tunnel_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jni::sys::jstring {
    android_support::init_android_logging("hs5t-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| telemetry_session(&mut env, handle))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel telemetry retrieval panicked");
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn tunnel_destroy_entry(mut env: JNIEnv, handle: jlong) {
    android_support::init_android_logging("hs5t-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle)))
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
    let runtime = match shared_tunnel_runtime() {
        Ok(runtime) => runtime,
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
    let owned_fd = match nix::unistd::dup(tun_fd) {
        Ok(fd) => fd,
        Err(err) => {
            throw_io_exception(env, format!("Failed to dup TUN fd: {err}"));
            return;
        }
    };
    let runtime = session.runtime.clone();

    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());
    let config = session.config.clone();
    let last_error = session.last_error.clone();
    let telemetry = session.telemetry.clone();

    // Wire the DNS latency histogram: clone shares the Arc<Mutex<Histogram>>
    // inside LatencyHistogram so the closure and telemetry state observe the
    // same underlying data without requiring hs5t-core to import ripdpi-telemetry.
    let dns_histogram = telemetry.dns_histogram.clone();
    stats.set_dns_latency_observer(Arc::new(move |ms| dns_histogram.record(ms)));

    {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Err(message) = ensure_tunnel_start_allowed(&state) {
            // Bug H4 fix: close the dup'd fd before returning.
            let _ = nix::unistd::close(owned_fd);
            throw_illegal_state(env, message);
            return;
        }
        // Bug H3 fix: atomically transition to Starting while holding the lock,
        // so no concurrent call can also see Ready.
        *state = TunnelSessionState::Starting;
    }

    {
        let mut guard = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = None;
    }
    telemetry.mark_started(format!("{}:{}", session.config.socks5.address, session.config.socks5.port));

    let worker_cancel = cancel.clone();
    let worker_stats = stats.clone();
    let worker = match std::thread::Builder::new().name("hs5t-worker".into()).spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let hs5t_cfg = Arc::new(to_hs5t_config(&config));
            runtime.block_on(hs5t_core::run_tunnel(hs5t_cfg, owned_fd, (*worker_cancel).clone(), worker_stats.clone()))
        }));

        match result {
            Ok(Ok(())) => {}
            Ok(Err(err)) => {
                log::error!("tunnel worker exited with error: {err}");
                let mut guard = last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                *guard = Some(err.to_string());
                drop(guard);
                telemetry.record_error(err.to_string());
            }
            Err(panic) => {
                let msg = if let Some(s) = panic.downcast_ref::<&str>() {
                    s.to_string()
                } else if let Some(s) = panic.downcast_ref::<String>() {
                    s.clone()
                } else {
                    "unknown panic".to_string()
                };
                log::error!("tunnel worker panicked: {msg}");
                let mut guard = last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                *guard = Some(format!("Tunnel worker panicked: {msg}"));
                drop(guard);
                telemetry.record_error(format!("Tunnel worker panicked: {msg}"));
            }
        }
        telemetry.mark_stopped();
    }) {
        Ok(worker) => worker,
        Err(err) => {
            rollback_failed_tunnel_start(&session, owned_fd, format!("failed to spawn tunnel worker thread: {err}"));
            throw_io_exception(env, format!("Failed to spawn tunnel worker thread: {err}"));
            return;
        }
    };

    let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
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
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
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
        let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
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
        let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
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
    let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(message) = ensure_tunnel_destroyable(&state) {
        throw_illegal_state(env, message);
        return;
    }
    drop(state);
    let _ = remove_tunnel_session(handle);
}

pub(crate) fn lookup_tunnel_session(handle: jlong) -> Result<Arc<TunnelSession>, &'static str> {
    let handle = to_handle(handle).ok_or("Invalid tunnel handle")?;
    SESSIONS.get(handle).ok_or("Unknown tunnel handle")
}

pub(crate) fn remove_tunnel_session(handle: jlong) -> Result<Arc<TunnelSession>, &'static str> {
    let handle = to_handle(handle).ok_or("Invalid tunnel handle")?;
    SESSIONS.remove(handle).ok_or("Unknown tunnel handle")
}

pub(crate) fn validate_tun_fd(tun_fd: jint) -> Result<(), &'static str> {
    if tun_fd < 0 {
        Err("Invalid TUN file descriptor")
    } else {
        Ok(())
    }
}

pub(crate) fn ensure_tunnel_start_allowed(state: &TunnelSessionState) -> Result<(), &'static str> {
    match *state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting => Err("Tunnel session is already starting"),
        TunnelSessionState::Running { .. } => Err("Tunnel session is already running"),
    }
}

pub(crate) fn take_running_tunnel(
    state: &mut TunnelSessionState,
) -> Result<(Arc<CancellationToken>, JoinHandle<()>), &'static str> {
    match std::mem::replace(state, TunnelSessionState::Ready) {
        TunnelSessionState::Ready | TunnelSessionState::Starting => Err("Tunnel session is not running"),
        TunnelSessionState::Running { cancel, stats: _, worker } => Ok((cancel, worker)),
    }
}

pub(crate) fn stats_snapshots_for_state(state: &TunnelSessionState) -> ((u64, u64, u64, u64), DnsStatsSnapshot) {
    match state {
        TunnelSessionState::Ready | TunnelSessionState::Starting => ((0, 0, 0, 0), DnsStatsSnapshot::default()),
        TunnelSessionState::Running { stats, .. } => (stats.snapshot(), stats.dns_snapshot()),
    }
}

pub(crate) fn ensure_tunnel_destroyable(state: &TunnelSessionState) -> Result<(), &'static str> {
    match *state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting => Err("Cannot destroy a starting tunnel session"),
        TunnelSessionState::Running { .. } => Err("Cannot destroy a running tunnel session"),
    }
}

fn rollback_failed_tunnel_start(session: &TunnelSession, owned_fd: i32, message: String) {
    let _ = nix::unistd::close(owned_fd);
    {
        let mut guard = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = Some(message.clone());
    }
    session.telemetry.record_error(message);
    session.telemetry.mark_stopped();
    {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *state = TunnelSessionState::Ready;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::sample_payload;
    use crate::telemetry::NativeRuntimeSnapshot;
    use android_support::describe_exception;
    use hs5t_core::Stats;
    use jni::objects::{JLongArray, JObject, JString};
    use jni::{InitArgsBuilder, JNIEnv, JNIVersion, JavaVM};
    use once_cell::sync::{Lazy, OnceCell};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use serde_json::Value;
    use std::sync::atomic::Ordering;
    use std::time::Duration;

    static TEST_JVM: OnceCell<JavaVM> = OnceCell::new();
    static JNI_TEST_MUTEX: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

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
    fn shared_tunnel_runtime_is_reused() {
        let first = shared_tunnel_runtime().expect("shared runtime");
        let second = shared_tunnel_runtime().expect("shared runtime");

        assert!(Arc::ptr_eq(&first, &second));
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

    #[test]
    fn rollback_failed_tunnel_start_restores_ready_state() {
        let session = TunnelSession {
            runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new()),
            state: Mutex::new(TunnelSessionState::Starting),
        };
        session.telemetry.mark_started("127.0.0.1:1080".to_string());

        rollback_failed_tunnel_start(&session, -1, "spawn failed".to_string());

        assert!(matches!(*session.state.lock().expect("state lock"), TunnelSessionState::Ready));
        assert_eq!(session.last_error.lock().expect("last error lock").as_deref(), Some("spawn failed"));
        assert!(ensure_tunnel_start_allowed(&session.state.lock().expect("state lock")).is_ok());
        assert!(ensure_tunnel_destroyable(&session.state.lock().expect("state lock")).is_ok());

        let snapshot = session.telemetry.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snapshot.state, "idle");
        assert_eq!(snapshot.active_sessions, 0);
        assert_eq!(snapshot.total_sessions, 1);
        assert_eq!(snapshot.total_errors, 1);
        assert_eq!(snapshot.last_error.as_deref(), Some("spawn failed"));
    }

    #[test]
    fn exported_jni_create_and_destroy_round_trip_without_exception() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");
        let mut handle = TunnelHandle::new();

        let stale_handle = handle.raw();
        with_env(|env| {
            jni_destroy(env, stale_handle);
            assert_no_exception(env);
        });
        handle.disarm();
    }

    #[test]
    fn exported_jni_rejects_malformed_config_json() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");

        with_env(|env| {
            let handle = jni_create(env, "{");
            assert_eq!(handle, 0);
            let exception = take_exception(env);
            assert!(exception.starts_with("java.lang.IllegalArgumentException: Invalid tunnel config JSON:"));
        });
    }

    #[test]
    fn exported_jni_reports_ready_stats_and_telemetry() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");
        let handle = TunnelHandle::new();

        with_env(|env| {
            let raw_stats = jni_get_stats(env, handle.raw());
            let stats = decode_long_array(env, raw_stats).expect("stats array");
            assert_no_exception(env);
            assert_eq!(stats, vec![0, 0, 0, 0]);

            let raw_telemetry = jni_get_telemetry(env, handle.raw());
            let telemetry_json = decode_jstring(env, raw_telemetry).expect("telemetry json");
            assert_no_exception(env);
            let snapshot: Value = serde_json::from_str(&telemetry_json).expect("decode telemetry");
            assert_eq!(snapshot["state"], "idle");
            assert_eq!(snapshot["health"], "idle");
            assert_eq!(snapshot["activeSessions"], 0);
            assert_eq!(snapshot["tunnelStats"]["txPackets"], 0);
            assert_eq!(snapshot["tunnelStats"]["rxBytes"], 0);
        });
    }

    #[test]
    fn exported_jni_start_rejects_invalid_tun_fd() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");
        let handle = TunnelHandle::new();

        with_env(|env| {
            jni_start(env, handle.raw(), -1);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid TUN file descriptor",);
        });
    }

    #[test]
    fn exported_jni_invalid_handles_throw_and_return_null_for_reference_results() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");

        for handle in [0, -1] {
            with_env(|env| {
                jni_start(env, handle, -1);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid tunnel handle");
            });

            with_env(|env| {
                jni_stop(env, handle);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid tunnel handle");
            });

            with_env(|env| {
                let stats = jni_get_stats(env, handle);
                assert!(decode_long_array(env, stats).is_none());
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid tunnel handle");
            });

            with_env(|env| {
                let telemetry = jni_get_telemetry(env, handle);
                assert!(decode_jstring(env, telemetry).is_none());
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid tunnel handle");
            });

            with_env(|env| {
                jni_destroy(env, handle);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid tunnel handle");
            });
        }
    }

    #[test]
    fn exported_jni_rejects_stale_handles_as_unknown() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");
        let mut handle = TunnelHandle::new();

        let stale_handle = handle.raw();
        with_env(|env| {
            jni_destroy(env, stale_handle);
            assert_no_exception(env);
        });
        handle.disarm();

        with_env(|env| {
            jni_start(env, stale_handle, -1);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown tunnel handle");
        });

        with_env(|env| {
            jni_stop(env, stale_handle);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown tunnel handle");
        });

        with_env(|env| {
            let stats = jni_get_stats(env, stale_handle);
            assert!(decode_long_array(env, stats).is_none());
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown tunnel handle");
        });

        with_env(|env| {
            let telemetry = jni_get_telemetry(env, stale_handle);
            assert!(decode_jstring(env, telemetry).is_none());
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown tunnel handle");
        });

        with_env(|env| {
            jni_destroy(env, stale_handle);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown tunnel handle");
        });
    }

    struct TunnelHandle {
        raw: jlong,
    }

    impl TunnelHandle {
        fn new() -> Self {
            let raw = with_env(|env| {
                let handle = jni_create(env, &sample_payload_json());
                assert_no_exception(env);
                handle
            });
            assert_ne!(raw, 0, "jniCreate should return a non-zero tunnel handle");
            Self { raw }
        }

        fn raw(&self) -> jlong {
            self.raw
        }

        fn disarm(&mut self) {
            self.raw = 0;
        }
    }

    impl Drop for TunnelHandle {
        fn drop(&mut self) {
            if self.raw == 0 {
                return;
            }
            with_env(|env| {
                jni_destroy(env, self.raw);
                let _ = describe_exception(env);
            });
        }
    }

    fn test_jvm() -> &'static JavaVM {
        TEST_JVM.get_or_init(|| {
            let args = InitArgsBuilder::new()
                .version(JNIVersion::V8)
                .option("-Xcheck:jni")
                .build()
                .expect("build test JVM init args");
            JavaVM::new(args).expect("create in-process test JVM")
        })
    }

    fn with_env<R>(f: impl FnOnce(&mut JNIEnv<'_>) -> R) -> R {
        let mut env = test_jvm().attach_current_thread().expect("attach current thread to test JVM");
        f(&mut env)
    }

    fn jni_create(env: &mut JNIEnv<'_>, config_json: &str) -> jlong {
        let config_json = env.new_string(config_json).expect("create config json string");
        crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            config_json,
        )
    }

    fn jni_start(env: &mut JNIEnv<'_>, handle: jlong, tun_fd: jint) {
        crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
            tun_fd,
        );
    }

    fn jni_stop(env: &mut JNIEnv<'_>, handle: jlong) {
        crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        );
    }

    fn jni_get_stats(env: &mut JNIEnv<'_>, handle: jlong) -> jlongArray {
        crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_get_telemetry(env: &mut JNIEnv<'_>, handle: jlong) -> jni::sys::jstring {
        crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_destroy(env: &mut JNIEnv<'_>, handle: jlong) {
        crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        );
    }

    fn assert_no_exception(env: &mut JNIEnv<'_>) {
        assert!(describe_exception(env).is_none(), "unexpected pending Java exception");
    }

    fn take_exception(env: &mut JNIEnv<'_>) -> String {
        describe_exception(env).expect("expected Java exception")
    }

    fn decode_jstring(env: &mut JNIEnv<'_>, raw: jni::sys::jstring) -> Option<String> {
        if raw.is_null() {
            return None;
        }
        let string = unsafe { JString::from_raw(raw) };
        Some(env.get_string(&string).expect("read jstring").into())
    }

    fn decode_long_array(env: &mut JNIEnv<'_>, raw: jlongArray) -> Option<Vec<jlong>> {
        if raw.is_null() {
            return None;
        }
        let array = unsafe { JLongArray::from_raw(raw) };
        let len = env.get_array_length(&array).expect("stats array length") as usize;
        let mut values = vec![0; len];
        env.get_long_array_region(&array, 0, &mut values).expect("read stats array");
        Some(values)
    }

    fn sample_payload_json() -> String {
        r#"{
            "tunnelName": "tun0",
            "tunnelMtu": 8500,
            "multiQueue": false,
            "tunnelIpv4": null,
            "tunnelIpv6": null,
            "socks5Address": "127.0.0.1",
            "socks5Port": 1080,
            "socks5Udp": "udp",
            "socks5UdpAddress": null,
            "socks5Pipeline": null,
            "username": null,
            "password": null,
            "mapdnsAddress": null,
            "mapdnsPort": null,
            "mapdnsNetwork": null,
            "mapdnsNetmask": null,
            "mapdnsCacheSize": null,
            "encryptedDnsResolverId": null,
            "encryptedDnsProtocol": null,
            "encryptedDnsHost": null,
            "encryptedDnsPort": null,
            "encryptedDnsTlsServerName": null,
            "encryptedDnsDohUrl": null,
            "encryptedDnsDnscryptProviderName": null,
            "encryptedDnsDnscryptPublicKey": null,
            "dohResolverId": null,
            "dohUrl": null,
            "dohBootstrapIps": [],
            "encryptedDnsBootstrapIps": [],
            "dnsQueryTimeoutMs": null,
            "resolverFallbackActive": null,
            "resolverFallbackReason": null,
            "taskStackSize": 81920,
            "tcpBufferSize": null,
            "udpRecvBufferSize": null,
            "udpCopyBufferNums": null,
            "maxSessionCount": null,
            "connectTimeoutMs": null,
            "tcpReadWriteTimeoutMs": null,
            "udpReadWriteTimeoutMs": null,
            "logLevel": "warn",
            "limitNofile": null,
            "filterInjectedResets": null
        }"#
        .to_string()
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
}

#[cfg(feature = "loom")]
mod loom_tests {
    use super::*;
    use loom::sync::{Arc, Mutex};

    #[test]
    fn loom_starting_blocks_concurrent_start() {
        loom::model(|| {
            let state = Arc::new(Mutex::new(TunnelSessionState::Starting));

            let state1 = state.clone();
            let t1 = loom::thread::spawn(move || {
                let s = state1.lock().unwrap();
                ensure_tunnel_start_allowed(&s).is_ok()
            });

            let state2 = state.clone();
            let t2 = loom::thread::spawn(move || {
                let s = state2.lock().unwrap();
                ensure_tunnel_start_allowed(&s).is_ok()
            });

            let r1 = t1.join().unwrap();
            let r2 = t2.join().unwrap();
            // Both threads observe Starting; neither is allowed to start.
            assert!(!r1, "start must be rejected when Starting");
            assert!(!r2, "start must be rejected when Starting");
        });
    }

    #[test]
    fn loom_stop_during_starting_returns_not_running() {
        loom::model(|| {
            let state = Arc::new(Mutex::new(TunnelSessionState::Starting));

            let state1 = state.clone();
            let t_stop = loom::thread::spawn(move || {
                let mut s = state1.lock().unwrap();
                take_running_tunnel(&mut s).is_ok()
            });

            let stopped = t_stop.join().unwrap();
            assert!(!stopped, "stop during Starting must return not-running");
            // take_running_tunnel resets Starting -> Ready on non-Running match.
            let s = state.lock().unwrap();
            assert!(matches!(*s, TunnelSessionState::Ready));
        });
    }

    #[test]
    fn loom_concurrent_start_check_and_destroy_check() {
        loom::model(|| {
            let state = Arc::new(Mutex::new(TunnelSessionState::Ready));

            // Thread A: checks start-allowed; if so, transitions to Starting.
            let state1 = state.clone();
            let t_start = loom::thread::spawn(move || {
                let mut s = state1.lock().unwrap();
                if ensure_tunnel_start_allowed(&s).is_ok() {
                    *s = TunnelSessionState::Starting;
                    true
                } else {
                    false
                }
            });

            // Thread B: checks destroyable.
            let state2 = state.clone();
            let t_destroy = loom::thread::spawn(move || {
                let s = state2.lock().unwrap();
                ensure_tunnel_destroyable(&s).is_ok()
            });

            let started = t_start.join().unwrap();
            let can_destroy = t_destroy.join().unwrap();

            // The Mutex serializes the two threads.  After both complete, the
            // state is either Ready (A lost the race) or Starting (A won).
            // In both cases the results must be internally consistent:
            // - If A transitioned to Starting, B either saw Ready (destroyable)
            //   or Starting (not destroyable).
            // - If A did not transition, B saw Ready (destroyable).
            let s = state.lock().unwrap();
            match *s {
                TunnelSessionState::Ready => {
                    // A did not win; B must have seen Ready -> destroyable.
                    assert!(!started);
                    assert!(can_destroy);
                }
                TunnelSessionState::Starting => {
                    // A won; B's result was valid for whichever state it observed.
                    assert!(started);
                    // B saw either Ready (can_destroy=true) or Starting (can_destroy=false).
                    // Both are valid; just confirm ensure_tunnel_destroyable now fails.
                    assert!(ensure_tunnel_destroyable(&s).is_err());
                }
                TunnelSessionState::Running { .. } => {
                    panic!("unexpected Running state in loom test");
                }
            }
        });
    }
}
