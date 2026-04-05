use crate::config::MasqueConfig;

/// Construct Cloudflare-specific authentication headers.
///
/// Returns a list of `(header_name, header_value)` pairs to attach to the
/// Extended CONNECT request when `cloudflare_mode` is enabled.
pub fn cloudflare_auth_headers(config: &MasqueConfig) -> Vec<(String, String)> {
    let mut headers = Vec::new();

    if let Some(ref client_id) = config.cf_client_id {
        headers.push(("cf-client-id".to_owned(), client_id.clone()));
    }

    if let Some(ref key_id) = config.cf_key_id {
        headers.push(("cf-key-id".to_owned(), key_id.clone()));
    }

    if let Some(ref token) = config.auth_token {
        headers.push(("authorization".to_owned(), format!("Bearer {token}")));
    }

    headers
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cloudflare_headers_present() {
        let config = MasqueConfig {
            url: "https://masque.example.com/".to_owned(),
            use_http2_fallback: false,
            cloudflare_mode: true,
            auth_mode: Some("cloudflare".to_owned()),
            auth_token: Some("test-token".to_owned()),
            cf_client_id: Some("client-123".to_owned()),
            cf_key_id: Some("key-456".to_owned()),
            cf_private_key_pem: None,
        };

        let headers = cloudflare_auth_headers(&config);
        assert_eq!(headers.len(), 3);
        assert_eq!(headers[0], ("cf-client-id".to_owned(), "client-123".to_owned()));
        assert_eq!(headers[1], ("cf-key-id".to_owned(), "key-456".to_owned()));
        assert_eq!(headers[2], ("authorization".to_owned(), "Bearer test-token".to_owned()));
    }

    #[test]
    fn cloudflare_headers_empty_when_no_config() {
        let config = MasqueConfig {
            url: "https://masque.example.com/".to_owned(),
            use_http2_fallback: false,
            cloudflare_mode: false,
            auth_mode: None,
            auth_token: None,
            cf_client_id: None,
            cf_key_id: None,
            cf_private_key_pem: None,
        };

        let headers = cloudflare_auth_headers(&config);
        assert!(headers.is_empty());
    }
}
