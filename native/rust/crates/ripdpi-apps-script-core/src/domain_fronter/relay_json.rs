use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use serde::Deserialize;
use serde_json::Value;

use super::FronterError;

#[derive(Default, Deserialize)]
struct RelayResponse {
    #[serde(default, rename = "s")]
    status: Option<u16>,
    #[serde(default, rename = "h")]
    headers: Option<serde_json::Map<String, Value>>,
    #[serde(default, rename = "b")]
    body: Option<String>,
    #[serde(default, rename = "e")]
    error: Option<String>,
}

pub(super) fn parse(body: &[u8]) -> Result<Vec<u8>, FronterError> {
    let text = std::str::from_utf8(body)
        .map_err(|_| FronterError::BadResponse("Apps Script payload is not utf-8".to_string()))?;
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err(FronterError::BadResponse("Apps Script payload is empty".to_string()));
    }
    let response = match serde_json::from_str::<RelayResponse>(trimmed) {
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

    let mut output = Vec::with_capacity(body.len() + 256);
    output.extend_from_slice(format!("HTTP/1.1 {status} {}\r\n", status_text(status)).as_bytes());
    if let Some(headers) = response.headers {
        let connection_tokens = connection_tokens(&headers);
        for (key, value) in headers {
            let lower = key.to_ascii_lowercase();
            if is_skipped_response_header(&lower) || connection_tokens.contains(&lower) {
                continue;
            }
            for value in header_values_from_json(&value) {
                output.extend_from_slice(format!("{key}: {value}\r\n").as_bytes());
            }
        }
    }
    output.extend_from_slice(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes());
    output.extend_from_slice(&body);
    Ok(output)
}

/// Response fields that must not reach the client. The body is already
/// decoded and is framed only by the Content-Length written here, so the
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

pub(super) fn status_text(status: u16) -> &'static str {
    match status {
        200 => "OK",
        201 => "Created",
        204 => "No Content",
        301 => "Moved Permanently",
        302 => "Found",
        304 => "Not Modified",
        307 => "Temporary Redirect",
        308 => "Permanent Redirect",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        504 => "Gateway Timeout",
        _ => "Response",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn envelope(headers: &str, body: &[u8]) -> Vec<u8> {
        format!(r#"{{"s":200,"h":{headers},"b":"{}"}}"#, BASE64_STANDARD.encode(body)).into_bytes()
    }

    /// Splits a synthesized response into its header lines and body.
    fn split(response: &[u8]) -> (Vec<String>, Vec<u8>) {
        let end = response.windows(4).position(|window| window == b"\r\n\r\n").expect("header terminator");
        let head = std::str::from_utf8(&response[..end]).expect("utf-8 head");
        let lines = head.split("\r\n").skip(1).map(str::to_owned).collect();
        (lines, response[end + 4..].to_vec())
    }

    #[test]
    fn repeated_set_cookie_values_stay_separate_header_lines() {
        // Expires contains a comma, so a comma-joined Set-Cookie cannot be
        // split back (RFC 9110 section 5.3).
        let headers =
            r#"{"Set-Cookie":["session=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/","csrf=xyz; Path=/"]}"#;

        let (lines, _) = split(&parse(&envelope(headers, b"")).expect("parse envelope"));

        assert_eq!(
            lines,
            vec![
                "Set-Cookie: session=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/".to_owned(),
                "Set-Cookie: csrf=xyz; Path=/".to_owned(),
                "Content-Length: 0".to_owned(),
            ]
        );
    }

    #[test]
    fn hop_by_hop_response_headers_are_not_relayed() {
        let headers = r#"{"Transfer-Encoding":"chunked","Content-Type":"text/html","Trailer":"Expires",
            "TE":"trailers","Upgrade":"h2c","Proxy-Authenticate":"Basic","Proxy-Connection":"keep-alive",
            "Connection":"X-Hop","X-Hop":"1","Keep-Alive":"timeout=5"}"#;

        let (lines, body) = split(&parse(&envelope(headers, b"hello")).expect("parse envelope"));

        // The body is already decoded, so Content-Length is the only framing.
        assert_eq!(lines, vec!["Content-Type: text/html".to_owned(), "Content-Length: 5".to_owned()]);
        assert_eq!(body, b"hello");
    }
}
