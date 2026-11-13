use std::io;

use crate::HelperResponse;

impl HelperResponse {
    /// Encodes a seqovl error without granting replay after an uncertain send.
    ///
    /// The sender must reserve these four outer error kinds for a refusal
    /// before emission with TCP repair restored. Other errors remain terminal.
    pub fn seqovl_error(error: &io::Error) -> Self {
        let mut response = Self::error(error.to_string());
        let label = match error.kind() {
            io::ErrorKind::InvalidInput => "invalid_input",
            io::ErrorKind::WouldBlock => "would_block",
            io::ErrorKind::Unsupported => "unsupported",
            io::ErrorKind::PermissionDenied => "permission_denied",
            _ => return response,
        };
        response.data = serde_json::json!({ "seqovl_safe_refusal": label });
        response
    }

    /// Reads additive error metadata after protocol-version validation.
    /// Missing, malformed and unknown metadata never permits replay.
    pub fn seqovl_safe_refusal_kind(&self) -> Option<io::ErrorKind> {
        if self.ok {
            return None;
        }
        match self.data.get("seqovl_safe_refusal")?.as_str()? {
            "invalid_input" => Some(io::ErrorKind::InvalidInput),
            "would_block" => Some(io::ErrorKind::WouldBlock),
            "unsupported" => Some(io::ErrorKind::Unsupported),
            "permission_denied" => Some(io::ErrorKind::PermissionDenied),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seqovl_refusal_survives_json_round_trip() {
        for (label, kind) in [
            ("invalid_input", io::ErrorKind::InvalidInput),
            ("would_block", io::ErrorKind::WouldBlock),
            ("unsupported", io::ErrorKind::Unsupported),
            ("permission_denied", io::ErrorKind::PermissionDenied),
        ] {
            let response = HelperResponse::seqovl_error(&io::Error::new(kind, "refused before send"));
            let encoded = serde_json::to_value(response).expect("encode response");
            assert_eq!(encoded["data"], serde_json::json!({ "seqovl_safe_refusal": label }));
            let decoded: HelperResponse = serde_json::from_value(encoded).expect("decode response");
            assert_eq!(decoded.seqovl_safe_refusal_kind(), Some(kind));
            assert_eq!(decoded.error.as_deref(), Some("refused before send"));
        }
    }

    #[test]
    fn seqovl_uncertain_errors_do_not_grant_replay() {
        for kind in [io::ErrorKind::Other, io::ErrorKind::TimedOut, io::ErrorKind::ConnectionReset] {
            let response = HelperResponse::seqovl_error(&io::Error::new(kind, "outcome unknown"));
            assert!(response.data.is_null());
            assert_eq!(response.seqovl_safe_refusal_kind(), None);
        }
    }

    #[test]
    fn seqovl_legacy_and_invalid_metadata_do_not_grant_replay() {
        for value in [
            serde_json::Value::Null,
            serde_json::json!({ "seqovl_safe_refusal": "timed_out" }),
            serde_json::json!({ "seqovl_safe_refusal": "other" }),
            serde_json::json!({ "seqovl_safe_refusal": 1 }),
            serde_json::json!({ "seqovl_safe_refusal": true }),
            serde_json::json!({ "seqovl_safe_refusal": null }),
            serde_json::json!({ "bytes_committed": 1 }),
        ] {
            let response = serde_json::json!({ "ok": false, "error": "refused", "data": value });
            let decoded: HelperResponse = serde_json::from_value(response).expect("legacy response");
            assert_eq!(decoded.seqovl_safe_refusal_kind(), None);
        }
        let success = HelperResponse::success(serde_json::json!({ "seqovl_safe_refusal": "would_block" }));
        assert_eq!(success.seqovl_safe_refusal_kind(), None);
    }
}
