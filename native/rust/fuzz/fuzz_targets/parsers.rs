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

pub fn fuzz_parse_http_response(headers: &[u8], body: &[u8]) -> Result<(), HttpParseError> {
    let response = parse_http_response(headers, body.to_vec())?;
    let _ = classify_http_response(&response);
    Ok(())
}
