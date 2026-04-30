use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;
use rustls::client::EchStatus;

use super::types::TlsObservation;

pub fn tls_version_label(version: Option<rustls::ProtocolVersion>) -> Option<String> {
    version.map(|value| match value {
        rustls::ProtocolVersion::TLSv1_2 => "TLS1.2".to_string(),
        rustls::ProtocolVersion::TLSv1_3 => "TLS1.3".to_string(),
        other => format!("{other:?}"),
    })
}

pub fn ech_status_label(status: EchStatus) -> String {
    match status {
        EchStatus::NotOffered => "not_offered".to_string(),
        EchStatus::Grease => "grease".to_string(),
        EchStatus::Offered => "offered".to_string(),
        EchStatus::Accepted => "accepted".to_string(),
        EchStatus::Rejected => "rejected".to_string(),
    }
}
/// Parse the TLS alert code and description from a stringified rustls error.
/// Rustls formats `AlertReceived` as `"received fatal alert: {alert:?}"` where
/// the alert Debug repr is the variant name (e.g. "HandshakeFailure").
pub(super) fn parse_alert_from_error(error: &str) -> (Option<u8>, Option<String>) {
    const PREFIX: &str = "received fatal alert: ";
    let Some(alert_name) = error.strip_prefix(PREFIX) else {
        return (None, None);
    };
    let alert_name = alert_name.trim();
    let code = match alert_name {
        "CloseNotify" => 0x00,
        "UnexpectedMessage" => 0x0a,
        "BadRecordMac" => 0x14,
        "DecryptionFailed" => 0x15,
        "RecordOverflow" => 0x16,
        "DecompressionFailure" => 0x1e,
        "HandshakeFailure" => 0x28,
        "NoCertificate" => 0x29,
        "BadCertificate" => 0x2a,
        "UnsupportedCertificate" => 0x2b,
        "CertificateRevoked" => 0x2c,
        "CertificateExpired" => 0x2d,
        "CertificateUnknown" => 0x2e,
        "IllegalParameter" => 0x2f,
        "UnknownCA" => 0x30,
        "AccessDenied" => 0x31,
        "DecodeError" => 0x32,
        "DecryptError" => 0x33,
        "ExportRestriction" => 0x3c,
        "ProtocolVersion" => 0x46,
        "InsufficientSecurity" => 0x47,
        "InternalError" => 0x50,
        "InappropriateFallback" => 0x56,
        "UserCanceled" => 0x5a,
        "NoRenegotiation" => 0x64,
        "MissingExtension" => 0x6d,
        "UnsupportedExtension" => 0x6e,
        "UnrecognisedName" => 0x70,
        "BadCertificateStatusResponse" => 0x71,
        "UnknownPSKIdentity" => 0x73,
        "CertificateRequired" => 0x74,
        "NoApplicationProtocol" => 0x78,
        "EncryptedClientHelloRequired" => 0x79,
        _ => return (None, Some(alert_name.to_string())),
    };
    (Some(code), Some(alert_name.to_string()))
}

/// Map a TLS alert code (with optional timing) to a DPI firmware signature.
///
/// Common patterns observed in Russian middlebox equipment:
/// - Alert 0x28 (`HandshakeFailure`) with fast timing (<200ms) indicates a
///   generic middlebox block injecting a TLS alert before the real server responds.
/// - Alert 0x46 (`ProtocolVersion`) suggests the middlebox is performing a version
///   downgrade attack, rejecting modern TLS versions.
/// - Alert 0x70 (`UnrecognisedName`) indicates SNI-based blocking where the
///   DPI device inspects the SNI extension and rejects blacklisted hostnames.
pub(super) fn classify_tls_dpi_signature(alert_code: u8, tls_handshake_ms: Option<u64>) -> Option<String> {
    let fast_timing = tls_handshake_ms.is_some_and(|ms| ms < 200);
    match alert_code {
        0x28 if fast_timing => Some("tspu_generic_block".to_string()),
        0x28 => Some("handshake_failure_slow".to_string()),
        0x46 => Some("tspu_version_downgrade".to_string()),
        0x70 => Some("sni_based_block".to_string()),
        0x56 => Some("middlebox_fallback_reject".to_string()),
        _ => None,
    }
}

pub fn is_certificate_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("certificate")
        || lower.contains("unknown issuer")
        || lower.contains("not valid")
        || lower.contains("bad certificate")
}

pub fn classify_tls_signal(tls13: &TlsObservation, tls12: &TlsObservation) -> &'static str {
    if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid"
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_consistent"
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split_low_confidence"
    } else {
        "tls_unavailable"
    }
}

pub fn preferred_tls_observation<'a>(tls13: &'a TlsObservation, tls12: &'a TlsObservation) -> &'a TlsObservation {
    if tls13.certificate_anomaly {
        tls13
    } else if tls12.certificate_anomaly {
        tls12
    } else if tls13.status == "tls_ok" {
        tls13
    } else if tls12.status == "tls_ok" {
        tls12
    } else {
        tls13
    }
}

/// Returns true if a TLS version split is caused by the server rejecting
/// one TLS version (protocol alert), not by network interference (timeout/reset).
/// Server-side rejections indicate the server simply doesn't support that version,
/// not that an ISP is selectively blocking it.
pub fn is_server_tls_version_rejection(tls13: &TlsObservation, tls12: &TlsObservation) -> bool {
    let failed = if tls13.status != "tls_ok" { tls13 } else { tls12 };
    let error = failed.error.as_deref().unwrap_or("");
    error.contains("AlertReceived")
        || error.contains("protocol_version")
        || error.contains("handshake_failure")
        || error.contains("inappropriate_fallback")
        || error.contains("InappropriateHandshakeMessage")
}
pub(super) fn first_flight_plan_label(plan: &TlsTemplateFirstFlightPlan) -> String {
    format!(
        "{}|{}|resolver={}|outer={}|ech_present={}",
        plan.record_choreography.as_str(),
        plan.ech_bootstrap_policy,
        plan.ech_bootstrap_resolver_id.unwrap_or("none"),
        plan.ech_outer_extension_policy,
        plan.ech_present_in_input
    )
}
