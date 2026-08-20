pub fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
    use base64::engine::{Engine, general_purpose::STANDARD};

    let Ok(request_str) = std::str::from_utf8(request) else { return false };
    for line in request_str.lines() {
        if let Some(value) = line.strip_prefix("Proxy-Authorization:") {
            let value = value.trim();
            if let Some(encoded) = value.strip_prefix("Basic ") {
                let encoded = encoded.trim();
                if let Ok(decoded) = STANDARD.decode(encoded) {
                    let mut expected = Vec::with_capacity("ripdpi:".len() + token.len());
                    expected.extend_from_slice(b"ripdpi:");
                    expected.extend_from_slice(token.as_bytes());
                    return constant_time_eq::constant_time_eq(&decoded, &expected);
                }
            }
            return false;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_basic_proxy_authorization_token() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic cmlwZHBpOnMzY3IzdA==\r\n\r\n";

        assert!(validate_http_proxy_auth(request, "s3cr3t"));
        assert!(!validate_http_proxy_auth(request, "other"));
    }

    #[test]
    fn rejects_missing_or_malformed_proxy_authorization() {
        assert!(!validate_http_proxy_auth(b"CONNECT example.com:443 HTTP/1.1\r\n\r\n", "s3cr3t"));
        assert!(!validate_http_proxy_auth(
            b"CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Bearer token\r\n\r\n",
            "s3cr3t",
        ));
        assert!(!validate_http_proxy_auth(&[0xff, 0xfe], "s3cr3t"));
    }

    #[test]
    fn rejects_same_length_wrong_prefix_and_suffix_tokens() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic cmlwZHBpOnMzY3IzdA==\r\n\r\n";

        assert!(!validate_http_proxy_auth(request, "x3cr3t"));
        assert!(!validate_http_proxy_auth(request, "s3cr3x"));
    }
}
