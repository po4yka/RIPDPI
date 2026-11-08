use ripdpi_session::HttpConnectHead;

pub fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
    HttpConnectHead::parse(request).is_ok_and(|head| validate_http_connect_auth(head, token))
}

pub fn validate_http_connect_auth(head: HttpConnectHead<'_>, token: &str) -> bool {
    use base64::engine::{Engine, general_purpose::STANDARD};

    let Some(value) = head.proxy_authorization() else { return false };
    let mut parts = value.split_ascii_whitespace();
    if let (Some(scheme), Some(encoded), None) = (parts.next(), parts.next(), parts.next())
        && scheme.eq_ignore_ascii_case("Basic")
        && let Ok(decoded) = STANDARD.decode(encoded)
    {
        let mut expected = Vec::with_capacity("ripdpi:".len() + token.len());
        expected.extend_from_slice(b"ripdpi:");
        expected.extend_from_slice(token.as_bytes());
        return constant_time_eq::constant_time_eq(&decoded, &expected);
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_credentials_with_opaque_field_octets() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nX-Opaque: \x80\xff\r\nProxy-Authorization: Basic cmlwZHBpOnMzY3IzdA==\r\n\r\n";
        assert!(validate_http_proxy_auth(request, "s3cr3t"));
        assert!(!validate_http_proxy_auth(request, "other"));
    }

    #[test]
    fn credentials_are_limited_to_the_connect_head() {
        let credentials = b"Proxy-Authorization: Basic cmlwZHBpOnMzY3IzdA==\r\n";
        let mut payload_credentials = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n".to_vec();
        payload_credentials.extend_from_slice(credentials);
        assert!(!validate_http_proxy_auth(&payload_credentials, "s3cr3t"));

        let mut binary_payload = b"CONNECT example.com:443 HTTP/1.1\r\n".to_vec();
        binary_payload.extend_from_slice(credentials);
        binary_payload.extend_from_slice(b"\r\n\x16\x03\xff");
        assert!(validate_http_proxy_auth(&binary_payload, "s3cr3t"));
    }

    #[test]
    fn ignores_credentials_beyond_the_http_header_boundary() {
        for boundary in ["\r\n", "\n"] {
            let request = format!(
                "CONNECT 127.0.0.1:80 HTTP/1.1\r\nHost: 127.0.0.1:80\r\n{boundary}Proxy-Authorization: Basic cmlwZHBpOnMzY3IzdA==\r\n\r\n"
            );
            assert!(!validate_http_proxy_auth(request.as_bytes(), "s3cr3t"));
        }
    }

    #[test]
    fn validates_basic_proxy_authorization_token() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic cmlwZHBpOnMzY3IzdA==\r\n\r\n";

        assert!(validate_http_proxy_auth(request, "s3cr3t"));
        assert!(!validate_http_proxy_auth(request, "other"));
    }

    #[test]
    fn accepts_case_insensitive_header_and_basic_scheme() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nproxy-authorization: bAsIc cmlwZHBpOnMzY3IzdA==\r\n\r\n";
        assert!(validate_http_proxy_auth(request, "s3cr3t"));
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
