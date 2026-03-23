use std::os::fd::AsRawFd;
use std::sync::{Arc, Mutex, PoisonError};

use android_support::{
    android_log_level_from_debug_verbosity, android_log_level_from_str, clear_android_log_scope_level,
    init_android_logging, set_android_log_scope_level, throw_illegal_argument, throw_illegal_state, throw_io_exception,
    throw_runtime_exception, HandleRegistry,
};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::{NetworkSnapshot, ProxyRuntimeContext};
use ripdpi_runtime::{runtime, EmbeddedProxyControl};

use crate::config::{parse_proxy_config_json, runtime_config_envelope_from_payload};
use crate::errors::{extract_panic_message, JniProxyError};
use crate::telemetry::{ProxyTelemetryObserver, ProxyTelemetryState};
use crate::to_handle;

pub(crate) static SESSIONS: once_cell::sync::Lazy<HandleRegistry<ProxySession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) struct ProxySession {
    pub(crate) config: RuntimeConfig,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    pub(crate) telemetry: Arc<ProxyTelemetryState>,
    pub(crate) state: Mutex<ProxySessionState>,
}

pub(crate) enum ProxySessionState {
    Idle,
    Running { listener_fd: i32, control: Arc<EmbeddedProxyControl> },
}

pub(crate) fn proxy_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session creation panicked: {msg}"));
            0
        },
    )
}

pub(crate) fn proxy_start_entry(mut env: JNIEnv, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session start panicked: {msg}"));
            libc::EINVAL
        },
    )
}

pub(crate) fn proxy_stop_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle))).map_err(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session stop panicked: {msg}"));
        },
    );
}

pub(crate) fn proxy_poll_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| poll_proxy_telemetry(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy telemetry polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn proxy_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle))).map_err(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session destroy panicked: {msg}"));
        },
    );
}

pub(crate) fn proxy_update_network_snapshot_entry(mut env: JNIEnv, handle: jlong, snapshot_json: JString) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        update_network_snapshot(&mut env, handle, snapshot_json);
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Proxy network snapshot update panicked: {msg}"));
    });
}

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

    let envelope = match runtime_config_envelope_from_payload(payload) {
        Ok(envelope) => envelope,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };
    let native_log_level = match envelope.native_log_level.as_deref() {
        Some(value) => match android_log_level_from_str(value) {
            Some(level) => level,
            None => {
                throw_illegal_argument(env, format!("Unsupported proxy nativeLogLevel: {value}"));
                return 0;
            }
        },
        None => android_log_level_from_debug_verbosity(envelope.config.debug),
    };
    let config = envelope.config;

    if let Err(err) = runtime::create_listener(&config) {
        JniProxyError::Io(err).throw(env);
        return 0;
    }

    let autolearn_enabled = config.host_autolearn_enabled;
    let telemetry = Arc::new(ProxyTelemetryState::new());
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);
    telemetry.set_autolearn_state(autolearn_enabled, 0, 0);

    SESSIONS.insert(ProxySession {
        config,
        runtime_context: envelope.runtime_context,
        telemetry,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
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

    session.telemetry.clear_last_error();
    let control = Arc::new(EmbeddedProxyControl::new_with_context(
        Some(Arc::new(ProxyTelemetryObserver { state: session.telemetry.clone() })),
        session.runtime_context.clone(),
    ));

    {
        let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        if let Err(message) = try_mark_proxy_running(&mut state, listener_fd, control.clone()) {
            throw_illegal_state(env, message);
            return libc::EINVAL;
        }
    }

    let result = runtime::run_proxy_with_embedded_control(config, listener, control);

    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
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

    let (listener_fd, control) = {
        let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        match listener_fd_for_proxy_stop(&state) {
            Ok(parts) => parts,
            Err(message) => {
                throw_illegal_state(env, message);
                return;
            }
        }
    };

    control.request_shutdown();
    if let Err(err) = shutdown_proxy_listener(listener_fd) {
        throw_io_exception(env, format!("Failed to stop proxy listener: {err}"));
        session.telemetry.on_client_error(err.to_string());
    }
    session.telemetry.push_event("proxy", "info", "stop requested".to_string());
}

fn update_network_snapshot(env: &mut JNIEnv, handle: jlong, snapshot_json: JString) {
    let json: String = match env.get_string(&snapshot_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid network snapshot JSON");
            return;
        }
    };
    let snapshot: NetworkSnapshot = match serde_json::from_str(&json) {
        Ok(value) => value,
        Err(err) => {
            throw_illegal_argument(env, format!("Failed to parse network snapshot: {err}"));
            return;
        }
    };
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };
    let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    if let ProxySessionState::Running { control, .. } = &*state {
        control.update_network_snapshot(snapshot);
    }
    // If the session is Idle, ignore: snapshot will be re-pushed on next start.
}

fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };
    let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    if let Err(message) = ensure_proxy_destroyable(&state) {
        throw_illegal_state(env, message);
        return;
    }
    drop(state);
    let _ = remove_proxy_session(handle);
}

pub(crate) fn lookup_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    SESSIONS.get(handle).ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))
}

fn poll_proxy_telemetry(env: &mut JNIEnv, handle: jlong) -> jstring {
    let result = env.with_local_frame_returning_local(4, |env| {
        let session = match lookup_proxy_session(handle) {
            Ok(session) => session,
            Err(err) => {
                err.throw(env);
                return Ok(JObject::null());
            }
        };
        match serde_json::to_string(&session.telemetry.snapshot()) {
            Ok(value) => env.new_string(value).map(|s| s.into()),
            Err(err) => {
                JniProxyError::Serialization(err).throw(env);
                Ok(JObject::null())
            }
        }
    });
    match result {
        Ok(obj) => obj.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

pub(crate) fn remove_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    let session =
        SESSIONS.remove(handle).ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))?;
    clear_android_log_scope_level(session.telemetry.log_scope());
    Ok(session)
}

pub(crate) fn try_mark_proxy_running(
    state: &mut ProxySessionState,
    listener_fd: i32,
    control: Arc<EmbeddedProxyControl>,
) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle => {
            *state = ProxySessionState::Running { listener_fd, control };
            Ok(())
        }
        ProxySessionState::Running { .. } => Err("Proxy session is already running"),
    }
}

pub(crate) fn listener_fd_for_proxy_stop(
    state: &ProxySessionState,
) -> Result<(i32, Arc<EmbeddedProxyControl>), &'static str> {
    match state {
        ProxySessionState::Idle => Err("Proxy session is not running"),
        ProxySessionState::Running { listener_fd, control } => Ok((*listener_fd, control.clone())),
    }
}

pub(crate) fn ensure_proxy_destroyable(state: &ProxySessionState) -> Result<(), &'static str> {
    if matches!(*state, ProxySessionState::Running { .. }) {
        Err("Cannot destroy a running proxy session")
    } else {
        Ok(())
    }
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

pub(crate) fn open_proxy_listener(
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

pub(crate) fn shutdown_proxy_listener(listener_fd: i32) -> Result<(), std::io::Error> {
    nix::sys::socket::shutdown(listener_fd, nix::sys::socket::Shutdown::Both)
        .map_err(|e| std::io::Error::from_raw_os_error(e as i32))
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::sync::Mutex;

    use android_support::describe_exception;
    use jni::objects::{JObject, JString};
    use jni::sys::jlong;
    use jni::{sys::jstring, JNIEnv};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use ripdpi_config::RuntimeConfig;
    use ripdpi_proxy_config::{NetworkSnapshot, ProxyConfigPayload, ProxyUiConfig};
    use ripdpi_runtime::EmbeddedProxyControl;

    use crate::telemetry::ProxyTelemetryState;
    use crate::to_handle;

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
        let first = Arc::new(EmbeddedProxyControl::default());
        let second = Arc::new(EmbeddedProxyControl::default());

        try_mark_proxy_running(&mut state, 7, first).expect("first start");
        let err = try_mark_proxy_running(&mut state, 8, second).expect_err("duplicate start");

        assert_eq!(err, "Proxy session is already running");
    }

    #[test]
    fn proxy_state_rejects_stop_when_idle() {
        let err = listener_fd_for_proxy_stop(&ProxySessionState::Idle).expect_err("idle stop");

        assert_eq!(err, "Proxy session is not running");
    }

    #[test]
    fn proxy_state_rejects_destroy_when_running() {
        let err = ensure_proxy_destroyable(&ProxySessionState::Running {
            listener_fd: 9,
            control: Arc::new(EmbeddedProxyControl::default()),
        })
        .expect_err("running destroy");

        assert_eq!(err, "Cannot destroy a running proxy session");
    }

    #[test]
    fn destroy_removes_idle_proxy_session() {
        let handle = SESSIONS.insert(ProxySession {
            config: RuntimeConfig::default(),
            runtime_context: None,
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

    #[test]
    fn exported_jni_create_poll_update_and_destroy_round_trip_without_exception() {
        let _serial = lock_jni_tests();
        let mut handle = ProxyHandle::new();

        with_env(|env| {
            let telemetry = jni_poll_telemetry(env, handle.raw());
            let telemetry_json = decode_jstring(env, telemetry).expect("telemetry json");
            assert!(telemetry_json.contains("\"source\":\"proxy\""));
            assert!(telemetry_json.contains("\"state\":\"idle\""));
            assert_no_exception(env);

            let snapshot_json = serde_json::to_string(&NetworkSnapshot::default()).expect("snapshot json");
            jni_update_network_snapshot(env, handle.raw(), &snapshot_json);
            assert_no_exception(env);

            jni_destroy(env, handle.raw());
            assert_no_exception(env);
        });
        handle.disarm();
    }

    #[test]
    fn exported_jni_rejects_malformed_config_and_snapshot_json() {
        let _serial = lock_jni_tests();

        with_env(|env| {
            let handle = jni_create(env, "{");
            assert_eq!(handle, 0);
            assert!(take_exception(env).starts_with("java.lang.IllegalArgumentException:"));
        });

        let handle = ProxyHandle::new();
        with_env(|env| {
            jni_update_network_snapshot(env, handle.raw(), "{");
            assert!(take_exception(env)
                .starts_with("java.lang.IllegalArgumentException: Failed to parse network snapshot:"));
        });
    }

    #[test]
    fn exported_jni_invalid_handles_throw_and_reference_calls_return_null() {
        let _serial = lock_jni_tests();
        let snapshot_json = serde_json::to_string(&NetworkSnapshot::default()).expect("snapshot json");

        for handle in [0, -1] {
            with_env(|env| {
                assert_eq!(jni_start(env, handle), libc::EINVAL);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid proxy handle");
            });

            with_env(|env| {
                jni_stop(env, handle);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid proxy handle");
            });

            with_env(|env| {
                let telemetry = jni_poll_telemetry(env, handle);
                assert!(decode_jstring(env, telemetry).is_none());
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid proxy handle");
            });

            with_env(|env| {
                jni_update_network_snapshot(env, handle, &snapshot_json);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid proxy handle");
            });

            with_env(|env| {
                jni_destroy(env, handle);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid proxy handle");
            });
        }
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
                runtime_context: None,
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
            try_mark_proxy_running(&mut state, listener_fd, Arc::new(EmbeddedProxyControl::default()))?;
            self.next_listener_fd += 1;
            Ok(listener_fd)
        }

        fn stop(&mut self) -> Result<i32, String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let mut state = session.state.lock().expect("proxy state lock");
            let (listener_fd, _) = listener_fd_for_proxy_stop(&state)?;
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

    struct ProxyHandle {
        raw: jlong,
    }

    impl ProxyHandle {
        fn new() -> Self {
            let raw = with_env(|env| {
                let handle = jni_create(env, &minimal_proxy_config_json());
                assert_no_exception(env);
                handle
            });
            assert_ne!(raw, 0, "jniCreate should return a non-zero proxy handle");
            Self { raw }
        }

        fn raw(&self) -> jlong {
            self.raw
        }

        fn disarm(&mut self) -> jlong {
            let raw = self.raw;
            self.raw = 0;
            raw
        }
    }

    impl Drop for ProxyHandle {
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

    fn minimal_proxy_config_json() -> String {
        serde_json::to_string(&ProxyConfigPayload::Ui {
            strategy_preset: None,
            config: test_ui_config(),
            runtime_context: None,
        })
        .expect("proxy config json")
    }

    fn test_ui_config() -> ProxyUiConfig {
        let mut config = ProxyUiConfig::default();
        config.protocols.desync_udp = true;
        config.chains.tcp_steps = vec![];
        config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
        config
    }

    fn lock_jni_tests() -> std::sync::MutexGuard<'static, ()> {
        crate::shared_jni_test_mutex().lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn with_env<R>(f: impl FnOnce(&mut JNIEnv<'_>) -> R) -> R {
        let mut env = crate::shared_test_jvm().attach_current_thread().expect("attach current thread to test JVM");
        f(&mut env)
    }

    fn jni_create(env: &mut JNIEnv<'_>, config_json: &str) -> jlong {
        let config_json = env.new_string(config_json).expect("create config json");
        crate::Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            config_json,
        )
    }

    fn jni_start(env: &mut JNIEnv<'_>, handle: jlong) -> i32 {
        crate::Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_stop(env: &mut JNIEnv<'_>, handle: jlong) {
        crate::Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        );
    }

    fn jni_poll_telemetry(env: &mut JNIEnv<'_>, handle: jlong) -> jstring {
        crate::Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_destroy(env: &mut JNIEnv<'_>, handle: jlong) {
        crate::Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        );
    }

    fn jni_update_network_snapshot(env: &mut JNIEnv<'_>, handle: jlong, snapshot_json: &str) {
        let snapshot_json = env.new_string(snapshot_json).expect("create snapshot json");
        crate::Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
            snapshot_json,
        );
    }

    fn decode_jstring(env: &mut JNIEnv<'_>, value: jstring) -> Option<String> {
        (!value.is_null()).then(|| {
            let value = unsafe { JString::from_raw(value) };
            let text = env.get_string(&value).expect("decode jstring");
            text.into()
        })
    }

    fn assert_no_exception(env: &mut JNIEnv<'_>) {
        if let Some(exception) = describe_exception(env) {
            panic!("unexpected Java exception: {exception}");
        }
    }

    fn take_exception(env: &mut JNIEnv<'_>) -> String {
        describe_exception(env).expect("expected Java exception")
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(256))]

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

#[cfg(feature = "loom")]
mod loom_tests {
    use super::*;
    use loom::sync::{Arc, Mutex};

    #[test]
    fn loom_two_concurrent_starts_one_wins() {
        loom::model(|| {
            let state = Arc::new(Mutex::new(ProxySessionState::Idle));
            let ctrl1 = std::sync::Arc::new(EmbeddedProxyControl::default());
            let ctrl2 = std::sync::Arc::new(EmbeddedProxyControl::default());

            let state1 = state.clone();
            let t1 = loom::thread::spawn(move || {
                let mut s = state1.lock().unwrap();
                try_mark_proxy_running(&mut s, 1, ctrl1).is_ok()
            });

            let state2 = state.clone();
            let t2 = loom::thread::spawn(move || {
                let mut s = state2.lock().unwrap();
                try_mark_proxy_running(&mut s, 2, ctrl2).is_ok()
            });

            let r1 = t1.join().unwrap();
            let r2 = t2.join().unwrap();
            assert_ne!(r1, r2, "exactly one start must win");
        });
    }

    #[test]
    fn loom_concurrent_start_and_stop() {
        loom::model(|| {
            let state = Arc::new(Mutex::new(ProxySessionState::Idle));
            let ctrl = std::sync::Arc::new(EmbeddedProxyControl::default());

            let state1 = state.clone();
            let ctrl1 = ctrl.clone();
            let t_start = loom::thread::spawn(move || {
                let mut s = state1.lock().unwrap();
                let _ = try_mark_proxy_running(&mut s, 42, ctrl1);
            });

            let state2 = state.clone();
            let t_stop = loom::thread::spawn(move || {
                let s = state2.lock().unwrap();
                listener_fd_for_proxy_stop(&s).ok().map(|(fd, _)| fd)
            });

            t_start.join().unwrap();
            let _fd = t_stop.join().unwrap();
        });
    }

    #[test]
    fn loom_stop_then_destroy_consistent() {
        loom::model(|| {
            let ctrl = std::sync::Arc::new(EmbeddedProxyControl::default());
            let state = Arc::new(Mutex::new(ProxySessionState::Running { listener_fd: 42, control: ctrl }));

            let state1 = state.clone();
            let t_stop = loom::thread::spawn(move || {
                let mut s = state1.lock().unwrap();
                if listener_fd_for_proxy_stop(&s).is_ok() {
                    *s = ProxySessionState::Idle;
                }
            });

            let state2 = state.clone();
            let t_destroy_check = loom::thread::spawn(move || {
                let s = state2.lock().unwrap();
                ensure_proxy_destroyable(&s).is_ok()
            });

            t_stop.join().unwrap();
            let _ = t_destroy_check.join().unwrap();

            // After stop, state must be destroyable.
            let s = state.lock().unwrap();
            assert!(ensure_proxy_destroyable(&s).is_ok());
        });
    }
}
