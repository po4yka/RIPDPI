use std::net::{IpAddr, Ipv4Addr, TcpListener};
use std::sync::{Arc, Mutex};

use android_support::describe_exception;
use jni::objects::JObject;
use jni::sys::jlong;
use jni::{sys::jstring, JNIEnv};
use proptest::collection::vec;
use proptest::prelude::*;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::{NetworkSnapshot, ProxyConfigPayload, ProxyUiConfig};
use ripdpi_runtime::EmbeddedProxyControl;

use crate::support::{assert_no_exception, decode_jstring, lock_jni_tests, take_exception, with_env};
use crate::telemetry::ProxyTelemetryState;
use crate::to_handle;

use super::lifecycle::open_proxy_listener;
use super::registry::{
    control_for_proxy_stop, ensure_proxy_destroyable, lookup_proxy_session, remove_proxy_session,
    try_mark_proxy_running, ProxySession, ProxySessionState, SESSIONS,
};

#[test]
fn open_proxy_listener_records_telemetry_when_bind_fails() {
    let busy = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind busy listener");
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
    config.network.listen.listen_port = busy.local_addr().expect("busy listener addr").port();
    let telemetry = ProxyTelemetryState::new();

    let err = open_proxy_listener(&config, &telemetry).expect_err("listener bind should fail");
    let snapshot = telemetry.snapshot();

    assert!(err.kind() == std::io::ErrorKind::AddrInUse || err.raw_os_error().is_some());
    assert_eq!(snapshot.total_errors, 1);
    assert!(snapshot.last_error.expect("listener error").contains("listener open failed"));
    assert_eq!(snapshot.health, "idle");
}

#[test]
fn rejects_invalid_handle() {
    assert!(to_handle(0).is_none());
    assert!(to_handle(-1).is_none());
}

#[test]
fn rejects_unknown_proxy_handle_lookup() {
    let Err(err) = lookup_proxy_session(99) else {
        panic!("expected unknown handle error");
    };

    assert_eq!(err.to_string(), "Unknown proxy handle");
}

#[test]
fn proxy_state_rejects_duplicate_start() {
    let mut state = ProxySessionState::Idle;
    let first = Arc::new(EmbeddedProxyControl::default());
    let second = Arc::new(EmbeddedProxyControl::default());

    try_mark_proxy_running(&mut state, first).expect("first start");
    let err = try_mark_proxy_running(&mut state, second).expect_err("duplicate start");

    assert_eq!(err, "Proxy session is already running");
}

#[test]
fn proxy_state_rejects_stop_when_idle() {
    let err = control_for_proxy_stop(&ProxySessionState::Idle).expect_err("idle stop");

    assert_eq!(err, "Proxy session is not running");
}

#[test]
fn proxy_state_rejects_destroy_when_running() {
    let err = ensure_proxy_destroyable(&ProxySessionState::Running {
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
        assert!(
            take_exception(env).starts_with("java.lang.IllegalArgumentException: Failed to parse network snapshot:")
        );
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
}

impl Default for ProxySessionHarness {
    fn default() -> Self {
        Self { active_handle: None, stale_handle: None }
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

    fn start(&mut self) -> Result<(), String> {
        let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
        let mut state = session.state.lock().expect("proxy state lock");
        try_mark_proxy_running(&mut state, Arc::new(EmbeddedProxyControl::default()))?;
        Ok(())
    }

    fn stop(&mut self) -> Result<(), String> {
        let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
        let mut state = session.state.lock().expect("proxy state lock");
        let _control = control_for_proxy_stop(&state)?;
        *state = ProxySessionState::Idle;
        Ok(())
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
    config.listen.port = i32::from(test_listen_port());
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config
}

fn test_listen_port() -> u16 {
    TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .expect("bind test listener")
        .local_addr()
        .expect("test listener addr")
        .port()
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

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    #[test]
    fn proxy_session_state_machine(commands in proxy_state_command_strategy()) {
        let mut harness = ProxySessionHarness::default();
        let mut model = ProxyModelState::Absent;

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
                            harness.start().expect("idle start");
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
                            harness.stop().expect("running stop");
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
