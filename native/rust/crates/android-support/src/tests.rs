use golden_test_support::{assert_contract_fixture, assert_text_golden};
use jni::{Env, EnvUnowned, InitArgsBuilder, JNIVersion, JavaVM};
use log::LevelFilter;
use serde_json::json;
use std::sync::Mutex;
use std::sync::{LazyLock, OnceLock};
use tracing_subscriber::prelude::*;

use super::*;
use crate::tracing_layer::MessageFieldFormatter;

static TEST_JVM: OnceLock<JavaVM> = OnceLock::new();
static JNI_TEST_MUTEX: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));
static LOG_LEVEL_TEST_MUTEX: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

#[test]
fn install_panic_hook_is_idempotent() {
    install_panic_hook();
    install_panic_hook();
}

#[test]
fn formatter_renders_plain_message_golden() {
    let mut formatter = MessageFieldFormatter::default();
    formatter.record_named_str("message", "proxy started");

    assert_text_golden(
        env!("CARGO_MANIFEST_DIR"),
        "tests/golden/plain_message.txt",
        &formatter.finish("fallback.target"),
    );
}

#[test]
fn formatter_renders_structured_fields_golden() {
    let mut formatter = MessageFieldFormatter::default();
    formatter.record_named_str("message", "route selected");
    formatter.record_named_str("target", "203.0.113.10:443");
    formatter.record_named_debug("group", &2);

    assert_text_golden(
        env!("CARGO_MANIFEST_DIR"),
        "tests/golden/structured_fields.txt",
        &formatter.finish("fallback.target"),
    );
}

#[test]
fn formatter_falls_back_to_target_when_message_is_absent() {
    let formatter = MessageFieldFormatter::default();
    assert_text_golden(
        env!("CARGO_MANIFEST_DIR"),
        "tests/golden/fallback_target.txt",
        &formatter.finish("ripdpi.native"),
    );
}

#[test]
fn formatter_preserves_debug_quotes_for_non_message_fields() {
    let mut formatter = MessageFieldFormatter::default();
    formatter.record_named_str("message", "tunnel error");
    formatter.record_named_debug("error", &"unexpected eof");

    assert_text_golden(
        env!("CARGO_MANIFEST_DIR"),
        "tests/golden/debug_quotes.txt",
        &formatter.finish("fallback.target"),
    );
}

#[test]
fn formatter_renders_structured_prefix_fields_before_message() {
    let mut formatter = MessageFieldFormatter::default();
    formatter.record_named_str("subsystem", "diagnostics");
    formatter.record_named_str("session", "diag-7");
    formatter.record_named_str("profile", "connectivity");
    formatter.record_named_str("path_mode", "RAW_PATH");
    formatter.record_named_str("source", "dns");
    formatter.record_named_str("message", "probe started");

    assert_eq!(
        formatter.finish("fallback.target"),
        "subsystem=diagnostics session=diag-7 profile=connectivity pathMode=RAW_PATH source=dns probe started",
    );
}

#[test]
fn event_ring_layer_routes_and_drains_correlation_fields() {
    let buffers = EventRingBuffers::new(RingConfig {
        proxy_capacity: 8,
        relay_capacity: 8,
        warp_capacity: 8,
        tunnel_capacity: 8,
        diagnostics_capacity: 8,
    });
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));

    tracing::subscriber::with_default(subscriber, || {
        tracing::warn!(
            ring = "diagnostics",
            subsystem = "diagnostics",
            session = "diag-42",
            profile = "connectivity",
            path_mode = "RAW_PATH",
            source = "dns",
            runtime_id = "vpn-runtime-1",
            mode = "VPN",
            policy_signature = "policy-123",
            fingerprint_hash = "fingerprint-abc",
            diagnostics_session_id = "diag-42",
            "probe failed target=example.org"
        );
    });

    let events = buffers.drain_diagnostics();
    assert_eq!(events.len(), 1);
    assert!(buffers.drain_diagnostics().is_empty(), "drain must empty the ring");
    assert_eq!(
        events[0],
        NativeEventRecord {
            source: "dns".to_string(),
            level: "warn".to_string(),
            message: "probe failed target=example.org".to_string(),
            created_at: events[0].created_at,
            kind: None,
            runtime_id: Some("vpn-runtime-1".to_string()),
            mode: Some("vpn".to_string()),
            policy_signature: Some("policy-123".to_string()),
            fingerprint_hash: Some("fingerprint-abc".to_string()),
            diagnostics_session_id: Some("diag-42".to_string()),
            subsystem: Some("diagnostics".to_string()),
            attempt_id: None,
            attempt_sequence: None,
            stage: None,
            outcome: None,
            duration_ms: None,
            failure_stage: None,
            failure_class: None,
            io_error_kind: None,
            os_error_code: None,
            peer_close_phase: None,
            carrier_disposition: None,
        },
    );
}

#[test]
fn event_ring_layer_respects_capacity_per_ring() {
    let buffers = EventRingBuffers::new(RingConfig {
        proxy_capacity: 2,
        relay_capacity: 2,
        warp_capacity: 2,
        tunnel_capacity: 2,
        diagnostics_capacity: 2,
    });
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));

    tracing::subscriber::with_default(subscriber, || {
        tracing::info!(ring = "proxy", source = "proxy", "one");
        tracing::info!(ring = "proxy", source = "proxy", "two");
        tracing::info!(ring = "proxy", source = "proxy", "three");
    });

    let messages: Vec<String> = buffers.drain_proxy().into_iter().map(|event| event.message).collect();
    assert_eq!(messages, vec!["two".to_string(), "three".to_string()]);
}

#[test]
fn throw_helpers_map_expected_java_exception_classes() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock android-support JNI tests");

    with_unowned_env(|env| {
        throw_illegal_argument(env, "bad arg");
        assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: bad arg");
    });

    with_unowned_env(|env| {
        throw_illegal_state(env, "bad state");
        assert_eq!(take_exception(env), "java.lang.IllegalStateException: bad state");
    });

    with_unowned_env(|env| {
        throw_io_exception(env, "disk boom");
        assert_eq!(take_exception(env), "java.io.IOException: disk boom");
    });

    with_unowned_env(|env| {
        throw_runtime_exception(env, "runtime boom");
        assert_eq!(take_exception(env), "java.lang.RuntimeException: runtime boom");
    });
}

#[test]
fn describe_exception_reads_and_clears_pending_exception() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock android-support JNI tests");

    with_env(|env| {
        let err = env
            .throw_new(jni::jni_str!("java/lang/RuntimeException"), jni::jni_str!("direct boom"))
            .expect_err("throw direct runtime exception");
        assert!(matches!(err, jni::errors::Error::JavaException));

        with_borrowed_unowned_env(env, |env| {
            assert_eq!(describe_exception(env), Some("java.lang.RuntimeException: direct boom".to_string()));
            assert!(describe_exception(env).is_none(), "describe_exception should clear the pending throwable");
        });
    });
}

#[test]
fn describe_exception_returns_none_when_no_exception_is_pending() {
    let _serial = JNI_TEST_MUTEX.lock().expect("lock android-support JNI tests");

    with_unowned_env(|env| {
        assert!(describe_exception(env).is_none());
    });
}

#[test]
fn android_log_level_parser_supports_expected_values() {
    assert_eq!(android_log_level_from_str("trace"), Some(LevelFilter::Trace));
    assert_eq!(android_log_level_from_str("debug"), Some(LevelFilter::Debug));
    assert_eq!(android_log_level_from_str("info"), Some(LevelFilter::Info));
    assert_eq!(android_log_level_from_str("warning"), Some(LevelFilter::Warn));
    assert_eq!(android_log_level_from_str("error"), Some(LevelFilter::Error));
    assert_eq!(android_log_level_from_str("off"), Some(LevelFilter::Off));
    assert_eq!(android_log_level_from_str("nope"), None);
}

#[test]
fn scoped_log_levels_keep_the_most_verbose_active_request() {
    let _serial = LOG_LEVEL_TEST_MUTEX.lock().expect("lock android-support log level tests");
    clear_android_log_scope_level("android-support:test:a");
    clear_android_log_scope_level("android-support:test:b");
    log::set_max_level(default_android_log_level());

    set_android_log_scope_level("android-support:test:a", LevelFilter::Warn);
    assert_eq!(log::max_level(), LevelFilter::Warn);

    set_android_log_scope_level("android-support:test:b", LevelFilter::Trace);
    assert_eq!(log::max_level(), LevelFilter::Trace);

    clear_android_log_scope_level("android-support:test:b");
    assert_eq!(log::max_level(), LevelFilter::Warn);

    clear_android_log_scope_level("android-support:test:a");
    assert_eq!(log::max_level(), default_android_log_level());
}

#[test]
fn handle_sentinel_matches_contract_fixture() {
    let registry = HandleRegistry::<String>::new();

    assert!(registry.get(0).is_none(), "handle 0 must be invalid");

    let first = registry.insert("test".to_string());
    assert!(first >= 1, "first valid handle must be >= 1, got {first}");

    let fixture = json!({
        "invalidSentinel": 0,
        "minimumValidHandle": 1,
    });
    let actual = serde_json::to_string_pretty(&fixture).expect("serialize fixture");
    assert_contract_fixture("handle_contract.json", &actual);
}

fn test_jvm() -> &'static JavaVM {
    TEST_JVM.get_or_init(|| {
        let args = InitArgsBuilder::new()
            .version(JNIVersion::V1_8)
            .option("-Xcheck:jni")
            .build()
            .expect("build test JVM init args");
        JavaVM::new(args).expect("create in-process test JVM")
    })
}

fn with_env<R>(f: impl FnOnce(&mut Env<'_>) -> R) -> R {
    test_jvm()
        .attach_current_thread(|env| Ok::<R, jni::errors::Error>(f(env)))
        .expect("attach current thread to test JVM")
}

fn with_unowned_env<R>(f: impl FnOnce(&mut EnvUnowned<'_>) -> R) -> R {
    with_env(|env| with_borrowed_unowned_env(env, f))
}

fn with_borrowed_unowned_env<R>(env: &mut Env<'_>, f: impl FnOnce(&mut EnvUnowned<'_>) -> R) -> R {
    // SAFETY: `env` is attached for this callback scope, and the unowned wrapper
    // is only used synchronously before the callback returns.
    let mut unowned_env = unsafe { EnvUnowned::from_raw(env.get_raw()) };
    f(&mut unowned_env)
}

fn take_exception(env: &mut EnvUnowned<'_>) -> String {
    describe_exception(env).expect("expected Java exception")
}
