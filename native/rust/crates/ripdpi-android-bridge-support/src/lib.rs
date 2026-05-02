use std::any::Any;

use android_support::{
    sanitize_error_message, throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env,
    throw_runtime_exception, throw_runtime_exception_env,
};
use jni::{Env, EnvUnowned};

#[derive(Debug, thiserror::Error)]
pub(crate) enum JniProxyError {
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
    pub(crate) fn throw(self, env: &mut Env<'_>) {
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

pub(crate) fn extract_panic_message(payload: Box<dyn Any + Send>) -> String {
    payload
        .downcast_ref::<String>()
        .map(String::as_str)
        .or_else(|| payload.downcast_ref::<&str>().copied())
        .unwrap_or("unknown panic")
        .to_string()
}

pub(crate) fn throw_panic(env: &mut EnvUnowned<'_>, prefix: &str, payload: Box<dyn Any + Send>) {
    throw_runtime_exception(env, format!("{prefix}: {}", extract_panic_message(payload)));
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::io;

    use crate::support::{lock_jni_tests, take_exception, with_env};

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
