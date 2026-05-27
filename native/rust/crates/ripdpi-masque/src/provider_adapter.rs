//! Generic provider adapter for self-hosted RFC 9298 MASQUE endpoints.
//!
//! The crate intentionally does not implement commercial relay provider adapters. Proprietary enrollment and authorization flows such as iCloud Private Relay and Cloudflare-specific routing remain outside this module; the in-tree adapter covers standards-based endpoint selection plus bearer or TLS client certificate authentication.

use std::io;

use crate::auth::AuthHeader;
use crate::config::{MasqueAuthMode, MasqueConfig};

/// Stable adapter surface for MASQUE provider authentication.
pub trait MasqueProviderAdapter: Send + Sync {
    /// Short, stable identifier for telemetry and tests.
    fn provider_id(&self) -> &'static str;

    /// Which configured auth mode this adapter represents.
    fn auth_mode(&self) -> MasqueAuthMode;

    /// Build the HTTP auth header for this provider, if authentication happens at the HTTP layer.
    fn auth_header(&self, config: &MasqueConfig) -> io::Result<Option<AuthHeader>>;

    /// Whether Privacy Pass retry handling may be used for this provider.
    fn uses_privacy_pass_retry(&self) -> bool {
        false
    }

    /// Whether this provider authenticates with a TLS client certificate.
    fn requires_client_certificate(&self, _config: &MasqueConfig) -> bool {
        false
    }

    /// Generic providers do not request proprietary geohash routing headers.
    fn wants_geohash_header(&self) -> bool {
        false
    }
}

pub struct GenericSelfHostedAdapter {
    auth_mode: MasqueAuthMode,
}

impl GenericSelfHostedAdapter {
    pub fn new(auth_mode: MasqueAuthMode) -> Self {
        Self { auth_mode }
    }
}

impl MasqueProviderAdapter for GenericSelfHostedAdapter {
    fn provider_id(&self) -> &'static str {
        match self.auth_mode {
            MasqueAuthMode::CloudflareMtls => "generic_self_hosted_tls_client_cert",
            MasqueAuthMode::None | MasqueAuthMode::Bearer | MasqueAuthMode::Preshared => "generic_self_hosted",
            MasqueAuthMode::PrivacyPass => "privacy_pass",
        }
    }

    fn auth_mode(&self) -> MasqueAuthMode {
        self.auth_mode
    }

    fn auth_header(&self, config: &MasqueConfig) -> io::Result<Option<AuthHeader>> {
        match self.auth_mode {
            MasqueAuthMode::Bearer | MasqueAuthMode::Preshared => {
                let secret = config
                    .auth_token
                    .as_ref()
                    .filter(|value| !value.trim().is_empty())
                    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing MASQUE auth secret"))?;

                Ok(Some(match self.auth_mode {
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

    fn uses_privacy_pass_retry(&self) -> bool {
        self.auth_mode == MasqueAuthMode::PrivacyPass
    }

    fn requires_client_certificate(&self, _config: &MasqueConfig) -> bool {
        self.auth_mode == MasqueAuthMode::CloudflareMtls
    }
}

/// Pick the adapter matching a configured `MasqueAuthMode`.
pub fn adapter_for(mode: MasqueAuthMode) -> Box<dyn MasqueProviderAdapter> {
    Box::new(GenericSelfHostedAdapter::new(mode))
}

/// Pick the adapter matching a full MASQUE client configuration.
pub fn adapter_for_config(config: &MasqueConfig) -> Box<dyn MasqueProviderAdapter> {
    adapter_for(config.effective_auth_mode())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adapter_for_each_mode_reports_consistent_metadata() {
        let cases = [
            (MasqueAuthMode::None, "generic_self_hosted", false),
            (MasqueAuthMode::Bearer, "generic_self_hosted", false),
            (MasqueAuthMode::Preshared, "generic_self_hosted", false),
            (MasqueAuthMode::PrivacyPass, "privacy_pass", true),
            (MasqueAuthMode::CloudflareMtls, "generic_self_hosted_tls_client_cert", false),
        ];
        let config = test_config(None, None, None, None);
        for (mode, expected_id, expected_pp_retry) in cases {
            let adapter = adapter_for(mode);
            assert_eq!(adapter.provider_id(), expected_id, "id mismatch for {mode:?}");
            assert_eq!(adapter.auth_mode(), mode, "auth_mode round-trip mismatch for {mode:?}");
            assert_eq!(
                adapter.uses_privacy_pass_retry(),
                expected_pp_retry,
                "uses_privacy_pass_retry mismatch for {mode:?}",
            );
            assert!(!adapter.wants_geohash_header(), "{mode:?} must not request proprietary geohash routing");
            if mode != MasqueAuthMode::CloudflareMtls {
                assert!(!adapter.requires_client_certificate(&config), "{mode:?} must not require TLS client auth");
            }
        }
    }

    #[test]
    fn only_privacy_pass_adapter_requests_retry_flow() {
        for mode in
            [MasqueAuthMode::None, MasqueAuthMode::Bearer, MasqueAuthMode::Preshared, MasqueAuthMode::CloudflareMtls]
        {
            assert!(!adapter_for(mode).uses_privacy_pass_retry(), "{mode:?} must NOT request privacy-pass retry");
        }
        assert!(
            adapter_for(MasqueAuthMode::PrivacyPass).uses_privacy_pass_retry(),
            "privacy_pass adapter must request retry flow",
        );
    }

    #[test]
    fn client_certificate_mode_requires_standard_tls_client_certificate() {
        let config = test_config(
            Some("cloudflare_mtls"),
            None,
            Some("-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----"),
            Some("-----BEGIN PRIVATE KEY-----\nZm9v\n-----END PRIVATE KEY-----"),
        );

        assert!(adapter_for(MasqueAuthMode::CloudflareMtls).requires_client_certificate(&config));
    }

    #[test]
    fn no_adapter_wants_proprietary_geohash_header() {
        for mode in [
            MasqueAuthMode::None,
            MasqueAuthMode::Bearer,
            MasqueAuthMode::Preshared,
            MasqueAuthMode::PrivacyPass,
            MasqueAuthMode::CloudflareMtls,
        ] {
            assert!(!adapter_for(mode).wants_geohash_header(), "{mode:?} must NOT want the geohash header");
        }
    }

    #[test]
    fn bearer_mode_uses_generic_self_hosted_adapter_and_standard_authorization() {
        let config = test_config(Some("bearer"), Some("secret"), None, None);
        let adapter = adapter_for_config(&config);

        assert_eq!(adapter.provider_id(), "generic_self_hosted");
        let header = adapter.auth_header(&config).expect("auth header").expect("bearer header");
        assert_eq!(header.name, "authorization");
        assert_eq!(header.value, "Bearer secret");
        assert!(!adapter.requires_client_certificate(&config));
        assert!(!adapter.wants_geohash_header());
    }

    #[test]
    fn client_certificate_mode_uses_generic_self_hosted_tls_adapter() {
        let config = test_config(
            Some("cloudflare_mtls"),
            None,
            Some("-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----"),
            Some("-----BEGIN PRIVATE KEY-----\nZm9v\n-----END PRIVATE KEY-----"),
        );
        let adapter = adapter_for_config(&config);

        assert_eq!(adapter.provider_id(), "generic_self_hosted_tls_client_cert");
        assert!(adapter.auth_header(&config).expect("auth header").is_none());
        assert!(adapter.requires_client_certificate(&config));
        assert!(!adapter.wants_geohash_header());
    }

    fn test_config(
        auth_mode: Option<&str>,
        auth_token: Option<&str>,
        client_certificate_chain_pem: Option<&str>,
        client_private_key_pem: Option<&str>,
    ) -> MasqueConfig {
        MasqueConfig {
            url: "https://masque.example/".to_string(),
            use_http2_fallback: true,
            auth_mode: auth_mode.map(ToOwned::to_owned),
            auth_token: auth_token.map(ToOwned::to_owned),
            client_certificate_chain_pem: client_certificate_chain_pem.map(ToOwned::to_owned),
            client_private_key_pem: client_private_key_pem.map(ToOwned::to_owned),
            cloudflare_geohash_header: Some("u4pruyd-GB".to_string()),
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".to_string(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            ech_config: None,
        }
    }
}
