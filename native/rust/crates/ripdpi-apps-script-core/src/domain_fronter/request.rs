use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use serde::Serialize;
use serde_json::Value;

use super::FronterError;
use super::headers;

#[derive(Serialize)]
struct RelayRequest<'a> {
    #[serde(rename = "k")]
    auth_key: &'a str,
    #[serde(rename = "m")]
    method: &'a str,
    #[serde(rename = "u")]
    url: &'a str,
    #[serde(rename = "h", skip_serializing_if = "Option::is_none")]
    headers: Option<serde_json::Map<String, Value>>,
    #[serde(rename = "b", skip_serializing_if = "Option::is_none")]
    body: Option<String>,
    #[serde(rename = "ct", skip_serializing_if = "Option::is_none")]
    content_type: Option<String>,
    #[serde(rename = "r")]
    follow_redirects: bool,
}

pub(super) fn build_payload_json(
    auth_key: &str,
    method: &str,
    url: &str,
    headers: &[(String, String)],
    body: &[u8],
) -> Result<Vec<u8>, FronterError> {
    let filtered_headers = filter_forwarded_headers(headers);
    let header_map = if filtered_headers.is_empty() {
        None
    } else {
        let mut map = serde_json::Map::new();
        for (key, value) in filtered_headers {
            map.insert(key, Value::String(value));
        }
        Some(map)
    };
    let content_type =
        if body.is_empty() { None } else { headers::value(headers, "content-type").map(ToOwned::to_owned) };
    let request = RelayRequest {
        auth_key,
        method,
        url,
        headers: header_map,
        body: (!body.is_empty()).then(|| BASE64_STANDARD.encode(body)),
        content_type,
        follow_redirects: true,
    };
    Ok(serde_json::to_vec(&request)?)
}

fn filter_forwarded_headers(headers: &[(String, String)]) -> Vec<(String, String)> {
    headers
        .iter()
        .filter_map(|(key, value)| {
            let lower = key.to_ascii_lowercase();
            if matches!(
                lower.as_str(),
                "host" | "connection" | "content-length" | "transfer-encoding" | "proxy-connection"
            ) {
                return None;
            }
            if lower == "accept-encoding" {
                let value = strip_unsupported_encodings(value);
                return (!value.is_empty()).then(|| (key.clone(), value));
            }
            Some((key.clone(), value.clone()))
        })
        .collect()
}

fn strip_unsupported_encodings(value: &str) -> String {
    value
        .split(',')
        .filter_map(|part| {
            let part = part.trim();
            let name = part.split(';').next().unwrap_or_default().trim().to_ascii_lowercase();
            (!matches!(name.as_str(), "br" | "zstd")).then(|| part.to_string())
        })
        .collect::<Vec<_>>()
        .join(", ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_brotli_from_forwarded_headers() {
        let filtered = filter_forwarded_headers(&[
            ("Accept-Encoding".to_string(), "gzip, br, deflate, zstd".to_string()),
            ("Host".to_string(), "example.com".to_string()),
        ]);
        assert_eq!(filtered, vec![("Accept-Encoding".to_string(), "gzip, deflate".to_string())]);
    }
}
