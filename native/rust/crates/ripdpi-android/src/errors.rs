use std::any::Any;

use android_support::sanitize_error_message;
use jni::JNIEnv;

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
    pub(crate) fn throw(self, env: &mut JNIEnv) {
        let (class, msg) = match &self {
            Self::InvalidConfig(_) | Self::InvalidArgument(_) => {
                ("java/lang/IllegalArgumentException", self.to_string())
            }
            Self::IllegalState(_) => ("java/lang/IllegalStateException", self.to_string()),
            Self::Io(_) => ("java/io/IOException", sanitize_error_message(&self.to_string(), "I/O failure")),
            Self::Serialization(_) => {
                ("java/lang/RuntimeException", sanitize_error_message(&self.to_string(), "Serialization failure"))
            }
        };
        if env.throw_new(class, &msg).is_err() {
            log::error!("Failed to throw {class}: {msg}");
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
}
