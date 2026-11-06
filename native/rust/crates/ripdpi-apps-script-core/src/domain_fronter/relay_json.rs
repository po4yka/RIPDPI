use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use serde::Deserialize;
use serde_json::Value;

use super::{FronterError, RelayResponse};
use bytes::Bytes;
use http_body_util::Full;

#[derive(Default, Deserialize)]
struct RelayEnvelope {
    #[serde(default, rename = "s")]
    status: Option<u16>,
    #[serde(default, rename = "h")]
    headers: Option<serde_json::Map<String, Value>>,
    #[serde(default, rename = "b")]
    body: Option<String>,
    #[serde(default, rename = "e")]
    error: Option<String>,
}

pub(super) fn parse(body: &[u8]) -> Result<RelayResponse, FronterError> {
    let text = std::str::from_utf8(body)
        .map_err(|_| FronterError::BadResponse("Apps Script payload is not utf-8".to_string()))?;
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err(FronterError::BadResponse("Apps Script payload is empty".to_string()));
    }
    let response = match serde_json::from_str::<RelayEnvelope>(trimmed) {
        Ok(response) => response,
        Err(_) => {
            let start = trimmed
                .find('{')
                .ok_or_else(|| FronterError::BadResponse("Apps Script payload does not contain JSON".to_string()))?;
            let end = trimmed
                .rfind('}')
                .ok_or_else(|| FronterError::BadResponse("Apps Script payload does not terminate JSON".to_string()))?;
            serde_json::from_str(&trimmed[start..=end])?
        }
    };
    if let Some(error) = response.error {
        return Err(FronterError::Relay(error));
    }

    let status = response.status.unwrap_or(200);
    let body = response
        .body
        .map(|value| BASE64_STANDARD.decode(value))
        .transpose()
        .map_err(|error| FronterError::BadResponse(format!("invalid body base64: {error}")))?
        .unwrap_or_default();

    let mut output = ::http::Response::new(Full::new(Bytes::from(body)));
    *output.status_mut() =
        ::http::StatusCode::from_u16(status).map_err(|error| FronterError::BadResponse(error.to_string()))?;
    if output.status().is_informational() {
        return Err(FronterError::BadResponse("relay returned informational status".into()));
    }
    if let Some(headers) = response.headers {
        let connection_tokens = connection_tokens(&headers);
        for (key, value) in headers {
            let lower = key.to_ascii_lowercase();
            if is_skipped_response_header(&lower) || connection_tokens.contains(&lower) {
                continue;
            }
            for value in header_values_from_json(&value) {
                let name = ::http::header::HeaderName::from_bytes(key.as_bytes())
                    .map_err(|error| FronterError::BadResponse(error.to_string()))?;
                let value = ::http::HeaderValue::from_str(&value)
                    .map_err(|error| FronterError::BadResponse(error.to_string()))?;
                output.headers_mut().append(name, value);
            }
        }
    }
    Ok(output)
}

/// Response fields that must not reach the client. The body is already
/// decoded and Hyper owns its framing, so the
/// origin's framing and encoding fields are dropped, together with the RFC
/// 9110 section 7.6.1 hop-by-hop fields. A kept Transfer-Encoding would
/// override Content-Length (RFC 9112 section 6.3) and break the client.
fn is_skipped_response_header(lower: &str) -> bool {
    matches!(
        lower,
        "connection"
            | "keep-alive"
            | "content-length"
            | "content-encoding"
            | "transfer-encoding"
            | "trailer"
            | "te"
            | "upgrade"
    ) || lower.starts_with("proxy-")
}

/// Lowercase field names listed in the origin's Connection header, which are
/// hop-by-hop too.
fn connection_tokens(headers: &serde_json::Map<String, Value>) -> Vec<String> {
    headers
        .iter()
        .filter(|(key, _)| key.eq_ignore_ascii_case("connection"))
        .flat_map(|(_, value)| header_values_from_json(value))
        .flat_map(|value| value.split(',').map(|token| token.trim().to_ascii_lowercase()).collect::<Vec<_>>())
        .filter(|token| !token.is_empty())
        .collect()
}

/// One field value per output line. UrlFetchApp reports a repeated header
/// (such as Set-Cookie) as an array; each element stays its own line,
/// because Set-Cookie must not be combined (RFC 9110 section 5.3) and other
/// list fields mean the same either way.
fn header_values_from_json(value: &Value) -> Vec<String> {
    match value {
        Value::Array(values) => values.iter().filter_map(header_value_from_json).collect(),
        value => header_value_from_json(value).into_iter().collect(),
    }
}

fn header_value_from_json(value: &Value) -> Option<String> {
    match value {
        Value::String(value) => Some(value.clone()),
        Value::Bool(value) => Some(value.to_string()),
        Value::Number(value) => Some(value.to_string()),
        Value::Null | Value::Object(_) | Value::Array(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn envelope(headers: &str, body: &[u8]) -> Vec<u8> {
        format!(r#"{{"s":200,"h":{headers},"b":"{}"}}"#, BASE64_STANDARD.encode(body)).into_bytes()
    }

    #[test]
    fn repeated_set_cookie_values_stay_separate_header_lines() {
        // Expires contains a comma, so a comma-joined Set-Cookie cannot be
        // split back (RFC 9110 section 5.3).
        let headers =
            r#"{"Set-Cookie":["session=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/","csrf=xyz; Path=/"]}"#;

        let response = parse(&envelope(headers, b"")).expect("parse envelope");
        let lines: Vec<_> =
            response.headers().get_all("set-cookie").iter().map(|value| value.to_str().unwrap()).collect();

        assert_eq!(
            lines,
            vec![
                "session=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/".to_owned(),
                "csrf=xyz; Path=/".to_owned(),
            ]
        );
    }

    #[test]
    fn hop_by_hop_response_headers_are_not_relayed() {
        let headers = r#"{"Transfer-Encoding":"chunked","Content-Type":"text/html","Trailer":"Expires",
            "TE":"trailers","Upgrade":"h2c","Proxy-Authenticate":"Basic","Proxy-Connection":"keep-alive",
            "Connection":"X-Hop","X-Hop":"1","Keep-Alive":"timeout=5"}"#;

        let response = parse(&envelope(headers, b"hello")).expect("parse envelope");

        // The body is already decoded, so Content-Length is the only framing.
        assert_eq!(response.headers().len(), 1);
        assert_eq!(response.headers()["content-type"], "text/html");
        assert_eq!(hyper::body::Body::size_hint(response.body()).exact(), Some(5));
    }

    #[test]
    fn relay_json_rejects_header_injection_and_invalid_status() {
        assert!(parse(&envelope(r#"{"X-Test":"safe\r\nInjected: bad"}"#, b"")).is_err());
        assert!(parse(br#"{"s":99}"#).is_err());
        assert!(parse(br#"{"s":100}"#).is_err());
    }
}
