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
        for (key, value) in headers {
            let lower = key.to_ascii_lowercase();
            if matches!(lower.as_str(), "connection" | "keep-alive" | "content-length" | "content-encoding") {
                continue;
            }
            if let Some(value) = header_value_from_json(&value) {
                output.extend_from_slice(format!("{key}: {value}\r\n").as_bytes());
            }
        }
    }
    output.extend_from_slice(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes());
    output.extend_from_slice(&body);
    Ok(output)
}

fn header_value_from_json(value: &Value) -> Option<String> {
    match value {
        Value::Array(values) => Some(values.iter().filter_map(header_value_from_json).collect::<Vec<_>>().join(", ")),
        Value::String(value) => Some(value.clone()),
        Value::Bool(value) => Some(value.to_string()),
        Value::Number(value) => Some(value.to_string()),
        Value::Null | Value::Object(_) => None,
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
