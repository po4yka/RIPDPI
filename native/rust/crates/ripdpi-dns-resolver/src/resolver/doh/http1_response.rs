use http_body_util::BodyExt;
use hyper::{Response, body::Incoming};
use reqwest::header::{CONTENT_LENGTH, HeaderMap, TRANSFER_ENCODING};

use super::MAX_DOH_RESPONSE_BYTES;
use crate::types::EncryptedDnsError;

/// # Cancel safety:
/// cancel-safe: the response and collected bytes are owned and dropped on cancellation.
pub(super) async fn read_doh_response(response: Response<Incoming>) -> Result<Vec<u8>, EncryptedDnsError> {
    if !response.status().is_success() {
        return Err(EncryptedDnsError::HttpStatus(response.status()));
    }
    validate_response_headers(response.headers())?;
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
