use std::any::Any;

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
            Self::Io(_) => ("java/io/IOException", self.to_string()),
            Self::Serialization(_) => ("java/lang/RuntimeException", self.to_string()),
        };
        let _ = env.throw_new(class, &msg);
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
