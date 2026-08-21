use std::collections::HashMap;
use std::num::ParseIntError;

pub use ripdpi_failure_classifier::{BlockpageFingerprint, load_blockpage_fingerprints as load_fingerprints};

/// Failure modes when parsing an HTTP response head + body.
///
/// `Display` is kept byte-for-byte compatible with the previous
/// `Result<_, String>` contract so that error text surfaced at any
/// `String` boundary (JNI, diagnostics summaries) is unchanged.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum HttpParseError {
    /// The header block had no status line at all.
    #[error("missing_status_line")]
    MissingStatusLine,
    /// The status line had no status-code token.
    #[error("missing_status_code")]
    MissingStatusCode,
    /// The status-code token was not a valid integer.
    #[error("{0}")]
    InvalidStatusCode(String),
}

impl From<ParseIntError> for HttpParseError {
    fn from(err: ParseIntError) -> Self {
        HttpParseError::InvalidStatusCode(err.to_string())
    }
}

/// Failure modes when parsing a DNS response packet.
///
/// `Display` mirrors the historical `Result<_, String>` error tokens so the
/// observable error text at every boundary is preserved.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum DnsParseError {
    #[error("dns_response_too_short")]
    ResponseTooShort,
    #[error("dns_response_id_mismatch")]
    IdMismatch,
    #[error("dns_nxdomain")]
    NxDomain,
    #[error("dns_servfail")]
    ServFail,
    #[error("dns_refused")]
    Refused,
    #[error("dns_question_truncated")]
    QuestionTruncated,
    #[error("dns_answer_truncated")]
    AnswerTruncated,
    #[error("dns_rdata_truncated")]
    RdataTruncated,
    #[error("dns_name_truncated")]
    NameTruncated,
    #[error("dns_pointer_truncated")]
    PointerTruncated,
    #[error("dns_label_truncated")]
    LabelTruncated,
    #[error("dns_empty")]
    Empty,
}

#[derive(Clone, Debug)]
pub struct HttpResponse {
    pub status_code: u16,
    pub reason: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

pub fn parse_http_response(headers: &[u8], body: Vec<u8>) -> Result<HttpResponse, HttpParseError> {
    let text = String::from_utf8_lossy(headers);
    let mut lines = text.split("\r\n");
    let status_line = lines.next().ok_or(HttpParseError::MissingStatusLine)?;
    let mut status_parts = status_line.splitn(3, ' ');
    let _http_version = status_parts.next();
    let status_code = status_parts.next().ok_or(HttpParseError::MissingStatusCode)?.parse::<u16>()?;
    let reason = status_parts.next().unwrap_or_default().to_string();
    let mut parsed_headers = HashMap::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some((name, value)) = line.split_once(':') {
            parsed_headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
        }
    }
    Ok(HttpResponse { status_code, reason, headers: parsed_headers, body })
}

pub fn classify_http_response(response: &HttpResponse) -> String {
    if response.status_code == 200 && !body_has_blockpage_keywords(&response.body) {
        "http_ok".to_string()
    } else if response.status_code == 403 || response.status_code == 451 || body_has_blockpage_keywords(&response.body)
    {
        "http_blockpage".to_string()
    } else {
        format!("http_status_{}", response.status_code)
    }
}

pub fn body_has_blockpage_keywords(body: &[u8]) -> bool {
    if body.len() > 8192 {
        return false;
    }
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    ["blocked", "access denied", "forbidden", "restriction", "censorship"].iter().any(|needle| text.contains(needle))
}

pub fn match_blockpage(response: &HttpResponse, fingerprints: &[BlockpageFingerprint]) -> Option<String> {
    let headers = response.headers.iter().map(|(name, value)| (name.clone(), value.clone())).collect::<Vec<_>>();
    ripdpi_failure_classifier::match_blockpage_response(&headers, &response.body, fingerprints)
}

pub fn parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, DnsParseError> {
    if packet.len() < 12 {
        return Err(DnsParseError::ResponseTooShort);
    }
    let id = u16::from_be_bytes([packet[0], packet[1]]);
    if id != expected_id {
        return Err(DnsParseError::IdMismatch);
    }
    let rcode = packet[3] & 0x0F;
    if rcode == 3 {
        return Err(DnsParseError::NxDomain);
    }
    if rcode == 2 {
        return Err(DnsParseError::ServFail);
    }
    if rcode == 5 {
        return Err(DnsParseError::Refused);
    }
    let answer_count = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let question_count = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = skip_dns_name(packet, offset)?;
        offset += 4;
        if offset > packet.len() {
            return Err(DnsParseError::QuestionTruncated);
        }
    }
    let mut answers = Vec::new();
    for _ in 0..answer_count {
        offset = skip_dns_name(packet, offset)?;
        if offset + 10 > packet.len() {
            return Err(DnsParseError::AnswerTruncated);
        }
        let record_type = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
        let data_len = u16::from_be_bytes([packet[offset + 8], packet[offset + 9]]) as usize;
        offset += 10;
        if offset + data_len > packet.len() {
            return Err(DnsParseError::RdataTruncated);
        }
        if record_type == 1 && data_len == 4 {
            answers.push(
                std::net::Ipv4Addr::new(packet[offset], packet[offset + 1], packet[offset + 2], packet[offset + 3])
                    .to_string(),
            );
        }
        offset += data_len;
    }
    if answers.is_empty() { Err(DnsParseError::Empty) } else { Ok(answers) }
}

fn skip_dns_name(packet: &[u8], mut offset: usize) -> Result<usize, DnsParseError> {
    loop {
        let Some(length) = packet.get(offset).copied() else {
            return Err(DnsParseError::NameTruncated);
        };
        if length & 0b1100_0000 == 0b1100_0000 {
            if offset + 1 >= packet.len() {
                return Err(DnsParseError::PointerTruncated);
            }
            return Ok(offset + 2);
        }
        offset += 1;
        if length == 0 {
            return Ok(offset);
        }
        offset += length as usize;
        if offset > packet.len() {
            return Err(DnsParseError::LabelTruncated);
        }
    }
}

pub fn fuzz_parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, DnsParseError> {
    parse_dns_response(packet, expected_id)
}

pub fn fuzz_parse_http_response(headers: &[u8], body: &[u8]) -> Result<(), HttpParseError> {
    let response = parse_http_response(headers, body.to_vec())?;
    let _ = classify_http_response(&response);
    Ok(())
}
