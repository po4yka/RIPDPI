//! Shared JNI handle and error helpers for the Android bridge crates.
//!
//! These helpers implement the cross-language pieces of the JNI contract that
//! the `ripdpi-android*` adapters depend on:
//!
//! - **Error mapping.** [`JniProxyError`] and its [`JniProxyError::throw`]
//!   method translate native error categories into the Java exception classes
//!   the Kotlin layer expects: `IllegalArgumentException` (bad config/handle),
//!   `IllegalStateException` (wrong lifecycle state), `IOException` (socket/IO
//!   failure) and `RuntimeException` (serialization failure). The variant ->
//!   class mapping is regression-locked by the `error_exception_mapping.json`
//!   contract fixture; keep [`JniProxyError::throw`] and that fixture in sync.
//! - **Panic containment.** [`extract_panic_message`] and [`throw_panic`]
//!   convert a payload caught by `catch_unwind` into a Java `RuntimeException`
//!   instead of letting the panic unwind across the `extern "system"` boundary
//!   — that unwind is undefined behaviour under the `android-jni` profile's
//!   `panic = "unwind"` setting. They are the throwing counterpart to
//!   `android_support::ffi_boundary`, which substitutes a sentinel value.
//! - **Handle decoding.** [`to_handle`] validates the opaque `jlong` handle the
//!   Kotlin side passes back, rejecting `0` (the "no handle" sentinel) and
//!   negative values, and yielding a registry-key `u64` on success.
//!
//! None of these helpers own file descriptors or register callbacks; fd and
//! callback ownership live in the per-feature adapter crates.
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
//! containment) and §7 (error mapping).

// The only `unsafe` in this crate is in the `test-support` module's JNI helpers
// (`EnvUnowned::from_raw`, `JString::from_raw`). Re-enable the workspace-deferred
// unsafe-documentation lints locally so every `unsafe` block keeps its inline
// `// SAFETY:` invariant and no block fuses multiple unsafe operations — matching
// the convention in `ripdpi-vless` / `ripdpi-io-uring` / `ripdpi-privileged-ops`
// / `ripdpi-proxy-runtime` (per the `rust-unsafe` skill's lint floor).
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

use std::any::Any;

use android_support::{
    sanitize_error_message, throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env,
    throw_runtime_exception, throw_runtime_exception_env,
};
use jni::sys::jlong;
use jni::{Env, EnvUnowned};

pub mod native_bridge_error;

pub use native_bridge_error::{
    NativeBridgeError, NativeBridgeErrorDomain, SCHEMA_VERSION as NATIVE_BRIDGE_ERROR_SCHEMA_VERSION,
    SENTINEL as NATIVE_BRIDGE_ERROR_SENTINEL, decorate_message,
};

/// Native error categories raised by the proxy/geo JNI adapters, each mapped to
/// a fixed Java exception class by [`JniProxyError::throw`].
#[derive(Debug, thiserror::Error)]
pub enum JniProxyError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("{0}")]
    InvalidArgument(String),

    #[error("{0}")]
    #[allow(dead_code)]
    IllegalState(&'static str),

    #[error("I/O failure: {0}")]
    Io(#[from] std::io::Error),

    #[error("{0}")]
    Serialization(#[from] serde_json::Error),
}

impl JniProxyError {
    /// `InvalidArgument` for a proxy handle that failed [`to_handle`] decoding
    /// (zero or out of range). Maps to `IllegalArgumentException` with the
    /// message `"Invalid proxy handle"`. Use as `ok_or_else(JniProxyError::invalid_handle)`.
    pub fn invalid_handle() -> Self {
        Self::InvalidArgument(invalid_handle_message("proxy"))
    }

    /// `InvalidArgument` for a well-formed proxy handle with no registered
    /// session. Maps to `IllegalArgumentException` with the message
    /// `"Unknown proxy handle"`.
    pub fn unknown_handle() -> Self {
        Self::InvalidArgument(unknown_handle_message("proxy"))
    }

    /// Throw this error into the JVM as a pending Java exception, then return.
    ///
    /// The mapping is fixed: `InvalidConfig`/`InvalidArgument` ->
    /// `IllegalArgumentException`, `IllegalState` -> `IllegalStateException`,
    /// `Io` -> `IOException`, `Serialization` -> `RuntimeException`. IO and
    /// serialization messages are passed through `sanitize_error_message` so
    /// raw paths/identifiers do not leak. After this call the caller must
    /// return the FFI sentinel value without making further JNI calls (a
    /// pending exception poisons subsequent JNI operations).
    pub fn throw(self, env: &mut Env<'_>) {
        log::error!("JNI proxy error: {self:?}");
        match self {
            Self::InvalidConfig(message) => {
                throw_illegal_argument_env(env, format!("invalid configuration: {message}"));
            }
            Self::InvalidArgument(message) => throw_illegal_argument_env(env, message),
            Self::IllegalState(message) => throw_illegal_state_env(env, message),
            Self::Io(err) => {
                throw_io_exception_env(env, sanitize_error_message(&format!("I/O failure: {err}"), "I/O failure"));
            }
            Self::Serialization(err) => {
                throw_runtime_exception_env(env, sanitize_error_message(&err.to_string(), "Serialization failure"));
            }
        }
    }
}

/// `throw` variant that also stamps a typed [`NativeBridgeError`]
/// payload into the exception message via [`decorate_message`].
///
/// The Java exception **class** is unchanged from [`JniProxyError::throw`];
/// callers that only read the leading message line continue to see
/// exactly what they always saw, and the [`NATIVE_BRIDGE_ERROR_SENTINEL`]
/// line lets typed callers recover the structured payload.
impl JniProxyError {
    pub fn throw_with_payload(self, env: &mut Env<'_>, payload: &NativeBridgeError) {
        log::error!("JNI proxy error: {self:?}");
        match self {
            Self::InvalidConfig(message) => {
                throw_illegal_argument_env(
                    env,
                    decorate_message(&format!("invalid configuration: {message}"), payload),
                );
            }
            Self::InvalidArgument(message) => {
                throw_illegal_argument_env(env, decorate_message(&message, payload));
            }
            Self::IllegalState(message) => {
                throw_illegal_state_env(env, decorate_message(message, payload));
            }
            Self::Io(err) => {
                let detail = format!("I/O failure: {err}");
                throw_io_exception_env(env, decorate_message(&sanitize_error_message(&detail, "I/O failure"), payload));
            }
            Self::Serialization(err) => {
                throw_runtime_exception_env(
                    env,
                    decorate_message(&sanitize_error_message(&err.to_string(), "Serialization failure"), payload),
                );
            }
        }
    }
}

/// `throw_illegal_argument_env` that appends a typed payload trailer.
pub fn throw_illegal_argument_env_with_payload(env: &mut Env<'_>, message: &str, payload: &NativeBridgeError) {
    throw_illegal_argument_env(env, decorate_message(message, payload));
}

/// `throw_illegal_state_env` that appends a typed payload trailer.
pub fn throw_illegal_state_env_with_payload(env: &mut Env<'_>, message: &str, payload: &NativeBridgeError) {
    throw_illegal_state_env(env, decorate_message(message, payload));
}

/// `throw_io_exception_env` that appends a typed payload trailer.
pub fn throw_io_exception_env_with_payload(env: &mut Env<'_>, message: &str, payload: &NativeBridgeError) {
    throw_io_exception_env(env, decorate_message(message, payload));
}

/// `throw_runtime_exception` (on `EnvUnowned`) that appends a typed
/// payload trailer. The runtime-exception variant is what the proxy
/// entry-point `Outcome::Err` / `Outcome::Panic` arms use.
pub fn throw_runtime_exception_with_payload(env: &mut EnvUnowned<'_>, message: &str, payload: &NativeBridgeError) {
    throw_runtime_exception(env, decorate_message(message, payload));
}

/// Extract a human-readable message from a `catch_unwind` panic payload,
/// handling the `String` and `&str` payload shapes and falling back to
/// `"unknown panic"` for any other type.
pub fn extract_panic_message(payload: Box<dyn Any + Send>) -> String {
    payload
        .downcast_ref::<String>()
        .map(String::as_str)
        .or_else(|| payload.downcast_ref::<&str>().copied())
        .unwrap_or("unknown panic")
        .to_string()
}

/// Throw a caught panic payload into the JVM as a `RuntimeException` prefixed
/// with `prefix`. Call this from the `Outcome::Panic` arm of a JNI entry point
/// so a contained panic is reported to Kotlin instead of unwinding.
pub fn throw_panic(env: &mut EnvUnowned<'_>, prefix: &str, payload: Box<dyn Any + Send>) {
    throw_runtime_exception(env, format!("{prefix}: {}", extract_panic_message(payload)));
}

/// `throw_panic` variant that augments the `RuntimeException` message
/// with a typed [`NativeBridgeError`] trailer. The panic-message extraction
/// itself is unchanged — only the trailer is added.
pub fn throw_panic_with_payload(
    env: &mut EnvUnowned<'_>,
    prefix: &str,
    payload: Box<dyn Any + Send>,
    bridge: &NativeBridgeError,
) {
    let human = format!("{prefix}: {}", extract_panic_message(payload));
    throw_runtime_exception(env, decorate_message(&human, bridge));
}

/// Decode the opaque `jlong` handle passed back from Kotlin into a native
/// registry key. Returns `None` for `0` (the "no handle" sentinel) and for any
/// value outside the non-negative `u64` range, so callers can reject stale or
/// never-created handles before a registry lookup.
pub fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

/// Canonical `IllegalArgumentException`-class message for a handle that failed
/// [`to_handle`] decoding (zero or out of range). `kind` is the subsystem name
/// (`"proxy"`, `"diagnostics"`, ...); the result reads `"Invalid {kind} handle"`.
///
/// Centralizes the message wording so every adapter's "bad handle" exception is
/// byte-identical for a given subsystem.
pub fn invalid_handle_message(kind: &str) -> String {
    format!("Invalid {kind} handle")
}

/// Canonical `IllegalArgumentException`-class message for a well-formed handle
/// that has no registered session. The result reads `"Unknown {kind} handle"`.
/// See [`invalid_handle_message`].
pub fn unknown_handle_message(kind: &str) -> String {
    format!("Unknown {kind} handle")
}

#[cfg(feature = "test-support")]
pub mod test_support {
    use super::*;
    use std::sync::MutexGuard;

    use android_support::describe_exception;
    use jni::objects::JString;
    use jni::sys::jstring;

    pub fn lock_jni_tests() -> MutexGuard<'static, ()> {
        static JNI_TEST_MUTEX: std::sync::LazyLock<std::sync::Mutex<()>> =
            std::sync::LazyLock::new(|| std::sync::Mutex::new(()));
        JNI_TEST_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub fn with_env<R>(f: impl for<'a> FnOnce(&mut Env<'a>) -> R) -> R {
        static TEST_JVM: std::sync::OnceLock<jni::JavaVM> = std::sync::OnceLock::new();
        TEST_JVM
            .get_or_init(|| {
                let args = jni::InitArgsBuilder::new()
                    .version(jni::JNIVersion::V9)
                    .option("-Xcheck:jni")
                    .build()
                    .expect("build test JVM init args");
                jni::JavaVM::new(args).expect("create in-process test JVM")
            })
            .attach_current_thread(|env| Ok::<_, jni::errors::Error>(f(env)))
            .expect("attach current thread to test JVM")
    }

    /// Create an `EnvUnowned` from an `Env` reference for calling FFI entry
    /// points and `describe_exception`.
    ///
    /// # Safety
    /// The returned `EnvUnowned` borrows the same JNI env pointer and must not
    /// outlive the `Env` it was derived from.
    pub fn env_to_unowned<'local>(env: &mut Env<'local>) -> EnvUnowned<'local> {
        // SAFETY: `env.get_raw()` returns the current JNI env pointer for this
        // thread; the returned `EnvUnowned` stays within the caller's borrow.
        unsafe { EnvUnowned::from_raw(env.get_raw()) }
    }

    pub fn take_exception(env: &mut Env<'_>) -> String {
        let mut unowned = env_to_unowned(env);
        describe_exception(&mut unowned).expect("expected Java exception")
    }

    pub fn decode_jstring(env: &mut Env<'_>, value: jstring) -> Option<String> {
        (!value.is_null()).then(|| {
            // SAFETY: `value` is a live local JNI string reference in the
            // current frame and is consumed exactly once by `from_raw`.
            let value = unsafe { JString::from_raw(env, value) };
            value.try_to_string(env).expect("decode jstring")
        })
    }

    pub fn assert_no_exception(env: &mut Env<'_>) {
        let mut unowned = env_to_unowned(env);
        if let Some(exception) = describe_exception(&mut unowned) {
            panic!("unexpected Java exception: {exception}");
        }
    }
}

#[cfg(all(test, feature = "test-support", not(feature = "loom")))]
mod tests {
    use super::*;

    use std::io;

    use crate::test_support::{lock_jni_tests, take_exception, with_env};

    #[test]
    fn handle_message_helpers_format_kind_and_back_the_proxy_constructors() {
        assert_eq!(invalid_handle_message("proxy"), "Invalid proxy handle");
        assert_eq!(invalid_handle_message("diagnostics"), "Invalid diagnostics handle");
        assert_eq!(unknown_handle_message("proxy"), "Unknown proxy handle");
        assert_eq!(unknown_handle_message("diagnostics"), "Unknown diagnostics handle");

        // The proxy constructors must keep producing the exact strings the
        // adapter test suites and `error_exception_mapping.json` pin.
        assert_eq!(JniProxyError::invalid_handle().to_string(), "Invalid proxy handle");
        assert_eq!(JniProxyError::unknown_handle().to_string(), "Unknown proxy handle");
    }

    #[test]
    fn throw_maps_argument_errors_to_illegal_argument_exception() {
        let _serial = lock_jni_tests();

        with_env(|env| {
            JniProxyError::InvalidConfig("bad settings".to_string()).throw(env);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: invalid configuration: bad settings",);

            JniProxyError::InvalidArgument("bad handle".to_string()).throw(env);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: bad handle");
        });
    }

    #[test]
    fn throw_maps_state_io_and_serialization_errors_to_expected_java_classes() {
        let _serial = lock_jni_tests();

        with_env(|env| {
            JniProxyError::IllegalState("proxy running").throw(env);
            assert_eq!(take_exception(env), "java.lang.IllegalStateException: proxy running");

            JniProxyError::Io(io::Error::new(io::ErrorKind::BrokenPipe, "socket boom")).throw(env);
            assert_eq!(take_exception(env), "java.io.IOException: I/O failure: I/O failure: socket boom");

            let json_err = serde_json::from_str::<serde_json::Value>("{").expect_err("json error");
            JniProxyError::Serialization(json_err).throw(env);
            assert!(take_exception(env).starts_with("java.lang.RuntimeException: Serialization failure:"));
        });
    }

    #[test]
    fn extract_panic_message_handles_string_str_and_unknown_payloads() {
        assert_eq!(extract_panic_message(Box::new(String::from("owned panic"))), "owned panic");
        assert_eq!(extract_panic_message(Box::new("borrowed panic")), "borrowed panic");
        assert_eq!(extract_panic_message(Box::new(42usize)), "unknown panic");
    }

    #[test]
    fn proxy_start_return_codes_match_contract_fixture() {
        use golden_test_support::assert_contract_fixture;
        use serde_json::json;

        // libc::EINVAL = 22 on all supported platforms
        let fixture = json!({
            "success": 0,
            "fallbackError": 22,
            "semantics": "positive_errno",
        });
        let actual = serde_json::to_string_pretty(&fixture).expect("serialize fixture");
        assert_contract_fixture("proxy_start_codes.json", &actual);
    }

    #[test]
    fn native_bridge_error_serialises_required_fields_only_when_unset() {
        let payload = NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, "create_failed", "bad config");
        let json: serde_json::Value = serde_json::from_str(&payload.to_json()).expect("payload is valid json");
        let obj = json.as_object().expect("top-level object");

        // Required keys present and typed correctly.
        assert_eq!(
            obj.get("schemaVersion").and_then(serde_json::Value::as_u64),
            Some(u64::from(NATIVE_BRIDGE_ERROR_SCHEMA_VERSION)),
        );
        assert_eq!(obj.get("domain").and_then(serde_json::Value::as_str), Some("proxy"));
        assert_eq!(obj.get("code").and_then(serde_json::Value::as_str), Some("create_failed"));
        assert_eq!(obj.get("message").and_then(serde_json::Value::as_str), Some("bad config"));
        assert_eq!(obj.get("retryable").and_then(serde_json::Value::as_bool), Some(false));

        // Optional keys absent when not set.
        assert!(obj.get("causeClass").is_none());
        assert!(obj.get("handleState").is_none());
    }

    #[test]
    fn native_bridge_error_serialises_optional_fields_when_set() {
        let payload = NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, "start_failed", "listener bind failed")
            .with_cause_class("java.io.IOException")
            .with_handle_state("idle")
            .retryable(true);
        let json: serde_json::Value = serde_json::from_str(&payload.to_json()).expect("payload is valid json");
        let obj = json.as_object().expect("top-level object");

        assert_eq!(obj.get("causeClass").and_then(serde_json::Value::as_str), Some("java.io.IOException"));
        assert_eq!(obj.get("handleState").and_then(serde_json::Value::as_str), Some("idle"));
        assert_eq!(obj.get("retryable").and_then(serde_json::Value::as_bool), Some(true));
    }

    #[test]
    fn native_bridge_error_domain_covers_every_documented_value() {
        // Spec-mandated set from /goal: proxy/tunnel/relay/diagnostics/root/telemetry.
        let expected = ["proxy", "tunnel", "relay", "diagnostics", "root", "telemetry"];
        let actual = [
            NativeBridgeErrorDomain::Proxy.as_str(),
            NativeBridgeErrorDomain::Tunnel.as_str(),
            NativeBridgeErrorDomain::Relay.as_str(),
            NativeBridgeErrorDomain::Diagnostics.as_str(),
            NativeBridgeErrorDomain::Root.as_str(),
            NativeBridgeErrorDomain::Telemetry.as_str(),
        ];
        assert_eq!(actual, expected);
    }

    #[test]
    fn decorate_message_appends_sentinel_and_json_after_human_prefix() {
        let payload =
            NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, "destroy_failed", "session not destroyable");
        let decorated = decorate_message("session not destroyable", &payload);

        // Human prefix is the leading line — callers that ignore the
        // typed payload see exactly what they always saw.
        let first_line = decorated.lines().next().expect("at least one line");
        assert_eq!(first_line, "session not destroyable");

        // Sentinel appears on a line of its own, followed by the JSON.
        assert!(decorated.contains(&format!("\n{NATIVE_BRIDGE_ERROR_SENTINEL}\n")));
        let (_human, trailer) = decorated.split_once(NATIVE_BRIDGE_ERROR_SENTINEL).expect("sentinel present");
        let json_str = trailer.trim_start_matches('\n');
        let parsed: serde_json::Value = serde_json::from_str(json_str).expect("trailer is JSON");
        assert_eq!(parsed["code"].as_str(), Some("destroy_failed"));
    }

    #[test]
    fn throw_with_payload_decorates_argument_exception_with_sentinel() {
        let _serial = lock_jni_tests();

        with_env(|env| {
            let payload =
                NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, "handle_unknown", "Unknown proxy handle")
                    .with_handle_state("unknown_handle");
            JniProxyError::unknown_handle().throw_with_payload(env, &payload);

            let exception = take_exception(env);
            // Existing leading prefix preserved verbatim.
            assert!(exception.starts_with("java.lang.IllegalArgumentException: Unknown proxy handle"));
            // Sentinel + payload appended.
            assert!(exception.contains(NATIVE_BRIDGE_ERROR_SENTINEL));
            assert!(exception.contains("\"code\":\"handle_unknown\""));
            assert!(exception.contains("\"handleState\":\"unknown_handle\""));
        });
    }

    #[test]
    fn throw_panic_with_payload_decorates_runtime_exception_with_sentinel() {
        let _serial = lock_jni_tests();

        with_env(|env| {
            let payload = NativeBridgeError::new(
                NativeBridgeErrorDomain::Proxy,
                "create_panic",
                "Proxy session creation panicked",
            );
            let mut unowned = crate::test_support::env_to_unowned(env);
            let panic_payload: Box<dyn Any + Send> = Box::new(String::from("boom"));
            throw_panic_with_payload(&mut unowned, "Proxy session creation panicked", panic_payload, &payload);

            let exception = take_exception(env);
            assert!(exception.starts_with("java.lang.RuntimeException: Proxy session creation panicked: boom"));
            assert!(exception.contains(NATIVE_BRIDGE_ERROR_SENTINEL));
            assert!(exception.contains("\"code\":\"create_panic\""));
        });
    }

    #[test]
    fn error_exception_mapping_matches_contract_fixture() {
        use golden_test_support::assert_contract_fixture;
        use serde_json::json;

        // This mapping must stay in sync with the match arms in JniProxyError::throw().
        // Use JNI-style slash separators to match the Rust throw() implementation,
        // but normalize to dot separators for cross-language readability.
        let mapping = json!([
            {"variant": "InvalidConfig", "javaClass": "java.lang.IllegalArgumentException"},
            {"variant": "InvalidArgument", "javaClass": "java.lang.IllegalArgumentException"},
            {"variant": "IllegalState", "javaClass": "java.lang.IllegalStateException"},
            {"variant": "Io", "javaClass": "java.io.IOException"},
            {"variant": "Serialization", "javaClass": "java.lang.RuntimeException"}
        ]);

        let actual = serde_json::to_string_pretty(&mapping).expect("serialize mapping");
        assert_contract_fixture("error_exception_mapping.json", &actual);
    }
}
