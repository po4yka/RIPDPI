use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};
use ripdpi_packets::{TlsAlertPrefixStatus, parse_tls_alert_prefix, tls_server_hello_version};

/// TLS alert description code for `close_notify`.
///
/// `close_notify` signals a graceful, negotiated close — not a failure.
/// Both classification paths must treat it as "no signal" so a server
/// that shuts a connection down cleanly is not reported as censorship
/// and does not trigger a strategy retry.
pub(crate) const CLOSE_NOTIFY_ALERT: u8 = 0;

/// `user_canceled` is a closure alert (RFC 8446 s6.1), not a protocol failure.
const USER_CANCELED_ALERT: u8 = 90;
const TLS_ALERT_LEVEL_WARNING: u8 = 1;

/// True for `close_notify` and `user_canceled`, which close a connection without a protocol failure.
pub(crate) fn is_closure_alert(code: u8) -> bool {
    matches!(code, CLOSE_NOTIFY_ALERT | USER_CANCELED_ALERT)
}

/// A complete closure alert gives no evidence of a successful handshake.
pub fn is_tls_closure_response(response: &[u8]) -> bool {
    matches!(tls_alert_disposition(response), TlsAlertDisposition::Closure)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TlsAlertDisposition {
    NotAlert,
    Invalid,
    Incomplete,
    /// A complete nonfatal warning whose negotiated version is not yet known.
    WarningPending(u8),
    Failure(u8),
    Closure,
    Continued {
        alert_bytes: usize,
    },
}

/// Interpret complete alert messages in wire order. Record versions do not prove negotiation.
pub fn tls_alert_disposition(response: &[u8]) -> TlsAlertDisposition {
    let Some(prefix) = parse_tls_alert_prefix(response) else { return TlsAlertDisposition::NotAlert };
    let mut warning = None;
    let mut canceled = false;
    for [level, code] in prefix.messages {
        if !matches!(level, 1 | 2) {
            return TlsAlertDisposition::Invalid;
        }
        if code == CLOSE_NOTIFY_ALERT {
            return TlsAlertDisposition::Closure;
        }
        if code == USER_CANCELED_ALERT {
            canceled = true;
            continue;
        }
        if level != TLS_ALERT_LEVEL_WARNING {
            return TlsAlertDisposition::Failure(code);
        }
        warning.get_or_insert(code);
    }
    if canceled {
        return TlsAlertDisposition::Closure;
    }
    if prefix.status == TlsAlertPrefixStatus::Invalid {
        return TlsAlertDisposition::Invalid;
    }
    let Some(code) = warning else {
        return if prefix.status == TlsAlertPrefixStatus::Incomplete {
            TlsAlertDisposition::Incomplete
        } else {
            TlsAlertDisposition::NotAlert
        };
    };
    match tls_server_hello_version(&response[prefix.record_bytes..]) {
        Some(version) if version <= 0x0303 => TlsAlertDisposition::Continued { alert_bytes: prefix.record_bytes },
        Some(_) => TlsAlertDisposition::Failure(code),
        None => TlsAlertDisposition::WarningPending(code),
    }
}

/// Classify a raw TLS alert record as a [`FailureClass::TlsAlert`]
/// failure. Returns `None` for non-alert records, incomplete or invalid records, the
/// closure alerts `close_notify` and `user_canceled`, and a warning-level
/// alert followed by a complete TLS 1.2 ServerHello (RFC 6066 s3 lets a server send a
/// warning `unrecognized_name` and continue with its ServerHello).
/// For a byte stream, use [`tls_alert_disposition`] first: `WarningPending` needs a bounded
/// continuation window. This function retains the standalone-buffer warning failure fallback.
pub fn classify_tls_alert(response: &[u8]) -> Option<ClassifiedFailure> {
    let (TlsAlertDisposition::Failure(alert_code) | TlsAlertDisposition::WarningPending(alert_code)) =
        tls_alert_disposition(response)
    else {
        return None;
    };
    let alert_desc = tls_alert_description(Some(alert_code));
    Some(
        ClassifiedFailure::new(
            FailureClass::TlsAlert,
            FailureStage::FirstResponse,
            FailureAction::RetryWithMatchingGroup,
            format!("TLS alert received: {alert_desc}"),
        )
        .with_tag("recordType", "21")
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

/// Canonical TLS alert-code description table shared by the raw-record
/// and field-cache classification paths.
pub(crate) fn tls_alert_description(alert: Option<u8>) -> &'static str {
    match alert {
        Some(0) => "close_notify",
        Some(10) => "unexpected_message",
        Some(20) => "bad_record_mac",
        Some(21) => "decryption_failed",
        Some(22) => "record_overflow",
        Some(30) => "decompression_failure",
        Some(40) => "handshake_failure",
        Some(41) => "no_certificate",
        Some(42) => "bad_certificate",
        Some(43) => "unsupported_certificate",
        Some(44) => "certificate_revoked",
        Some(45) => "certificate_expired",
        Some(46) => "certificate_unknown",
        Some(47) => "illegal_parameter",
        Some(48) => "unknown_ca",
        Some(49) => "access_denied",
        Some(50) => "decode_error",
        Some(51) => "decrypt_error",
        Some(60) => "export_restriction",
        Some(70) => "protocol_version",
        Some(71) => "insufficient_security",
        Some(80) => "internal_error",
        Some(86) => "inappropriate_fallback",
        Some(90) => "user_canceled",
        Some(100) => "no_renegotiation",
        Some(109) => "missing_extension",
        Some(110) => "unsupported_extension",
        Some(111) => "certificate_unobtainable",
        Some(112) => "unrecognized_name",
        Some(113) => "bad_certificate_status_response",
        Some(114) => "bad_certificate_hash_value",
        Some(115) => "unknown_psk_identity",
        Some(116) => "certificate_required",
        Some(120) => "no_application_protocol",
        Some(121) => "ech_required",
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
    fn tls_alert_requires_complete_record_and_valid_level() {
        for record in [
            vec![0x15, 0x03, 0x03, 0x00, 0x00, 0x02, 0x28],
            vec![0x15, 0x03, 0x03, 0x00, 0x01, 0x02, 0x28],
            vec![0x15, 0x03, 0x03, 0x00, 0x08, 0x02, 0x28],
            vec![0x15, 0x03, 0x03, 0xff, 0xff, 0x02, 0x28],
            vec![0x15, 0x03, 0x03, 0x00, 0x02, 0x00, 0x28],
            vec![0x15, 0x03, 0x03, 0x00, 0x02, 0x03, 0x28],
        ] {
            assert!(classify_tls_alert(&record).is_none(), "not a complete valid alert: {record:02x?}");
        }
        let coalesced = [0x15, 0x03, 0x03, 0x00, 0x04, 0x02, 0x28, 0x01, 0x00];
        assert!(classify_tls_alert(&coalesced).is_some(), "TLS 1.2 may coalesce alerts");
        let with_following_record = [0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28, 0x16, 0x03, 0x03, 0x00, 0x01, 0x02];
        let failure = classify_tls_alert(&with_following_record).expect("first complete fatal alert");
        assert!(failure.evidence.tags.iter().any(|tag| tag == "alert=handshake_failure"));
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
            (45, "certificate_expired"),
            (120, "no_application_protocol"),
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
    fn user_canceled_is_a_closure_alert_not_a_failure() {
        let canceled = [0x15, 0x03, 0x03, 0x00, 0x02, 0x01, 90];
        assert!(classify_tls_alert(&canceled).is_none());
    }

    #[test]
    fn warning_alert_followed_by_server_hello_is_not_a_failure() {
        // RFC 5246 ServerHello: version, random, empty session id, cipher, compression.
        let mut response = vec![0x15, 3, 3, 0, 2, 1, 112, 0x16, 3, 3, 0, 42, 2, 0, 0, 38, 3, 3];
        response.extend([0; 32]);
        response.extend([0, 0, 0x2f, 0]);
        assert!(classify_tls_alert(&response).is_none());
        assert!(classify_tls_alert(&response[..13]).is_some(), "a record prefix does not prove negotiation");
    }

    #[test]
    fn fatal_or_lone_warning_alerts_still_fail() {
        let fatal_then_hello = [0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 112, 0x16, 0x03, 0x03];
        assert!(classify_tls_alert(&fatal_then_hello).is_some());
        let lone_warning = [0x15, 0x03, 0x03, 0x00, 0x02, 0x01, 112];
        assert!(classify_tls_alert(&lone_warning).is_some());
    }

    #[test]
    fn redirect_failure_produces_correct_class_and_stage() {
        let f = classify_redirect_failure("redirected to block page");
        assert_eq!(f.class, FailureClass::Redirect);
        assert_eq!(f.stage, FailureStage::FirstResponse);
        assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
    }
}
