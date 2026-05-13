use serde::{Deserialize, Serialize};

pub const MIN_SESSION_NONCE_BYTES: usize = 32;
pub const MAX_SESSION_NONCE_BYTES: usize = 128;

#[derive(Debug, Serialize, Deserialize)]
pub struct HelperRequest {
    pub command: String,
    #[serde(default, skip_serializing_if = "serde_json::Value::is_null")]
    pub params: serde_json::Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_nonce: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct HelperResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default)]
    pub data: serde_json::Value,
}

impl HelperResponse {
    pub fn success(data: serde_json::Value) -> Self {
        Self { ok: true, error: None, data }
    }

    pub fn error(msg: impl Into<String>) -> Self {
        Self { ok: false, error: Some(msg.into()), data: serde_json::Value::Null }
    }
}

pub fn valid_session_nonce(value: &str) -> bool {
    let len = value.len();
    (MIN_SESSION_NONCE_BYTES..=MAX_SESSION_NONCE_BYTES).contains(&len)
        && value.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

#[cfg(test)]
mod tests {
    use super::valid_session_nonce;

    #[test]
    fn validates_urlsafe_session_nonce_shape() {
        assert!(valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDEF"));
        assert!(valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDEF0123456789-_"));
        assert!(!valid_session_nonce("short"));
        assert!(!valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDE+"));
        assert!(!valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDE/"));
    }
}
