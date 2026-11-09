use http_body_util::BodyExt;
use hyper::{Response, body::Incoming};
use reqwest::header::{CONTENT_LENGTH, CONTENT_TYPE, HeaderMap, TRANSFER_ENCODING};

use super::MAX_DOH_RESPONSE_BYTES;
use crate::types::EncryptedDnsError;

/// # Cancel safety:
/// cancel-safe: the response and collected bytes are owned and dropped on cancellation.
pub(super) async fn read_doh_response(
    response: Response<Incoming>,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    if !response.status().is_success() {
        return Err(EncryptedDnsError::HttpStatus(response.status()));
    }
    validate_response_headers(response.headers())?;
    validate_response_content_type(response.headers(), accept)?;
    let mut incoming = response.into_body();
    let mut body = Vec::new();
    while let Some(frame) = incoming.frame().await {
        let frame = frame.map_err(|error| EncryptedDnsError::Request(error.to_string()))?;
        if let Ok(data) = frame.into_data() {
            if data.len() > MAX_DOH_RESPONSE_BYTES - body.len() {
                return Err(EncryptedDnsError::Request("DoH response body exceeds maximum size".into()));
            }
            body.extend_from_slice(&data);
        }
    }
    Ok(body)
}

/// A present Content-Type must carry the expected media type (RFC 8484 section 4.2);
/// parameters are ignored and a missing header is allowed.
pub(super) fn validate_response_content_type(headers: &HeaderMap, expected: &str) -> Result<(), EncryptedDnsError> {
    let Some(value) = headers.get(CONTENT_TYPE) else {
        return Ok(());
    };
    let matches = value
        .to_str()
        .is_ok_and(|value| value.split(';').next().unwrap_or_default().trim().eq_ignore_ascii_case(expected.trim()));
    if matches {
        Ok(())
    } else {
        Err(EncryptedDnsError::Request(format!(
            "DoH response has unexpected Content-Type {:?}, expected {expected}",
            String::from_utf8_lossy(value.as_bytes())
        )))
    }
}

pub(super) fn validate_response_headers(headers: &HeaderMap) -> Result<(), EncryptedDnsError> {
    if headers.contains_key(TRANSFER_ENCODING) {
        if headers.contains_key(CONTENT_LENGTH) {
            return Err(EncryptedDnsError::Request("DoH response has conflicting framing headers".into()));
        }
        let mut encodings = headers.get_all(TRANSFER_ENCODING).iter();
        if !encodings.next().is_some_and(|value| value.as_bytes().eq_ignore_ascii_case(b"chunked"))
            || encodings.next().is_some()
        {
            return Err(EncryptedDnsError::Request("DoH response has unsupported transfer encoding".into()));
        }
    }
    if let Some(length) = headers.get(CONTENT_LENGTH) {
        let length = length
            .to_str()
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .ok_or_else(|| EncryptedDnsError::Request("DoH response has invalid Content-Length".into()))?;
        if length > MAX_DOH_RESPONSE_BYTES as u64 {
            return Err(EncryptedDnsError::Request("DoH response Content-Length exceeds maximum size".into()));
        }
    }
    Ok(())
}
