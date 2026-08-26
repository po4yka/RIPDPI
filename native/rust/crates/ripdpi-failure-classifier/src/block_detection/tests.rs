use crate::{FailureAction, FailureClass, FailureStage};

use super::fingerprint_catalog::parse_fingerprint_line;
use super::http_parse::parse_http_response;
use super::*;

#[test]
fn bundled_fingerprints_parse_provider_entries() {
    let fingerprints = load_blockpage_fingerprints();
    assert!(!fingerprints.is_empty());
    assert!(fingerprints.iter().any(|value| value.name == "rkn_standard"));
}

#[test]
fn fingerprint_line_parser_trims_fields_and_normalizes_header_names() {
    let parsed = parse_fingerprint_line("  mts_header  , header.Server  , contains , MTS Proxy , isp , medium  ")
        .expect("parsed");

    assert_eq!(parsed.name, "mts_header");
    assert_eq!(parsed.location, FingerprintLocation::Header("server".to_string()));
    assert_eq!(parsed.pattern_type, PatternType::Contains);
    assert_eq!(parsed.pattern, "MTS Proxy");
    assert_eq!(parsed.scope, "isp");
    assert_eq!(parsed.confidence, "medium");
}

#[test]
fn fingerprint_line_parser_rejects_empty_required_fields() {
    assert!(parse_fingerprint_line(",body,contains,blocked,isp,high").is_none());
    assert!(parse_fingerprint_line("name,body,contains,,isp,high").is_none());
    assert!(parse_fingerprint_line("name,body,contains,blocked,,high").is_none());
    assert!(parse_fingerprint_line("name,body,contains,blocked,isp,").is_none());
}

#[test]
fn fingerprint_line_parser_rejects_empty_header_name() {
    assert!(parse_fingerprint_line("name,header.,contains,blocked,isp,high").is_none());
    assert!(parse_fingerprint_line("name,header.   ,contains,blocked,isp,high").is_none());
}

#[test]
fn fingerprint_line_parser_rejects_invalid_location_and_pattern_type() {
    assert!(parse_fingerprint_line("name,footer.server,contains,blocked,isp,high").is_none());
    assert!(parse_fingerprint_line("name,body,suffix,blocked,isp,high").is_none());
}

#[test]
fn fingerprint_match_detects_body_and_header_patterns() {
    let fingerprints = load_blockpage_fingerprints();
    assert_eq!(
        match_blockpage_response(&[], b"<html>zapret-info.gov.ru</html>", &fingerprints,),
        Some("rkn_standard".to_string()),
    );
    assert_eq!(
        match_blockpage_response(&[("server".to_string(), "MTS Proxy/1.0".to_string())], b"ok", &fingerprints,),
        Some("mts_header".to_string()),
    );
}

#[test]
fn fingerprint_match_folds_unicode_case_for_cyrillic_patterns() {
    // to_ascii_lowercase leaves Cyrillic untouched, so sentence-case
    // blockpage text used to miss the lowercase CSV patterns.
    let fingerprints = load_blockpage_fingerprints();
    assert_eq!(
        match_blockpage_response(&[], "<html>Доступ Ограничен</html>".as_bytes(), &fingerprints,),
        Some("rkn_blocked_ru".to_string()),
    );
    assert_eq!(
        match_blockpage_response(&[], "<html>Решением Суда</html>".as_bytes(), &fingerprints,),
        Some("rkn_decision".to_string()),
    );
}

#[test]
fn http_block_detection_ignores_generic_redirects_and_429() {
    let redirect = b"HTTP/1.1 302 Found\r\nLocation: https://example.org/\r\n\r\n";
    let too_many = b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 10\r\n\r\n";
    let forbidden = b"HTTP/1.1 403 Forbidden\r\nServer: test\r\n\r\nPlease try again later";

    assert!(classify_http_response_block(redirect).is_none());
    assert!(classify_http_response_block(too_many).is_none());
    assert!(classify_http_response_block(forbidden).is_none());
}

#[test]
fn http_block_detection_classifies_fingerprint_redirects() {
    let response = b"HTTP/1.1 302 Found\r\nServer: MTS Proxy/1.0\r\nLocation: http://blocked.mts.ru/\r\n\r\n";
    let failure = classify_http_response_block(response).expect("redirect failure");

    assert_eq!(failure.class, FailureClass::Redirect);
    assert!(failure.evidence.tags.iter().any(|tag| tag == "provider=mts_header"));
}

#[test]
fn http_block_detection_classifies_blockpages() {
    let response = b"HTTP/1.1 403 Forbidden\r\nServer: test\r\n\r\n<html>zapret-info.gov.ru</html>";
    let failure = classify_http_response_block(response).expect("blockpage failure");

    assert_eq!(failure.class, FailureClass::HttpBlockpage);
    assert_eq!(failure.stage, FailureStage::HttpResponse);
    assert!(failure.evidence.tags.iter().any(|tag| tag == "provider=rkn_standard"));
}

#[test]
fn http_response_parser_rejects_header_without_colon() {
    let response = b"HTTP/1.1 200 OK\r\nServer: test\r\nBadHeader\r\n\r\nbody";

    assert!(parse_http_response(response).is_none());
    assert!(classify_http_response_block(response).is_none());
}

#[test]
fn http_response_parser_rejects_empty_header_name() {
    let response = b"HTTP/1.1 200 OK\r\n: value\r\n\r\nbody";

    assert!(parse_http_response(response).is_none());
    assert!(classify_http_response_block(response).is_none());
}

#[test]
fn http_response_parser_keeps_valid_headers() {
    let response = b"HTTP/1.1 302 Found\r\nServer: MTS Proxy/1.0\r\nLocation: http://blocked.mts.ru/\r\n\r\n";
    let parsed = parse_http_response(response).expect("parsed response");

    assert_eq!(parsed.status_code, 302);
    assert_eq!(
        parsed.headers.iter().find(|(name, _)| name == "server").map(|(_, value)| value.as_str()),
        Some("MTS Proxy/1.0")
    );
    assert_eq!(
        parsed.headers.iter().find(|(name, _)| name == "location").map(|(_, value)| value.as_str()),
        Some("http://blocked.mts.ru/")
    );
}

#[test]
fn http_response_parser_rejects_missing_headers_delimiter() {
    let response = b"HTTP/1.1 200 OK\r\nServer: test\r\nbody";

    assert!(parse_http_response(response).is_none());
}

#[test]
fn http_response_parser_rejects_non_utf8_headers() {
    let response = b"HTTP/1.1 200 OK\r\nServer: \xFF\xFE\r\n\r\nbody";

    assert!(parse_http_response(response).is_none());
}

#[test]
fn http_response_parser_rejects_non_http1_versions_and_bad_status_codes() {
    assert!(parse_http_response(b"HTTP/2 200 OK\r\nServer: test\r\n\r\nbody").is_none());
    assert!(parse_http_response(b"HTTP/1.1 20 OK\r\nServer: test\r\n\r\nbody").is_none());
    assert!(parse_http_response(b"HTTP/1.1 two OK\r\nServer: test\r\n\r\nbody").is_none());
}

#[test]
fn block_signal_mapping_prefers_tcp_retransmissions_for_timeout_threshold() {
    let failure = crate::ClassifiedFailure::new(
        FailureClass::SilentDrop,
        FailureStage::Connect,
        FailureAction::RetryWithMatchingGroup,
        "timed out",
    );

    let signal = block_signal_from_failure(&failure, Some(3)).expect("signal");
    assert_eq!(signal.signal, BlockSignal::TcpRetransmissions);
}

#[test]
fn block_signal_mapping_covers_tls_alert_and_quic_breakage() {
    let tls = crate::ClassifiedFailure::new(
        FailureClass::TlsAlert,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        "TLS alert",
    );
    let quic = crate::ClassifiedFailure::new(
        FailureClass::QuicBreakage,
        FailureStage::QuicProbe,
        FailureAction::DiagnosticsOnly,
        "quic timeout",
    );

    assert_eq!(block_signal_from_failure(&tls, None).expect("tls").signal, BlockSignal::TlsAlert,);
    assert_eq!(block_signal_from_failure(&quic, None).expect("quic").signal, BlockSignal::QuicBreakage,);
}
