use std::sync::Mutex;

use android_support::describe_exception;
use jni::objects::{JLongArray, JObject, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::{Env, EnvUnowned, InitArgsBuilder, JNIVersion, JavaVM};
use once_cell::sync::{Lazy, OnceCell};
use serde_json::Value;

static TEST_JVM: OnceCell<JavaVM> = OnceCell::new();
static JNI_TEST_MUTEX: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

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
            let _ = describe_exception(&mut env_to_unowned(env));
        });
    }
}

fn test_jvm() -> &'static JavaVM {
    TEST_JVM.get_or_init(|| {
        let args = InitArgsBuilder::new()
            .version(JNIVersion::V9)
            .option("-Xcheck:jni")
            .build()
            .expect("build test JVM init args");
        JavaVM::new(args).expect("create in-process test JVM")
    })
}

/// Create an `EnvUnowned` from an `Env` reference for calling FFI entry points
/// and `describe_exception`.
///
/// # Safety
/// The returned `EnvUnowned` borrows the same JNI env pointer and must not
/// outlive the `Env` it was derived from.
fn env_to_unowned(env: &mut Env<'_>) -> EnvUnowned<'_> {
    unsafe { EnvUnowned::from_raw(env.as_raw()) }
}

fn with_env<R>(f: impl for<'a> FnOnce(&mut Env<'a>) -> R) -> R {
    test_jvm()
        .attach_current_thread(|env| Ok::<_, jni::errors::Error>(f(env)))
        .expect("attach current thread to test JVM")
}

fn jni_create(env: &mut Env<'_>, config_json: &str) -> jlong {
    let config_json = env.new_string(config_json).expect("create config json string");
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
        env_to_unowned(env),
        JObject::null(),
        config_json,
    )
}

fn jni_start(env: &mut Env<'_>, handle: jlong, tun_fd: jint) {
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(
        env_to_unowned(env),
        JObject::null(),
        handle,
        tun_fd,
    );
}

fn jni_stop(env: &mut Env<'_>, handle: jlong) {
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(env_to_unowned(env), JObject::null(), handle);
}

fn jni_get_stats(env: &mut Env<'_>, handle: jlong) -> jlongArray {
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(env_to_unowned(env), JObject::null(), handle)
}

fn jni_get_telemetry(env: &mut Env<'_>, handle: jlong) -> jni::sys::jstring {
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(
        env_to_unowned(env),
        JObject::null(),
        handle,
    )
}

fn jni_destroy(env: &mut Env<'_>, handle: jlong) {
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(env_to_unowned(env), JObject::null(), handle);
}

fn assert_no_exception(env: &mut Env<'_>) {
    assert!(describe_exception(&mut env_to_unowned(env)).is_none(), "unexpected pending Java exception");
}

fn take_exception(env: &mut Env<'_>) -> String {
    describe_exception(&mut env_to_unowned(env)).expect("expected Java exception")
}

fn decode_jstring(env: &mut Env<'_>, raw: jni::sys::jstring) -> Option<String> {
    if raw.is_null() {
        return None;
    }
    let string = unsafe { JString::from_raw(env, raw) };
    Some(env.get_string(&string).expect("read jstring").into())
}

fn decode_long_array(env: &mut Env<'_>, raw: jlongArray) -> Option<Vec<jlong>> {
    if raw.is_null() {
        return None;
    }
    let array = unsafe { JLongArray::from_raw(env, raw) };
    let len = env.get_array_length(&array).expect("stats array length") as usize;
    let mut values = vec![0; len];
    array.get_region(env, 0, &mut values).expect("read stats array");
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
        "mapdnsPath": null,
        "mapdnsTlsName": null,
        "mapdnsResolverId": null,
        "mapdnsResolverProtocol": null,
        "mapdnsFallbackAddress": null,
        "mapdnsFallbackPort": null,
        "mapdnsFallbackTlsName": null,
        "mapdnsFallbackResolverId": null,
        "mapdnsFallbackResolverProtocol": null,
        "mapdnsInterceptEnabled": true,
        "tcpConnectTimeoutMs": null,
        "tcpReadWriteTimeoutMs": null,
        "udpReadWriteTimeoutMs": null,
        "logLevel": "warn",
        "limitNofile": null,
        "filterInjectedResets": null,
        "taskStackSize": 81920
    }"#
    .to_string()
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
