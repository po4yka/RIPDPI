mod lifecycle;
mod registry;
mod stats;
mod telemetry;

#[cfg(test)]
use std::sync::{Arc, Mutex};

use android_support::throw_runtime_exception;
use jni::objects::JString;
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;
#[cfg(test)]
use ripdpi_tunnel_core::DnsStatsSnapshot;
#[cfg(test)]
use tokio_util::sync::CancellationToken;

#[cfg(test)]
use crate::config::config_from_payload;
#[cfg(test)]
use crate::config::mapdns_resolver_protocol;
#[cfg(test)]
use crate::telemetry::TunnelTelemetryState;
#[cfg(test)]
use crate::to_handle;

use lifecycle::{create_session, destroy_session, start_session, stop_session};
use stats::stats_session;
use telemetry::telemetry_session;

pub(crate) use lifecycle::{
    ensure_tunnel_destroyable, ensure_tunnel_start_allowed, rollback_failed_tunnel_start, take_running_tunnel,
    validate_tun_fd,
};
pub(crate) use registry::{
    lookup_tunnel_session, remove_tunnel_session, shared_tunnel_runtime, TunnelSession, TunnelSessionState, SESSIONS,
};
pub(crate) use stats::stats_snapshots_for_state;

pub(crate) fn tunnel_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    android_support::init_android_logging("ripdpi-tunnel-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel session creation panicked");
            0
        },
    )
}

pub(crate) fn tunnel_start_entry(mut env: JNIEnv, handle: jlong, tun_fd: jint) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle, tun_fd)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session start panicked"));
}

pub(crate) fn tunnel_stop_entry(mut env: JNIEnv, handle: jlong) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session stop panicked"));
}

pub(crate) fn tunnel_stats_entry(mut env: JNIEnv, handle: jlong) -> jlongArray {
    android_support::init_android_logging("ripdpi-tunnel-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stats_session(&mut env, handle))).unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel stats retrieval panicked");
        std::ptr::null_mut()
    })
}

pub(crate) fn tunnel_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| telemetry_session(&mut env, handle))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel telemetry retrieval panicked");
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn tunnel_destroy_entry(mut env: JNIEnv, handle: jlong) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session destroy panicked"));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::sample_payload;
    use crate::telemetry::NativeRuntimeSnapshot;
    use android_support::describe_exception;
    use jni::objects::{JLongArray, JObject, JString};
    use jni::{InitArgsBuilder, JNIEnv, JNIVersion, JavaVM};
    use once_cell::sync::{Lazy, OnceCell};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use ripdpi_tunnel_core::Stats;
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
            "tunnelMtu": 1500,
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
