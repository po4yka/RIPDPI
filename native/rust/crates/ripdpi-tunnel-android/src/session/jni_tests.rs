use std::sync::{LazyLock, Mutex, OnceLock};

use android_support::describe_exception;
use jni::objects::{JLongArray, JObject, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::{Env, EnvUnowned, InitArgsBuilder, JNIVersion, JavaVM};
use serde_json::Value;

static TEST_JVM: OnceLock<JavaVM> = OnceLock::new();
static JNI_TEST_MUTEX: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

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
fn env_to_unowned<'local>(env: &mut Env<'local>) -> EnvUnowned<'local> {
    // SAFETY: `env.get_raw()` returns the live `*mut jni::sys::JNIEnv` backing the
    // borrowed `Env<'local>`. The returned `EnvUnowned<'local>` carries the same
    // lifetime as the `&mut Env`, so the borrow checker prevents it from outliving
    // the env it was derived from. `from_raw` only copies the pointer; it does not
    // detach the thread or take ownership of the JNI env.
    unsafe { EnvUnowned::from_raw(env.get_raw()) }
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

fn jni_get_forwarding_evidence(env: &mut Env<'_>, handle: jlong) -> jni::sys::jstring {
    crate::Java_com_poyka_ripdpi_core_TunForwardingEvidenceNativeBindings_jniGetForwardingEvidence(
        env_to_unowned(env),
        JObject::null(),
        handle,
    )
}

fn jni_get_icmp_ingress_packets(env: &mut Env<'_>, handle: jlong) -> jlong {
    crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetIcmpIngressPackets(
        env_to_unowned(env),
        JObject::null(),
        handle,
    )
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
    // SAFETY: `raw` is a `jstring` returned by a `jni*` FFI entry point above and
    // is a valid JNI local reference in the current frame (null-checked at the top
    // of the fn). It is consumed exactly once here, and the resulting `JString`
    // borrows `env`, so it cannot outlive the JNI local frame.
    let string = unsafe { JString::from_raw(env, raw) };
    Some(string.try_to_string(env).expect("read jstring"))
}

fn decode_long_array(env: &mut Env<'_>, raw: jlongArray) -> Option<Vec<jlong>> {
    if raw.is_null() {
        return None;
    }
    // SAFETY: `raw` is a `jlongArray` returned by `jniGetStats` above and is a
    // valid JNI local reference in the current frame (null-checked at the top of
    // the fn). It is consumed exactly once here, and the resulting `JLongArray`
    // borrows `env`, so it cannot outlive the JNI local frame.
    let array = unsafe { JLongArray::from_raw(env, raw) };
    let len = array.len(env).expect("stats array length");
    let mut values = vec![0; len];
    array.get_region(env, 0, &mut values).expect("read stats array");
    Some(values)
}

fn sample_payload_json() -> String {
    r#"{
        "schemaVersion": 3,
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
        "uidPolicyMode": null,
        "taskStackSize": 81920
    }"#
    .to_string()
}

fn sample_payload_with_schema_version(schema_version: Option<u64>) -> String {
    let mut payload: Value = serde_json::from_str(&sample_payload_json()).expect("decode sample tunnel payload");
    let object = payload.as_object_mut().expect("sample tunnel payload must be an object");
    match schema_version {
        Some(version) => {
            object.insert("schemaVersion".to_string(), Value::from(version));
        }
        None => {
            object.remove("schemaVersion");
        }
    }
    serde_json::to_string(&payload).expect("encode sample tunnel payload")
}

fn sample_payload_with_duplicate_field(field: &str, value: &str) -> String {
    sample_payload_json().replacen(&format!("\"{field}\":"), &format!("\"{field}\":{value},\"{field}\":"), 1)
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
fn exported_jni_requires_current_tunnel_schema_version() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");

    for (schema_version, expected_message) in [
        (None, "Missing tunnel config schemaVersion; expected 3".to_string()),
        (Some(2), "Unsupported tunnel config schemaVersion: 2; expected 3".to_string()),
        (Some(4), "Unsupported tunnel config schemaVersion: 4; expected 3".to_string()),
    ] {
        with_env(|env| {
            let handle = jni_create(env, &sample_payload_with_schema_version(schema_version));
            if handle != 0 {
                jni_destroy(env, handle);
                assert_no_exception(env);
            }
            assert_eq!(handle, 0);
            assert_eq!(take_exception(env), format!("java.lang.IllegalArgumentException: {expected_message}"));
        });
    }

    with_env(|env| {
        let handle = jni_create(env, &sample_payload_with_schema_version(Some(3)));
        assert_ne!(handle, 0, "current v3 JNI payload must allocate a handle");
        assert_no_exception(env);
        jni_destroy(env, handle);
        assert_no_exception(env);
    });

    let mut handle = TunnelHandle::new();
    with_env(|env| {
        jni_destroy(env, handle.raw());
        assert_no_exception(env);
    });
    handle.disarm();
}

#[test]
fn exported_jni_rejects_duplicate_schema_and_behavior_fields() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");

    for (payload, field) in [
        (sample_payload_with_duplicate_field("schemaVersion", "2"), "schemaVersion"),
        (sample_payload_with_duplicate_field("socks5Port", "1081"), "socks5Port"),
        (sample_payload_with_duplicate_field("uidPolicyMode", "\"allow\""), "uidPolicyMode"),
    ] {
        with_env(|env| {
            let handle = jni_create(env, &payload);
            if handle != 0 {
                jni_destroy(env, handle);
                assert_no_exception(env);
            }
            assert_eq!(handle, 0);
            let exception = take_exception(env);
            assert!(
                exception.starts_with("java.lang.IllegalArgumentException: Invalid tunnel config JSON:")
                    && exception.contains(&format!("duplicate field `{field}`")),
                "unexpected duplicate-field exception: {exception}"
            );
        });
    }
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

        let raw_evidence = jni_get_forwarding_evidence(env, handle.raw());
        let evidence_json = decode_jstring(env, raw_evidence).expect("forwarding evidence json");
        assert_no_exception(env);
        let evidence: Value = serde_json::from_str(&evidence_json).expect("decode forwarding evidence");
        assert_eq!(evidence["tunReadPackets"], 0);
        assert_eq!(evidence["tunWritePackets"], 0);
        assert_eq!(evidence["tunParseFailures"], 0);
        assert_eq!(evidence["tunPolicyDrops"], 0);
        assert_eq!(evidence["firstTunWriteAtEpochMs"], Value::Null);
        assert_eq!(evidence["lastTunWriteAtEpochMs"], Value::Null);
        assert!(evidence.get("lastTarget").is_none(), "evidence must not expose endpoint identity");

        assert_eq!(jni_get_icmp_ingress_packets(env, handle.raw()), 0);
        assert_no_exception(env);

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
            let evidence = jni_get_forwarding_evidence(env, handle);
            assert!(decode_jstring(env, evidence).is_none());
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid tunnel handle");
        });

        with_env(|env| {
            assert_eq!(jni_get_icmp_ingress_packets(env, handle), 0);
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
fn exported_jni_forwarding_evidence_panic_returns_null_without_exception() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");

    with_env(|env| {
        let raw = super::entries::tunnel_forwarding_evidence_panic_entry(env_to_unowned(env));
        assert!(decode_jstring(env, raw).is_none());
        assert_no_exception(env);
    });
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
        let evidence = jni_get_forwarding_evidence(env, stale_handle);
        assert!(decode_jstring(env, evidence).is_none());
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

#[test]
fn exported_direct_dns_registration_rejects_object_without_callback_contract() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock tunnel JNI tests");

    with_env(|env| {
        let object = env.new_object(jni::jni_str!("java/lang/Object"), jni::jni_sig!("()V"), &[]).expect("object");
        let token = crate::Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniRegisterDirectDnsSocketBinderNative(
            env_to_unowned(env),
            JObject::null(),
            object,
        );
        assert_eq!(token, 0, "method-contract preflight must reject the wrong bridge object");
        let exception = take_exception(env);
        assert!(exception.starts_with("java.lang.RuntimeException: Direct DNS binder registration failed"));
    });
}
