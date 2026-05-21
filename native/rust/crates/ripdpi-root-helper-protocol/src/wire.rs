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
    use super::{valid_session_nonce, HelperRequest, HelperResponse, MAX_SESSION_NONCE_BYTES, MIN_SESSION_NONCE_BYTES};
    use serde_json::{json, Value};

    #[test]
    fn validates_urlsafe_session_nonce_shape() {
        assert!(valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDEF"));
        assert!(valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDEF0123456789-_"));
        assert!(!valid_session_nonce("short"));
        assert!(!valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDE+"));
        assert!(!valid_session_nonce("abcdefghijklmnopqrstuvwxyzABCDE/"));
    }

    #[test]
    fn session_nonce_length_bounds_are_inclusive() {
        assert!(valid_session_nonce(&"a".repeat(MIN_SESSION_NONCE_BYTES)));
        assert!(valid_session_nonce(&"a".repeat(MAX_SESSION_NONCE_BYTES)));
        assert!(!valid_session_nonce(&"a".repeat(MIN_SESSION_NONCE_BYTES - 1)));
        assert!(!valid_session_nonce(&"a".repeat(MAX_SESSION_NONCE_BYTES + 1)));
        assert!(!valid_session_nonce(""));
    }

    #[test]
    fn helper_request_serializes_command_params_and_nonce_in_order() {
        let request = HelperRequest {
            command: "send_fake_rst".to_string(),
            params: json!({ "default_ttl": 64 }),
            session_nonce: Some("abcdefghijklmnopqrstuvwxyzABCDEF".to_string()),
        };
        let encoded = serde_json::to_string(&request).expect("serialize request");
        assert_eq!(
            encoded,
            concat!(
                r#"{"command":"send_fake_rst","params":{"default_ttl":64},"#,
                r#""session_nonce":"abcdefghijklmnopqrstuvwxyzABCDEF"}"#,
            ),
        );
    }

    #[test]
    fn helper_request_omits_null_params_and_absent_nonce() {
        let request = HelperRequest { command: "shutdown".to_string(), params: Value::Null, session_nonce: None };
        let encoded = serde_json::to_string(&request).expect("serialize request");
        assert_eq!(encoded, r#"{"command":"shutdown"}"#);
    }

    #[test]
    fn helper_request_accepts_an_unknown_command_string() {
        // The wire protocol is intentionally permissive: any `command` string
        // deserializes. Rejecting an unknown command is the helper
        // dispatcher's job, not the protocol's — see ROOT_HELPER_CONTRACT.md.
        let request: HelperRequest =
            serde_json::from_str(r#"{"command":"totally_unknown_command_v999"}"#).expect("deserialize request");
        assert_eq!(request.command, "totally_unknown_command_v999");
        assert!(request.params.is_null());
        assert!(request.session_nonce.is_none());
    }

    #[test]
    fn helper_request_json_carries_no_file_descriptor_field() {
        // File descriptors travel as SCM_RIGHTS ancillary data, never inside
        // the JSON payload — the request object has exactly three keys.
        let request = HelperRequest {
            command: "send_fake_tcp".to_string(),
            params: json!({ "ttl": 1 }),
            session_nonce: Some("abcdefghijklmnopqrstuvwxyzABCDEF".to_string()),
        };
        let encoded = serde_json::to_string(&request).expect("serialize request");
        let value: Value = serde_json::from_str(&encoded).expect("reparse request");
        let object = value.as_object().expect("request is a JSON object");
        let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["command", "params", "session_nonce"]);
    }

    #[test]
    fn helper_response_success_and_error_shapes_are_stable() {
        let success = HelperResponse::success(json!({ "raw_ipv4": true }));
        assert_eq!(serde_json::to_string(&success).expect("serialize"), r#"{"ok":true,"data":{"raw_ipv4":true}}"#);

        let failure = HelperResponse::error("invalid root-helper session nonce");
        assert_eq!(
            serde_json::to_string(&failure).expect("serialize"),
            r#"{"ok":false,"error":"invalid root-helper session nonce","data":null}"#,
        );
    }
}
