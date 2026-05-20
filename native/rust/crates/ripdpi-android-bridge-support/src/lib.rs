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

use std::any::Any;

use android_support::{
    sanitize_error_message, throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env,
    throw_runtime_exception, throw_runtime_exception_env,
};
use jni::sys::jlong;
use jni::{Env, EnvUnowned};

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

/// Decode the opaque `jlong` handle passed back from Kotlin into a native
/// registry key. Returns `None` for `0` (the "no handle" sentinel) and for any
/// value outside the non-negative `u64` range, so callers can reject stale or
/// never-created handles before a registry lookup.
pub fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

#[cfg(feature = "test-support")]
pub mod test_support {
    use super::*;
    use std::sync::MutexGuard;

    use android_support::describe_exception;
    use jni::objects::JString;
    use jni::sys::jstring;

    pub fn lock_jni_tests() -> MutexGuard<'static, ()> {
        static JNI_TEST_MUTEX: once_cell::sync::Lazy<std::sync::Mutex<()>> =
            once_cell::sync::Lazy::new(|| std::sync::Mutex::new(()));
        JNI_TEST_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub fn with_env<R>(f: impl for<'a> FnOnce(&mut Env<'a>) -> R) -> R {
        static TEST_JVM: once_cell::sync::OnceCell<jni::JavaVM> = once_cell::sync::OnceCell::new();
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
