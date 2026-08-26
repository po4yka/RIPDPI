use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

/// TLS alert description code for `close_notify`.
///
/// `close_notify` signals a graceful, negotiated close — not a failure.
/// Both classification paths must treat it as "no signal" so a server
/// that shuts a connection down cleanly is not reported as censorship
/// and does not trigger a strategy retry.
pub(crate) const CLOSE_NOTIFY_ALERT: u8 = 0;

/// Classify a raw TLS alert record as a [`FailureClass::TlsAlert`]
/// failure. Returns `None` for non-alert records, truncated records, and
/// `close_notify` (graceful close — see [`CLOSE_NOTIFY_ALERT`]).
pub fn classify_tls_alert(response: &[u8]) -> Option<ClassifiedFailure> {
    if !looks_like_tls_alert(response) {
        return None;
    }
    let alert_code = response.get(6).copied()?;
    if alert_code == CLOSE_NOTIFY_ALERT {
        return None;
    }
    let alert_desc = tls_alert_description(Some(alert_code));
    Some(
        ClassifiedFailure::new(
            FailureClass::TlsAlert,
            FailureStage::FirstResponse,
            FailureAction::RetryWithMatchingGroup,
            format!("TLS alert received: {alert_desc}"),
        )
        .with_tag("recordType", response[0].to_string())
        .with_tag("alert", alert_desc),
    )
}

pub fn classify_tls_handshake_failure(summary: impl Into<String>) -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::TlsHandshakeFailure,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        summary,
    )
}

pub fn classify_redirect_failure(summary: impl Into<String>) -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::Redirect,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        summary,
    )
}

fn looks_like_tls_alert(response: &[u8]) -> bool {
    response.len() >= 7 && response[0] == 0x15 && response[1] == 0x03 && (0x00..=0x04).contains(&response[2])
}

/// Canonical TLS alert-code description table shared by the raw-record
/// and field-cache classification paths.
pub(crate) fn tls_alert_description(alert: Option<u8>) -> &'static str {
    match alert {
        Some(0) => "close_notify",
        Some(10) => "unexpected_message",
        Some(20) => "bad_record_mac",
        Some(40) => "handshake_failure",
        Some(42) => "bad_certificate",
        Some(47) => "illegal_parameter",
        Some(48) => "unknown_ca",
        Some(70) => "protocol_version",
        Some(80) => "internal_error",
        Some(112) => "unrecognized_name",
        Some(_) => "other",
        None => "unknown",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tls_alerts_are_distinguished_from_generic_handshake_failures() {
        let alert = [0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];
        let classified = classify_tls_alert(&alert).expect("tls alert");

        assert_eq!(classified.class, FailureClass::TlsAlert);

        let generic = classify_tls_handshake_failure("server hello mismatch");
        assert_eq!(generic.class, FailureClass::TlsHandshakeFailure);
    }

    #[test]
    fn tls_alert_returns_none_for_short_input() {
        assert!(classify_tls_alert(&[0x15, 0x03, 0x03, 0x00, 0x02, 0x02]).is_none());
        assert!(classify_tls_alert(&[]).is_none());
    }

    #[test]
    fn tls_alert_returns_none_for_wrong_record_type() {
        let record = [0x16, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];
        assert!(classify_tls_alert(&record).is_none());
    }

    #[test]
    fn tls_alert_returns_none_for_invalid_tls_version() {
        let bad_major = [0x15, 0x02, 0x03, 0x00, 0x02, 0x02, 0x28];
        assert!(classify_tls_alert(&bad_major).is_none());

        let bad_minor = [0x15, 0x03, 0x05, 0x00, 0x02, 0x02, 0x28];
        assert!(classify_tls_alert(&bad_minor).is_none());
    }

    #[test]
    fn tls_alert_identifies_known_alert_codes() {
        let cases: &[(u8, &str)] = &[
            (10, "unexpected_message"),
            (20, "bad_record_mac"),
            (40, "handshake_failure"),
            (42, "bad_certificate"),
            (48, "unknown_ca"),
            (70, "protocol_version"),
            (80, "internal_error"),
            (112, "unrecognized_name"),
            (99, "other"),
        ];
        for &(code, expected_desc) in cases {
            let record = [0x15, 0x03, 0x03, 0x00, 0x02, 0x02, code];
            let f = classify_tls_alert(&record).expect("should classify valid TLS alert");
            assert!(
                f.evidence.tags.iter().any(|t| t == &format!("alert={expected_desc}")),
                "alert code {code} should produce description '{expected_desc}', got tags {:?}",
                f.evidence.tags,
            );
        }
    }

    #[test]
    fn close_notify_is_a_graceful_close_not_a_failure() {
        // Well-formed close_notify alert record.
        let close = [0x15, 0x03, 0x03, 0x00, 0x02, 0x01, CLOSE_NOTIFY_ALERT];
        assert!(classify_tls_alert(&close).is_none());
    }

    #[test]
    fn redirect_failure_produces_correct_class_and_stage() {
        let f = classify_redirect_failure("redirected to block page");
        assert_eq!(f.class, FailureClass::Redirect);
        assert_eq!(f.stage, FailureStage::FirstResponse);
        assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
    }
}
