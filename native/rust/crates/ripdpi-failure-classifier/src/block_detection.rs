use std::sync::LazyLock;

use serde::{Deserialize, Serialize};

use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockSignal {
    HttpBlockpage,
    HttpRedirect,
    TlsAlert,
    SilentDrop,
    TcpReset,
    ConnectionFreeze,
    QuicBreakage,
    TcpRetransmissions,
}

impl BlockSignal {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::HttpBlockpage => "http_blockpage",
            Self::HttpRedirect => "http_redirect",
            Self::TlsAlert => "tls_alert",
            Self::SilentDrop => "silent_drop",
            Self::TcpReset => "tcp_reset",
            Self::ConnectionFreeze => "connection_freeze",
            Self::QuicBreakage => "quic_breakage",
            Self::TcpRetransmissions => "tcp_retransmissions",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlockSignalObservation {
    pub signal: BlockSignal,
    pub provider: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlockpageFingerprint {
    pub name: String,
    pub location: FingerprintLocation,
    pub pattern_type: PatternType,
    pub pattern: String,
    pub scope: String,
    pub confidence: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FingerprintLocation {
    Body,
    Header(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PatternType {
    Full,
    Prefix,
    Contains,
}

struct ParsedHttpResponse {
    status_code: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

static BUNDLED_BLOCKPAGE_FINGERPRINTS: LazyLock<Vec<BlockpageFingerprint>> =
    LazyLock::new(|| load_blockpage_fingerprints_from_csv(include_str!("../blockpage_fingerprints.csv")));

pub fn bundled_blockpage_fingerprints() -> &'static [BlockpageFingerprint] {
    BUNDLED_BLOCKPAGE_FINGERPRINTS.as_slice()
}

pub fn load_blockpage_fingerprints() -> Vec<BlockpageFingerprint> {
    bundled_blockpage_fingerprints().to_vec()
}

pub fn load_blockpage_fingerprints_from_csv(csv: &str) -> Vec<BlockpageFingerprint> {
    csv.lines().skip(1).filter(|line| !line.trim().is_empty()).filter_map(parse_fingerprint_line).collect()
}

pub fn match_blockpage_response(
    headers: &[(String, String)],
    body: &[u8],
    fingerprints: &[BlockpageFingerprint],
) -> Option<String> {
    fingerprints.iter().find_map(|fingerprint| {
        let haystack = match &fingerprint.location {
            FingerprintLocation::Body => String::from_utf8_lossy(body).to_ascii_lowercase(),
            FingerprintLocation::Header(name) => headers
                .iter()
                .find(|(header, _)| header.eq_ignore_ascii_case(name))
                .map(|(_, value)| value.to_ascii_lowercase())
                .unwrap_or_default(),
        };
        let pattern = fingerprint.pattern.to_ascii_lowercase();
        let matched = match fingerprint.pattern_type {
            PatternType::Full => haystack == pattern,
            PatternType::Prefix => haystack.starts_with(&pattern),
            PatternType::Contains => haystack.contains(&pattern),
        };
        matched.then(|| fingerprint.name.clone())
    })
}

/// Generic body keywords that indicate a blockpage when no CSV fingerprint
/// matches.  Checked case-insensitively.
const BLOCKPAGE_KEYWORDS: &[&str] = &["blocked", "access denied", "forbidden", "restriction", "censorship"];

pub fn classify_http_response_block(response: &[u8]) -> Option<ClassifiedFailure> {
    let response = parse_http_response(response)?;
    if response.status_code == 429 {
        return None;
    }

    let provider = match_blockpage_response(&response.headers, &response.body, bundled_blockpage_fingerprints());
    let is_redirect = (300..400).contains(&response.status_code);

    if is_redirect {
        if let Some(provider) = &provider {
            return Some(
                ClassifiedFailure::new(
                    FailureClass::Redirect,
                    FailureStage::FirstResponse,
                    FailureAction::RetryWithMatchingGroup,
                    format!("HTTP redirect matched known block fingerprint: {provider}"),
                )
                .with_tag("status", response.status_code.to_string())
                .with_tag("provider", provider.clone()),
            );
        }
        let url_provider = match_redirect_block(&response.headers)?;
        return Some(
            ClassifiedFailure::new(
                FailureClass::HttpBlockpage,
                FailureStage::FirstResponse,
                FailureAction::RetryWithMatchingGroup,
                format!("HTTP redirect to block URL: {url_provider}"),
            )
            .with_tag("status", response.status_code.to_string())
            .with_tag("provider", url_provider),
        );
    }

    // Status 451 (Unavailable For Legal Reasons) is always a blockpage.
    if response.status_code == 451 {
        let mut failure = ClassifiedFailure::new(
            FailureClass::HttpBlockpage,
            FailureStage::HttpResponse,
            FailureAction::RetryWithMatchingGroup,
            "HTTP 451 Unavailable For Legal Reasons".to_string(),
        )
        .with_tag("status", "451".to_string());
        if let Some(provider) = provider {
            failure = failure.with_tag("provider", provider);
        }
        return Some(failure);
    }

    // Try CSV fingerprint first, then fall back to generic keyword matching.
    let provider = provider.filter(|value| !value.trim().is_empty()).or_else(|| match_body_keyword(&response.body));
    provider.as_ref()?;

    let mut failure = ClassifiedFailure::new(
        FailureClass::HttpBlockpage,
        FailureStage::HttpResponse,
        FailureAction::RetryWithMatchingGroup,
        format!("HTTP blockpage with status {}", response.status_code),
    )
    .with_tag("status", response.status_code.to_string());
    if let Some(provider) = provider {
        failure = failure.with_tag("provider", provider);
    }
    Some(failure)
}

/// Check if the redirect Location header points to a known block domain.
fn match_redirect_block(headers: &[(String, String)]) -> Option<String> {
    let location = headers
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case("location"))
        .map(|(_, value)| value.to_ascii_lowercase())?;
    if location.contains("block") || location.contains("filter") || location.contains("warning") {
        return Some("redirect_block_url".to_string());
    }
    None
}

/// Match generic blockpage keywords in the response body (case-insensitive).
fn match_body_keyword(body: &[u8]) -> Option<String> {
    let body_lower = String::from_utf8_lossy(body).to_ascii_lowercase();
    BLOCKPAGE_KEYWORDS
        .iter()
        .find(|keyword| body_lower.contains(**keyword))
        .map(|keyword| format!("keyword_{}", keyword.replace(' ', "_")))
}

pub fn block_signal_from_failure(
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) -> Option<BlockSignalObservation> {
    let provider = failure_tag(failure, "provider").map(ToOwned::to_owned);
    let signal = match failure.class {
        FailureClass::HttpBlockpage => BlockSignal::HttpBlockpage,
        FailureClass::Redirect => BlockSignal::HttpRedirect,
        FailureClass::TlsAlert => BlockSignal::TlsAlert,
        FailureClass::SilentDrop => {
            if tcp_total_retransmissions.unwrap_or_default() >= 3 {
                BlockSignal::TcpRetransmissions
            } else {
                BlockSignal::SilentDrop
            }
        }
        FailureClass::TcpReset => BlockSignal::TcpReset,
        FailureClass::ConnectionFreeze => BlockSignal::ConnectionFreeze,
        FailureClass::QuicBreakage => BlockSignal::QuicBreakage,
        _ => return None,
    };
    Some(BlockSignalObservation { signal, provider })
}

fn parse_fingerprint_line(line: &str) -> Option<BlockpageFingerprint> {
    let parts: Vec<&str> = line.splitn(6, ',').collect();
    if parts.len() < 6 {
        return None;
    }
    let location = if parts[1] == "body" {
        FingerprintLocation::Body
    } else if let Some(header) = parts[1].strip_prefix("header.") {
        FingerprintLocation::Header(header.to_ascii_lowercase())
    } else {
        return None;
    };
    let pattern_type = match parts[2] {
        "full" => PatternType::Full,
        "prefix" => PatternType::Prefix,
        "contains" => PatternType::Contains,
        _ => return None,
    };
    Some(BlockpageFingerprint {
        name: parts[0].to_string(),
        location,
        pattern_type,
        pattern: parts[3].to_string(),
        scope: parts[4].to_string(),
        confidence: parts[5].to_string(),
    })
}

fn parse_http_response(response: &[u8]) -> Option<ParsedHttpResponse> {
    let headers_end = find_headers_end(response)?;
    let headers_text = std::str::from_utf8(&response[..headers_end]).ok()?;
    let mut lines = headers_text.split("\r\n");
    let status_line = lines.next()?;
    if !status_line.starts_with("HTTP/1.") {
        return None;
    }
    let mut parts = status_line.splitn(3, ' ');
    let _ = parts.next()?;
    let status_code = parts.next()?.parse::<u16>().ok()?;
    let headers = lines
        .filter_map(|line| {
            let (name, value) = line.split_once(':')?;
            Some((name.trim().to_ascii_lowercase(), value.trim().to_string()))
        })
        .collect();
    Some(ParsedHttpResponse { status_code, headers, body: response[headers_end + 4..].to_vec() })
}

fn find_headers_end(response: &[u8]) -> Option<usize> {
    response.windows(4).position(|window| window == b"\r\n\r\n")
}

fn failure_tag<'a>(failure: &'a ClassifiedFailure, key: &str) -> Option<&'a str> {
    failure.evidence.tags.iter().find_map(|tag| tag.strip_prefix(key).and_then(|value| value.strip_prefix('=')))
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::{FailureAction, FailureClass, FailureStage};

    #[test]
    fn bundled_fingerprints_parse_provider_entries() {
        let fingerprints = load_blockpage_fingerprints();
        assert!(!fingerprints.is_empty());
        assert!(fingerprints.iter().any(|value| value.name == "rkn_standard"));
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
    fn block_signal_mapping_prefers_tcp_retransmissions_for_timeout_threshold() {
        let failure = ClassifiedFailure::new(
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
        let tls = ClassifiedFailure::new(
            FailureClass::TlsAlert,
            FailureStage::FirstResponse,
            FailureAction::RetryWithMatchingGroup,
            "TLS alert",
        );
        let quic = ClassifiedFailure::new(
            FailureClass::QuicBreakage,
            FailureStage::QuicProbe,
            FailureAction::DiagnosticsOnly,
            "quic timeout",
        );

        assert_eq!(block_signal_from_failure(&tls, None).expect("tls").signal, BlockSignal::TlsAlert,);
        assert_eq!(block_signal_from_failure(&quic, None).expect("quic").signal, BlockSignal::QuicBreakage,);
    }
}
