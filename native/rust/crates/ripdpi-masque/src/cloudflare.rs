use std::io;

use crate::config::MasqueConfig;

/// Construct Cloudflare-specific authentication headers.
///
/// Cloudflare's MASQUE `cf-connect-ip` flow requires ECDSA-authenticated
/// requests. Until that flow is implemented end-to-end, fail fast instead of
/// emitting placeholder headers that make the mode look functional.
pub fn cloudflare_auth_headers(config: &MasqueConfig) -> io::Result<Vec<(String, String)>> {
    if config.cloudflare_mode {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "Cloudflare MASQUE auth is not implemented yet"));
    }

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

    Ok(headers)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cloudflare_headers_reject_unsupported_mode() {
        let config = MasqueConfig {
            url: "https://masque.example.com/".to_owned(),
            use_http2_fallback: false,
            cloudflare_mode: true,
            auth_mode: Some("cloudflare".to_owned()),
            auth_token: Some("test-token".to_owned()),
            cf_client_id: Some("client-123".to_owned()),
            cf_key_id: Some("key-456".to_owned()),
            cf_private_key_pem: None,
            tls_fingerprint_profile: "chrome_stable".to_owned(),
        };

        let error = cloudflare_auth_headers(&config).expect_err("Cloudflare auth should fail fast");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
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
            tls_fingerprint_profile: "chrome_stable".to_owned(),
        };

        let headers = cloudflare_auth_headers(&config).expect("non-Cloudflare mode should remain a no-op");
        assert!(headers.is_empty());
    }
}
