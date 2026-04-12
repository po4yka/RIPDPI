use std::collections::VecDeque;
use std::io;
use std::time::{SystemTime, UNIX_EPOCH};

use http::HeaderValue;
use serde::{Deserialize, Serialize};

use crate::config::{MasqueAuthMode, MasqueConfig};

#[derive(Debug, Clone)]
pub struct AuthHeader {
    pub name: &'static str,
    pub value: String,
}

#[derive(Debug, Default)]
pub struct PrivacyPassCache {
    pub headers: VecDeque<AuthHeader>,
    pub expires_at_epoch_ms: Option<u64>,
}

impl PrivacyPassCache {
    pub fn pop(&mut self) -> Option<AuthHeader> {
        if self.expires_at_epoch_ms.is_some_and(|expires_at| expires_at <= now_ms()) {
            self.headers.clear();
            self.expires_at_epoch_ms = None;
        }
        self.headers.pop_front()
    }

    pub fn extend(&mut self, headers: Vec<AuthHeader>, expires_at_epoch_ms: Option<u64>) {
        self.headers.extend(headers);
        self.expires_at_epoch_ms = expires_at_epoch_ms;
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrivacyPassProviderRequest {
    pub proxy_url: String,
    pub target: String,
    pub challenge_header: String,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrivacyPassProviderResponse {
    pub authorization_headers: Option<Vec<String>>,
    pub authorization_header: Option<String>,
    pub proxy_authorization_headers: Option<Vec<String>>,
    pub proxy_authorization_header: Option<String>,
    pub expires_at_epoch_ms: Option<u64>,
}

impl PrivacyPassProviderResponse {
    pub fn into_headers(self) -> Vec<AuthHeader> {
        let mut headers = Vec::new();
        if let Some(values) = self.authorization_headers {
            headers.extend(values.into_iter().map(|value| AuthHeader { name: "authorization", value }));
        }
        if let Some(value) = self.authorization_header {
            headers.push(AuthHeader { name: "authorization", value });
        }
        if let Some(values) = self.proxy_authorization_headers {
            headers.extend(values.into_iter().map(|value| AuthHeader { name: "proxy-authorization", value }));
        }
        if let Some(value) = self.proxy_authorization_header {
            headers.push(AuthHeader { name: "proxy-authorization", value });
        }
        headers
    }
}

pub fn build_static_auth_header(config: &MasqueConfig) -> io::Result<Option<AuthHeader>> {
    let auth_mode = config.effective_auth_mode();
    match auth_mode {
        MasqueAuthMode::Bearer | MasqueAuthMode::Preshared => {
            let secret = config
                .auth_token
                .as_ref()
                .filter(|value| !value.trim().is_empty())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing MASQUE auth secret"))?;

            Ok(Some(match auth_mode {
                MasqueAuthMode::Bearer => AuthHeader { name: "authorization", value: format!("Bearer {secret}") },
                MasqueAuthMode::Preshared => {
                    AuthHeader { name: "proxy-authorization", value: format!("Preshared {secret}") }
                }
                MasqueAuthMode::None | MasqueAuthMode::PrivacyPass | MasqueAuthMode::CloudflareMtls => {
                    unreachable!("handled above")
                }
            }))
        }
        MasqueAuthMode::None | MasqueAuthMode::PrivacyPass | MasqueAuthMode::CloudflareMtls => Ok(None),
    }
}

pub fn parse_privacy_pass_challenge(value: Option<&HeaderValue>) -> io::Result<String> {
    let value = value.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::PermissionDenied,
            "proxy requested Privacy Pass authentication without WWW-Authenticate details",
        )
    })?;
    let value =
        value.to_str().map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid WWW-Authenticate header"))?;
    let trimmed = value.trim();
    let mut parts = trimmed.splitn(2, char::is_whitespace);
    let scheme = parts.next().unwrap_or_default();
    let params = parts.next().unwrap_or_default();
    if !scheme.eq_ignore_ascii_case("PrivateToken") {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "proxy did not return a PrivateToken challenge"));
    }
    if privacy_pass_auth_param(params, "challenge").is_none() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "proxy Privacy Pass challenge is missing a challenge parameter",
        ));
    }
    if privacy_pass_auth_param(params, "token-key").is_none() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "proxy Privacy Pass challenge is missing a token-key parameter",
        ));
    }
    Ok(trimmed.to_owned())
}

fn privacy_pass_auth_param<'a>(params: &'a str, name: &str) -> Option<&'a str> {
    params.split(',').find_map(|segment| {
        let (key, value) = segment.trim().split_once('=')?;
        if !key.trim().eq_ignore_ascii_case(name) {
            return None;
        }
        let value = value.trim().trim_matches('"');
        (!value.is_empty()).then_some(value)
    })
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_millis() as u64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn privacy_pass_challenge_parser_accepts_private_token_header() {
        let value = HeaderValue::from_static(
            "PrivateToken challenge=AAEABmlzc3VlcgAAAAZvcmlnaW4=, token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        );
        let parsed = parse_privacy_pass_challenge(Some(&value)).expect("parse");
        assert!(parsed.starts_with("PrivateToken "));
    }

    #[test]
    fn privacy_pass_challenge_parser_accepts_case_insensitive_scheme_and_trimmed_header() {
        let value = HeaderValue::from_static(
            "  privatetoken challenge=AAEABmlzc3VlcgAAAAZvcmlnaW4=, token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=  ",
        );

        let parsed = parse_privacy_pass_challenge(Some(&value)).expect("parse");

        assert_eq!(
            parsed,
            "privatetoken challenge=AAEABmlzc3VlcgAAAAZvcmlnaW4=, token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        );
    }

    #[test]
    fn privacy_pass_challenge_parser_rejects_missing_challenge_param() {
        let value = HeaderValue::from_static("PrivateToken token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

        let error = parse_privacy_pass_challenge(Some(&value)).expect_err("missing challenge should fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("challenge parameter"));
    }

    #[test]
    fn privacy_pass_challenge_parser_rejects_missing_token_key_param() {
        let value = HeaderValue::from_static("PrivateToken challenge=AAEABmlzc3VlcgAAAAZvcmlnaW4=");

        let error = parse_privacy_pass_challenge(Some(&value)).expect_err("missing token-key should fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("token-key parameter"));
    }

    #[test]
    fn privacy_pass_challenge_parser_rejects_empty_params() {
        let value = HeaderValue::from_static("PrivateToken challenge=, token-key=\"\"");

        let error = parse_privacy_pass_challenge(Some(&value)).expect_err("empty params should fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn preshared_header_uses_proxy_authorization() {
        let header = build_static_auth_header(&MasqueConfig {
            url: "https://masque.example/".to_string(),
            use_http2_fallback: true,
            auth_mode: Some("preshared".to_string()),
            auth_token: Some("secret".to_string()),
            client_certificate_chain_pem: None,
            client_private_key_pem: None,
            cloudflare_geohash_header: None,
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".to_string(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
        })
        .expect("header")
        .expect("some");
        assert_eq!(header.name, "proxy-authorization");
        assert_eq!(header.value, "Preshared secret");
    }

    #[test]
    fn privacy_pass_mode_does_not_require_static_secret() {
        let header = build_static_auth_header(&MasqueConfig {
            url: "https://masque.example/".to_string(),
            use_http2_fallback: false,
            auth_mode: Some("privacy_pass".to_string()),
            auth_token: None,
            client_certificate_chain_pem: None,
            client_private_key_pem: None,
            cloudflare_geohash_header: None,
            privacy_pass_provider_url: Some("https://provider.example/token".to_string()),
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".to_string(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
        })
        .expect("header");

        assert!(header.is_none());
    }

    #[test]
    fn cloudflare_mtls_mode_does_not_emit_static_auth_headers() {
        let header = build_static_auth_header(&MasqueConfig {
            url: "https://masque.example/".to_string(),
            use_http2_fallback: true,
            auth_mode: Some("cloudflare_mtls".to_string()),
            auth_token: None,
            client_certificate_chain_pem: Some(
                "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----".to_string(),
            ),
            client_private_key_pem: Some("-----BEGIN PRIVATE KEY-----\nZm9v\n-----END PRIVATE KEY-----".to_string()),
            cloudflare_geohash_header: Some("u4pruyd-GB".to_string()),
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".to_string(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
        })
        .expect("header");

        assert!(header.is_none());
    }
}
