use serde::{Deserialize, Serialize};

pub const MIN_SESSION_NONCE_BYTES: usize = 32;
pub const MAX_SESSION_NONCE_BYTES: usize = 128;

/// Wire-protocol version stamped by the helper on every response.
///
/// Bumped only on a backward-incompatible protocol change. Clients may use it
/// to gate features they need a known-recent helper for. Deserialization keeps
/// accepting missing values so clients can report a precise compatibility
/// error, but privileged operations must not trust an unversioned response.
pub const PROTOCOL_VERSION: u32 = 3;

/// Capability-set version stamped by the helper on every response.
///
/// Bumped when the `probe_capabilities` JSON shape changes (new keys, new
/// runtime capabilities). Independent from [`PROTOCOL_VERSION`]: the wire
/// protocol can be stable while the capability set evolves. Missing on
/// responses from a pre-versioned helper.
pub const CAPABILITY_VERSION: u32 = 1;

#[derive(Debug, Serialize, Deserialize)]
pub struct HelperRequest {
    /// Client wire version. The helper validates this before dispatching any
    /// command so a stale client cannot trigger a privileged side effect.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_version: Option<u32>,
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
    /// Helper's [`PROTOCOL_VERSION`] at the time the response was minted.
    /// `None` on pre-versioned helpers and on responses constructed before
    /// `with_versions` was called.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_version: Option<u32>,
    /// Helper's [`CAPABILITY_VERSION`] at the time the response was minted.
    /// Same back-compat semantics as `protocol_version`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capability_version: Option<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtocolVersionError {
    Missing,
    Mismatch { expected: u32, actual: u32 },
}

impl std::fmt::Display for ProtocolVersionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Missing => formatter.write_str("root-helper message is missing protocol_version"),
            Self::Mismatch { expected, actual } => {
                write!(formatter, "root-helper protocol version mismatch: expected {expected}, received {actual}")
            }
        }
    }
}

impl HelperRequest {
    /// Require the request to use the exact helper wire protocol before any
    /// privileged command is dispatched.
    pub fn validate_protocol_version(&self) -> Result<(), ProtocolVersionError> {
        validate_protocol_version(self.protocol_version)
    }
}

impl std::error::Error for ProtocolVersionError {}

impl HelperResponse {
    pub fn success(data: serde_json::Value) -> Self {
        Self { ok: true, error: None, data, protocol_version: None, capability_version: None }
    }

    pub fn error(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: Some(msg.into()),
            data: serde_json::Value::Null,
            protocol_version: None,
            capability_version: None,
        }
    }

    /// Stamp this response with the current `protocol_version` /
    /// `capability_version`. Called by the helper just before
    /// serialization — every response the helper emits carries the version
    /// pair. Pure data; no I/O.
    #[must_use]
    pub fn with_versions(mut self, protocol_version: u32, capability_version: u32) -> Self {
        self.protocol_version = Some(protocol_version);
        self.capability_version = Some(capability_version);
        self
    }

    /// Require an exact wire-protocol match before trusting response data or fds.
    pub fn validate_protocol_version(&self) -> Result<(), ProtocolVersionError> {
        validate_protocol_version(self.protocol_version)
    }
}

fn validate_protocol_version(protocol_version: Option<u32>) -> Result<(), ProtocolVersionError> {
    match protocol_version {
        Some(PROTOCOL_VERSION) => Ok(()),
        Some(actual) => Err(ProtocolVersionError::Mismatch { expected: PROTOCOL_VERSION, actual }),
        None => Err(ProtocolVersionError::Missing),
    }
}

pub fn valid_session_nonce(value: &str) -> bool {
    let len = value.len();
    (MIN_SESSION_NONCE_BYTES..=MAX_SESSION_NONCE_BYTES).contains(&len)
        && value.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

#[cfg(test)]
mod tests {
    use super::{
        CAPABILITY_VERSION, HelperRequest, HelperResponse, MAX_SESSION_NONCE_BYTES, MIN_SESSION_NONCE_BYTES,
        PROTOCOL_VERSION, valid_session_nonce,
    };
    use serde_json::{Value, json};

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
            protocol_version: Some(PROTOCOL_VERSION),
            command: "v3/send_fake_rst".to_string(),
            params: json!({ "default_ttl": 64 }),
            session_nonce: Some("abcdefghijklmnopqrstuvwxyzABCDEF".to_string()),
        };
        let encoded = serde_json::to_string(&request).expect("serialize request");
        assert_eq!(
            encoded,
            concat!(
                r#"{"protocol_version":3,"command":"v3/send_fake_rst","params":{"default_ttl":64},"#,
                r#""session_nonce":"abcdefghijklmnopqrstuvwxyzABCDEF"}"#,
            ),
        );
    }

    #[test]
    fn helper_request_omits_null_params_and_absent_nonce() {
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: "v3/shutdown".to_string(),
            params: Value::Null,
            session_nonce: None,
        };
        let encoded = serde_json::to_string(&request).expect("serialize request");
        assert_eq!(encoded, r#"{"protocol_version":3,"command":"v3/shutdown"}"#);
    }

    #[test]
    fn helper_request_accepts_an_unknown_command_string() {
        // The wire protocol is intentionally permissive: any `command` string
        // deserializes. Rejecting an unknown command is the helper
        // dispatcher's job, not the protocol's — see ROOT_HELPER_CONTRACT.md.
        let request: HelperRequest =
            serde_json::from_str(r#"{"command":"totally_unknown_command_v999"}"#).expect("deserialize request");
        assert_eq!(request.command, "totally_unknown_command_v999");
        assert!(request.protocol_version.is_none());
        assert!(request.params.is_null());
        assert!(request.session_nonce.is_none());
    }

    #[test]
    fn helper_request_json_carries_no_file_descriptor_field() {
        // File descriptors travel as SCM_RIGHTS ancillary data, never inside
        // the JSON payload — the request object has exactly four keys.
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: "v3/send_fake_tcp".to_string(),
            params: json!({ "ttl": 1 }),
            session_nonce: Some("abcdefghijklmnopqrstuvwxyzABCDEF".to_string()),
        };
        let encoded = serde_json::to_string(&request).expect("serialize request");
        let value: Value = serde_json::from_str(&encoded).expect("reparse request");
        let object = value.as_object().expect("request is a JSON object");
        let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["command", "params", "protocol_version", "session_nonce"]);
    }

    #[test]
    fn helper_request_requires_the_current_protocol_version() {
        let current = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: "v3/shutdown".to_string(),
            params: Value::Null,
            session_nonce: None,
        };
        assert!(current.validate_protocol_version().is_ok());

        let stale = HelperRequest { protocol_version: Some(PROTOCOL_VERSION - 1), ..current };
        assert!(matches!(stale.validate_protocol_version(), Err(super::ProtocolVersionError::Mismatch { .. })));

        let legacy = HelperRequest { protocol_version: None, ..stale };
        assert!(matches!(legacy.validate_protocol_version(), Err(super::ProtocolVersionError::Missing)));
    }

    #[test]
    fn helper_response_success_and_error_shapes_are_stable() {
        // Bare constructors omit the version fields — wire shape is back-compat
        // with pre-versioned clients (the version fields are `skip_serializing_if`).
        let success = HelperResponse::success(json!({ "raw_ipv4": true }));
        assert_eq!(serde_json::to_string(&success).expect("serialize"), r#"{"ok":true,"data":{"raw_ipv4":true}}"#);

        let failure = HelperResponse::error("invalid root-helper session nonce");
        assert_eq!(
            serde_json::to_string(&failure).expect("serialize"),
            r#"{"ok":false,"error":"invalid root-helper session nonce","data":null}"#,
        );
    }

    #[test]
    fn helper_response_with_versions_appends_protocol_and_capability_versions() {
        let success = HelperResponse::success(json!({ "raw_ipv4": true })).with_versions(7, 11);
        let encoded = serde_json::to_string(&success).expect("serialize");
        assert_eq!(encoded, r#"{"ok":true,"data":{"raw_ipv4":true},"protocol_version":7,"capability_version":11}"#,);
    }

    #[test]
    fn old_response_json_without_version_fields_still_deserializes() {
        // Wire compatibility — a pre-versioned helper response decodes cleanly,
        // the new fields default to `None`. This pins the back-compat contract.
        let legacy = r#"{"ok":true,"data":{"raw_ipv4":true}}"#;
        let decoded: HelperResponse = serde_json::from_str(legacy).expect("legacy success decodes");
        assert!(decoded.ok);
        assert_eq!(decoded.data, json!({ "raw_ipv4": true }));
        assert!(decoded.protocol_version.is_none());
        assert!(decoded.capability_version.is_none());

        let legacy_error = r#"{"ok":false,"error":"some failure","data":null}"#;
        let decoded_error: HelperResponse = serde_json::from_str(legacy_error).expect("legacy error decodes");
        assert!(!decoded_error.ok);
        assert_eq!(decoded_error.error.as_deref(), Some("some failure"));
        assert!(decoded_error.protocol_version.is_none());
        assert!(decoded_error.capability_version.is_none());
    }

    #[test]
    fn new_response_with_versions_round_trips_through_serde() {
        let original =
            HelperResponse::success(json!({ "raw_ipv4": true })).with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
        let encoded = serde_json::to_string(&original).expect("encode");
        let decoded: HelperResponse = serde_json::from_str(&encoded).expect("decode");
        assert!(decoded.ok);
        assert_eq!(decoded.protocol_version, Some(PROTOCOL_VERSION));
        assert_eq!(decoded.capability_version, Some(CAPABILITY_VERSION));
    }

    #[test]
    fn response_protocol_validation_rejects_legacy_and_mismatched_helpers() {
        assert_eq!(
            HelperResponse::success(Value::Null).validate_protocol_version(),
            Err(super::ProtocolVersionError::Missing)
        );
        assert_eq!(
            HelperResponse::success(Value::Null)
                .with_versions(PROTOCOL_VERSION + 1, CAPABILITY_VERSION)
                .validate_protocol_version(),
            Err(super::ProtocolVersionError::Mismatch { expected: PROTOCOL_VERSION, actual: PROTOCOL_VERSION + 1 })
        );
        assert!(
            HelperResponse::success(Value::Null)
                .with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION)
                .validate_protocol_version()
                .is_ok()
        );
    }
}
