use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use serde::Serialize;
use serde_json::Value;

use super::FronterError;

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
            match map.get_mut(&key) {
                None => {
                    map.insert(key, Value::String(value));
                }
                Some(Value::String(previous)) => {
                    let separator = match key.as_str() {
                        "cookie" => "; ",
                        "authorization" | "content-type" | "origin" | "referer" | "user-agent" => {
                            return Err(FronterError::BadResponse("unsupported repeated request header".into()));
                        }
                        _ => ", ",
                    };
                    previous.push_str(separator);
                    previous.push_str(&value);
                }
                Some(_) => return Err(FronterError::BadResponse("invalid request header value".into())),
            }
        }
        Some(map)
    };
    let content_type = if body.is_empty() {
        None
    } else {
        header_map.as_ref().and_then(|map| map.get("content-type")).and_then(Value::as_str).map(ToOwned::to_owned)
    };
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
    let connection_tokens: Vec<_> = headers
        .iter()
        .filter(|(key, _)| key.eq_ignore_ascii_case("connection"))
        .flat_map(|(_, value)| value.split(','))
        .map(|token| token.trim().to_ascii_lowercase())
        .collect();
    headers
        .iter()
        .filter_map(|(key, value)| {
            let lower = key.to_ascii_lowercase();
            if matches!(
                lower.as_str(),
                "host"
                    | "connection"
                    | "content-length"
                    | "transfer-encoding"
                    | "keep-alive"
                    | "te"
                    | "trailer"
                    | "upgrade"
            ) || lower.starts_with("proxy-")
                || connection_tokens.contains(&lower)
            {
                return None;
            }
            if lower == "accept-encoding" {
                let value = strip_unsupported_encodings(value);
                return (!value.is_empty()).then_some((lower, value));
            }
            Some((lower, value.clone()))
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
        assert_eq!(filtered, vec![("accept-encoding".to_string(), "gzip, deflate".to_string())]);
    }

    #[test]
    fn original_framing_is_not_forwarded_to_apps_script() {
        let payload = build_payload_json(
            "key",
            "POST",
            "https://example.test/",
            &[("Transfer-Encoding".into(), "chunked".into()), ("Content-Length".into(), "999".into())],
            b"abc",
        )
        .unwrap();
        let payload: serde_json::Value = serde_json::from_slice(&payload).unwrap();
        assert!(payload.get("h").is_none());
        assert_eq!(payload["b"], "YWJj");
    }
}

#[cfg(test)]
mod rly15_regressions {
    use super::*;
    #[test]
    fn rly15_hop_fields_and_proxy_credentials_do_not_leave_the_client() {
        let payload = build_payload_json(
            "key",
            "GET",
            "https://example.test/",
            &[
                ("Connection".into(), "X-Hop, TE, Content-Type".into()),
                ("Content-Type".into(), "local-secret".into()),
                ("cOnNeCtIoN".into(), "X-Other".into()),
                ("X-Hop".into(), "local-secret".into()),
                ("X-Other".into(), "local-secret".into()),
                ("Proxy-Authorization".into(), "Basic local-secret".into()),
                ("TE".into(), "trailers".into()),
                ("Authorization".into(), "Bearer origin-token".into()),
            ],
            b"abc",
        )
        .unwrap();
        assert!(!String::from_utf8_lossy(&payload).contains("local-secret"));
        let payload: Value = serde_json::from_slice(&payload).unwrap();
        assert!(payload.get("ct").is_none());
        assert_eq!(payload["h"], serde_json::json!({"authorization": "Bearer origin-token"}));
    }
    #[test]
    fn rly15_repeated_list_headers_and_cookies_keep_values_in_order() {
        let payload = build_payload_json(
            "key",
            "GET",
            "https://example.test/",
            &[
                ("Accept".into(), "text/html".into()),
                ("accept".into(), "application/json".into()),
                ("Cookie".into(), "session=first".into()),
                ("cookie".into(), "csrf=second".into()),
            ],
            b"",
        )
        .unwrap();
        let payload: Value = serde_json::from_slice(&payload).unwrap();
        assert_eq!(
            payload["h"],
            serde_json::json!({"accept": "text/html, application/json", "cookie": "session=first; csrf=second"})
        );
        assert!(
            build_payload_json(
                "key",
                "GET",
                "https://example.test/",
                &[("Authorization".into(), "first".into()), ("authorization".into(), "second".into())],
                b""
            )
            .is_err()
        );
    }
}
