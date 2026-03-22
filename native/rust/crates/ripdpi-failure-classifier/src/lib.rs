#![forbid(unsafe_code)]

use std::io;
use std::net::IpAddr;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureClass {
    Unknown,
    DnsTampering,
    TcpReset,
    SilentDrop,
    TlsAlert,
    HttpBlockpage,
    QuicBreakage,
    Redirect,
    TlsHandshakeFailure,
    ConnectFailure,
    StrategyExecutionFailure,
}

impl FailureClass {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::DnsTampering => "dns_tampering",
            Self::TcpReset => "tcp_reset",
            Self::SilentDrop => "silent_drop",
            Self::TlsAlert => "tls_alert",
            Self::HttpBlockpage => "http_blockpage",
            Self::QuicBreakage => "quic_breakage",
            Self::Redirect => "redirect",
            Self::TlsHandshakeFailure => "tls_handshake_failure",
            Self::ConnectFailure => "connect_failure",
            Self::StrategyExecutionFailure => "strategy_execution_failure",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureStage {
    Dns,
    Connect,
    FirstWrite,
    FirstResponse,
    TlsHandshake,
    HttpResponse,
    QuicProbe,
    Diagnostic,
}

impl FailureStage {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Dns => "dns",
            Self::Connect => "connect",
            Self::FirstWrite => "first_write",
            Self::FirstResponse => "first_response",
            Self::TlsHandshake => "tls_handshake",
            Self::HttpResponse => "http_response",
            Self::QuicProbe => "quic_probe",
            Self::Diagnostic => "diagnostic",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureAction {
    None,
    RetryWithMatchingGroup,
    ResolverOverrideRecommended,
    DiagnosticsOnly,
    SurfaceOnly,
}

impl FailureAction {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::RetryWithMatchingGroup => "retry_with_matching_group",
            Self::ResolverOverrideRecommended => "resolver_override_recommended",
            Self::DiagnosticsOnly => "diagnostics_only",
            Self::SurfaceOnly => "surface_only",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FailureEvidence {
    pub summary: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,
}

impl FailureEvidence {
    pub fn new(summary: impl Into<String>) -> Self {
        Self { summary: summary.into(), tags: Vec::new() }
    }

    pub fn with_tag(mut self, key: &str, value: impl Into<String>) -> Self {
        self.tags.push(format!("{key}={}", value.into()));
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassifiedFailure {
    pub class: FailureClass,
    pub stage: FailureStage,
    pub action: FailureAction,
    #[serde(default)]
    pub evidence: FailureEvidence,
}

impl ClassifiedFailure {
    pub fn new(class: FailureClass, stage: FailureStage, action: FailureAction, summary: impl Into<String>) -> Self {
        Self { class, stage, action, evidence: FailureEvidence::new(summary) }
    }

    pub fn with_tag(self, key: &str, value: impl Into<String>) -> Self {
        Self { evidence: self.evidence.with_tag(key, value), ..self }
    }
}

pub fn classify_transport_error(stage: FailureStage, error: &io::Error) -> ClassifiedFailure {
    let kind = error.kind();
    match kind {
        io::ErrorKind::ConnectionReset
        | io::ErrorKind::ConnectionAborted
        | io::ErrorKind::BrokenPipe
        | io::ErrorKind::UnexpectedEof => ClassifiedFailure::new(
            FailureClass::TcpReset,
            stage,
            FailureAction::RetryWithMatchingGroup,
            error.to_string(),
        )
        .with_tag("kind", format!("{kind:?}")),
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            error.to_string(),
        )
        .with_tag("kind", format!("{kind:?}")),
        io::ErrorKind::ConnectionRefused
        | io::ErrorKind::HostUnreachable
        | io::ErrorKind::NetworkUnreachable
        | io::ErrorKind::NotConnected
        | io::ErrorKind::AddrNotAvailable => ClassifiedFailure::new(
            FailureClass::ConnectFailure,
            stage,
            FailureAction::RetryWithMatchingGroup,
            error.to_string(),
        )
        .with_tag("kind", format!("{kind:?}")),
        _ => ClassifiedFailure::new(FailureClass::Unknown, stage, FailureAction::SurfaceOnly, error.to_string())
            .with_tag("kind", format!("{kind:?}")),
    }
}

pub fn classify_strategy_execution_failure(
    stage: FailureStage,
    action: &str,
    kind: io::ErrorKind,
    errno: Option<i32>,
    summary: impl Into<String>,
) -> Option<ClassifiedFailure> {
    if stage != FailureStage::FirstWrite {
        return None;
    }
    if !is_strategy_execution_kind(kind) && !errno.is_some_and(is_strategy_execution_errno) {
        return None;
    }
    Some(
        ClassifiedFailure::new(
            FailureClass::StrategyExecutionFailure,
            stage,
            FailureAction::RetryWithMatchingGroup,
            summary,
        )
        .with_tag("action", action.to_string())
        .with_tag("kind", format!("{kind:?}"))
        .with_tag("errno", errno.map(|value| value.to_string()).unwrap_or_else(|| "none".to_string())),
    )
}

pub fn classify_tls_alert(response: &[u8]) -> Option<ClassifiedFailure> {
    if !looks_like_tls_alert(response) {
        return None;
    }
    let alert_desc = tls_alert_description(response.get(6).copied());
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

pub fn classify_http_blockpage(response: &[u8]) -> Option<ClassifiedFailure> {
    let response = parse_http_response(response)?;
    let status_code = response.status_code;
    let body_text = String::from_utf8_lossy(&response.body).to_ascii_lowercase();
    let is_blockpage =
        status_code == 403 || status_code == 451 || status_code == 302 || body_has_blockpage_keywords(&body_text);
    if !is_blockpage {
        return None;
    }

    Some(
        ClassifiedFailure::new(
            FailureClass::HttpBlockpage,
            FailureStage::HttpResponse,
            FailureAction::RetryWithMatchingGroup,
            format!("HTTP blockpage with status {status_code}"),
        )
        .with_tag("status", status_code.to_string()),
    )
}

pub fn classify_quic_probe(outcome: &str, error: Option<&str>) -> Option<ClassifiedFailure> {
    if matches!(outcome, "quic_initial_response" | "quic_response") {
        return None;
    }
    let summary = error.filter(|value| !value.is_empty()).unwrap_or(outcome);
    Some(
        ClassifiedFailure::new(
            FailureClass::QuicBreakage,
            FailureStage::QuicProbe,
            FailureAction::DiagnosticsOnly,
            format!("QUIC probe failed: {summary}"),
        )
        .with_tag("outcome", outcome.to_string()),
    )
}

pub fn confirm_dns_tampering(
    host: &str,
    target_ip: IpAddr,
    encrypted_answers: &[IpAddr],
    source_label: &str,
) -> Option<ClassifiedFailure> {
    if encrypted_answers.is_empty() || encrypted_answers.contains(&target_ip) {
        return None;
    }
    let expected = encrypted_answers.iter().map(ToString::to_string).collect::<Vec<_>>().join("|");
    Some(
        ClassifiedFailure::new(
            FailureClass::DnsTampering,
            FailureStage::Dns,
            FailureAction::ResolverOverrideRecommended,
            format!("Encrypted DNS answers for {host} do not include {target_ip}"),
        )
        .with_tag("host", host.to_string())
        .with_tag("targetIp", target_ip.to_string())
        .with_tag("encryptedAnswers", expected)
        .with_tag("resolver", source_label.to_string()),
    )
}

fn looks_like_tls_alert(response: &[u8]) -> bool {
    response.len() >= 7 && response[0] == 0x15 && response[1] == 0x03 && (0x00..=0x04).contains(&response[2])
}

fn tls_alert_description(alert: Option<u8>) -> &'static str {
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

struct ParsedHttpResponse {
    status_code: u16,
    body: Vec<u8>,
}

fn parse_http_response(response: &[u8]) -> Option<ParsedHttpResponse> {
    let headers_end = find_headers_end(response)?;
    let headers = std::str::from_utf8(&response[..headers_end]).ok()?;
    let mut lines = headers.split("\r\n");
    let status_line = lines.next()?;
    if !status_line.starts_with("HTTP/1.") {
        return None;
    }
    let mut parts = status_line.splitn(3, ' ');
    let _ = parts.next()?;
    let status_code = parts.next()?.parse::<u16>().ok()?;
    Some(ParsedHttpResponse { status_code, body: response[headers_end + 4..].to_vec() })
}

fn find_headers_end(response: &[u8]) -> Option<usize> {
    response.windows(4).position(|window| window == b"\r\n\r\n")
}

fn body_has_blockpage_keywords(body: &str) -> bool {
    ["blocked", "access denied", "forbidden", "restriction", "censorship"].iter().any(|needle| body.contains(needle))
}

fn is_strategy_execution_errno(errno: i32) -> bool {
    errno == libc::EINVAL
        || errno == libc::ENOPROTOOPT
        || errno == libc::EOPNOTSUPP
        || errno == libc::ENOTSUP
        || errno == libc::EPERM
        || errno == libc::EACCES
}

fn is_strategy_execution_kind(kind: io::ErrorKind) -> bool {
    kind == io::ErrorKind::Unsupported
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_errors_distinguish_reset_and_timeout() {
        let reset = io::Error::new(io::ErrorKind::ConnectionReset, "reset by peer");
        let timeout = io::Error::new(io::ErrorKind::TimedOut, "timed out");

        let reset_failure = classify_transport_error(FailureStage::Connect, &reset);
        let timeout_failure = classify_transport_error(FailureStage::Connect, &timeout);

        assert_eq!(reset_failure.class, FailureClass::TcpReset);
        assert_eq!(timeout_failure.class, FailureClass::SilentDrop);
    }

    #[test]
    fn tls_alerts_are_distinguished_from_generic_handshake_failures() {
        let alert = [0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];
        let classified = classify_tls_alert(&alert).expect("tls alert");

        assert_eq!(classified.class, FailureClass::TlsAlert);

        let generic = classify_tls_handshake_failure("server hello mismatch");
        assert_eq!(generic.class, FailureClass::TlsHandshakeFailure);
    }

    #[test]
    fn detects_http_blockpages_from_status_and_body() {
        let response = b"HTTP/1.1 403 Forbidden\r\nServer: test\r\n\r\nAccess denied by policy";
        let classified = classify_http_blockpage(response).expect("blockpage");
        assert_eq!(classified.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn confirms_dns_tampering_when_target_ip_is_outside_answer_set() {
        let classified = confirm_dns_tampering(
            "example.org",
            "203.0.113.9".parse().expect("target"),
            &["198.51.100.10".parse().expect("answer"), "198.51.100.11".parse().expect("answer")],
            "cloudflare",
        )
        .expect("dns tampering");

        assert_eq!(classified.class, FailureClass::DnsTampering);
        assert_eq!(classified.action, FailureAction::ResolverOverrideRecommended);
    }

    #[test]
    fn quic_breakage_only_flags_failed_outcomes() {
        assert!(classify_quic_probe("quic_response", None).is_none());

        let classified = classify_quic_probe("quic_empty", Some("no datagram response")).expect("quic breakage");
        assert_eq!(classified.class, FailureClass::QuicBreakage);
        assert_eq!(classified.action, FailureAction::DiagnosticsOnly);
    }

    // ── Transport error classification completeness ──

    #[test]
    fn transport_errors_classify_connect_failures() {
        let kinds = [
            io::ErrorKind::ConnectionRefused,
            io::ErrorKind::HostUnreachable,
            io::ErrorKind::NetworkUnreachable,
            io::ErrorKind::NotConnected,
            io::ErrorKind::AddrNotAvailable,
        ];
        for kind in kinds {
            let err = io::Error::new(kind, "test");
            let f = classify_transport_error(FailureStage::Connect, &err);
            assert_eq!(f.class, FailureClass::ConnectFailure, "expected ConnectFailure for {kind:?}");
            assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
        }
    }

    #[test]
    fn transport_errors_classify_unknown_kinds() {
        let err = io::Error::new(io::ErrorKind::PermissionDenied, "denied");
        let f = classify_transport_error(FailureStage::Connect, &err);
        assert_eq!(f.class, FailureClass::Unknown);
        assert_eq!(f.action, FailureAction::SurfaceOnly);
    }

    #[test]
    fn strategy_execution_failures_retry_only_for_first_write_capability_errors() {
        let failure = classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "set_ttl",
            io::ErrorKind::InvalidInput,
            Some(libc::EINVAL),
            "desync action=set_ttl: Invalid argument (os error 22)",
        )
        .expect("first-write EINVAL should retry");

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.summary.contains("desync action=set_ttl"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "action=set_ttl"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "kind=InvalidInput"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == &format!("errno={}", libc::EINVAL)));
    }

    #[test]
    fn strategy_execution_failures_retry_for_unsupported_desync_actions() {
        let failure = classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "await_writable",
            io::ErrorKind::Unsupported,
            None,
            "desync action=await_writable: only supported on Linux/Android",
        )
        .expect("unsupported first-write action should retry");

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.tags.iter().any(|tag| tag == "action=await_writable"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "kind=Unsupported"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "errno=none"));
    }

    #[test]
    fn strategy_execution_failures_ignore_other_stages_and_non_capability_errors() {
        assert!(classify_strategy_execution_failure(
            FailureStage::Connect,
            "set_ttl",
            io::ErrorKind::InvalidInput,
            Some(libc::EINVAL),
            "desync action=set_ttl: Invalid argument (os error 22)",
        )
        .is_none());
        assert!(classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "set_ttl",
            io::ErrorKind::ConnectionReset,
            Some(libc::ECONNRESET),
            "desync action=set_ttl: Connection reset by peer (os error 54)",
        )
        .is_none());
        assert!(classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "set_ttl",
            io::ErrorKind::ConnectionReset,
            None,
            "desync action=set_ttl: Connection reset by peer",
        )
        .is_none());
    }

    #[test]
    fn invalid_input_transport_errors_remain_unknown_outside_desync_strategy_path() {
        for stage in [FailureStage::Connect, FailureStage::FirstResponse] {
            let err = io::Error::from_raw_os_error(libc::EINVAL);
            let failure = classify_transport_error(stage, &err);
            assert_eq!(failure.class, FailureClass::Unknown);
            assert_eq!(failure.action, FailureAction::SurfaceOnly);
        }
    }

    // ── TLS alert parsing edge cases ──

    #[test]
    fn tls_alert_returns_none_for_short_input() {
        assert!(classify_tls_alert(&[0x15, 0x03, 0x03, 0x00, 0x02, 0x02]).is_none());
        assert!(classify_tls_alert(&[]).is_none());
    }

    #[test]
    fn tls_alert_returns_none_for_wrong_record_type() {
        // 0x16 is Handshake, not Alert (0x15)
        let record = [0x16, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];
        assert!(classify_tls_alert(&record).is_none());
    }

    #[test]
    fn tls_alert_returns_none_for_invalid_tls_version() {
        // Second byte != 0x03
        let bad_major = [0x15, 0x02, 0x03, 0x00, 0x02, 0x02, 0x28];
        assert!(classify_tls_alert(&bad_major).is_none());

        // Third byte > 0x04
        let bad_minor = [0x15, 0x03, 0x05, 0x00, 0x02, 0x02, 0x28];
        assert!(classify_tls_alert(&bad_minor).is_none());
    }

    #[test]
    fn tls_alert_identifies_known_alert_codes() {
        let cases: &[(u8, &str)] = &[
            (0, "close_notify"),
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

    // ── HTTP blockpage edge cases ──

    #[test]
    fn http_blockpage_detects_status_451() {
        let response = b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nServer: test\r\n\r\nblocked";
        assert!(classify_http_blockpage(response).is_some());
    }

    #[test]
    fn http_blockpage_detects_redirect_302() {
        let response = b"HTTP/1.1 302 Found\r\nLocation: http://block.isp.net\r\n\r\n";
        assert!(classify_http_blockpage(response).is_some());
    }

    #[test]
    fn http_blockpage_detects_body_keywords_on_200() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nThis page is subject to censorship.";
        let f = classify_http_blockpage(response).expect("blockpage from keyword");
        assert_eq!(f.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn http_blockpage_returns_none_for_clean_200() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nHello, world!";
        assert!(classify_http_blockpage(response).is_none());
    }

    #[test]
    fn http_blockpage_returns_none_for_missing_headers_end() {
        let response = b"HTTP/1.1 403 Forbidden\r\nServer: test";
        assert!(classify_http_blockpage(response).is_none());
    }

    #[test]
    fn http_blockpage_returns_none_for_non_http() {
        let response = b"\x00\x01\x02random binary data";
        assert!(classify_http_blockpage(response).is_none());
    }

    // ── DNS tampering edge cases ──

    #[test]
    fn dns_tampering_returns_none_for_empty_answers() {
        assert!(confirm_dns_tampering("example.org", "1.2.3.4".parse().unwrap(), &[], "cloudflare").is_none());
    }

    #[test]
    fn dns_tampering_returns_none_when_target_in_answers() {
        let target: IpAddr = "198.51.100.10".parse().unwrap();
        assert!(confirm_dns_tampering("example.org", target, &[target], "cloudflare").is_none());
    }

    // ── classify_redirect_failure ──

    #[test]
    fn redirect_failure_produces_correct_class_and_stage() {
        let f = classify_redirect_failure("redirected to block page");
        assert_eq!(f.class, FailureClass::Redirect);
        assert_eq!(f.stage, FailureStage::FirstResponse);
        assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
    }

    // ── QUIC probe with initial_response success ──

    #[test]
    fn quic_probe_returns_none_for_initial_response() {
        assert!(classify_quic_probe("quic_initial_response", None).is_none());
    }

    // ── FailureEvidence tag building ──

    #[test]
    fn evidence_tags_format_key_value_pairs() {
        let e = FailureEvidence::new("test").with_tag("foo", "bar").with_tag("baz", "42");
        assert_eq!(e.tags, vec!["foo=bar", "baz=42"]);
    }
}
