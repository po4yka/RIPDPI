//! Shared QUIC transport configuration and the `quinn` + `rustls` config
//! factory.
//!
//! Before this module, `ripdpi-hysteria2` and `ripdpi-masque` each built
//! their own `quinn::ClientConfig` from a hand-rolled `rustls::ClientConfig`,
//! duplicating the ALPN / root-store / insecure-verifier wiring. This module
//! centralizes that into one [`QuicTransportConfig`] plus
//! [`QuicTransportConfig::build_rustls_client_config`] /
//! [`QuicTransportConfig::build_quinn_client_config`], so any outbound
//! (Hysteria2, MASQUE, and the not-yet-existing VLESS-QUIC / VMess-QUIC /
//! generic H3 CONNECT shapes) configures QUIC the same way.
//!
//! The config exposes the knobs the epic calls out as needing to be
//! "configurable at the transport boundary": **ALPN**, **SNI**, and a
//! per-profile **uTLS-style fingerprint profile** name.

use std::sync::Arc;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, RootCertStore, SignatureScheme};

use crate::error::{HysteriaError, Result};

/// ALPN token for HTTP/3, used by both Hysteria2's auth exchange and MASQUE.
pub const ALPN_H3: &[u8] = b"h3";

/// Configurable QUIC transport parameters, shared by every QUIC-carried
/// outbound.
///
/// This is intentionally protocol-agnostic: it describes *how to stand up a
/// QUIC connection*, not what protocol rides inside it. Hysteria2 layers its
/// auth + TCP/UDP framing on top; MASQUE layers HTTP/3 CONNECT on top; a
/// future VLESS-QUIC outbound would layer the VLESS handshake on top.
#[derive(Debug, Clone)]
pub struct QuicTransportConfig {
    /// SNI / server name presented in the TLS ClientHello and used by
    /// `quinn` to validate the certificate. For Reality-style profiles this
    /// is the cover domain, not the real host.
    pub server_name: String,
    /// ALPN protocols offered, most-preferred first. Defaults to `["h3"]`.
    pub alpn_protocols: Vec<Vec<u8>>,
    /// Per-profile uTLS-style fingerprint profile name (e.g. `chrome_stable`).
    /// Carried at the transport boundary so the caller can shape the QUIC
    /// ClientHello; `None` uses the stack default.
    pub fingerprint_profile: Option<String>,
    /// Skip certificate validation. Required for Reality / self-signed
    /// deployments; never enable for a normal CA-validated profile.
    pub insecure: bool,
    /// Prefer binding the QUIC UDP socket to a low, stable-looking source
    /// port range (mirrors the Hysteria2 / MASQUE `quic_bind_low_port` knob).
    pub bind_low_port: bool,
    /// Rebind the QUIC endpoint after the handshake so `quinn` performs an
    /// RFC 9000 path validation (mirrors `quic_migrate_after_handshake`).
    pub migrate_after_handshake: bool,
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}

impl QuicTransportConfig {
    /// Build a config for `server_name` with the HTTP/3 ALPN and stack
    /// defaults for every other knob.
    pub fn new(server_name: impl Into<String>) -> Self {
        Self {
            server_name: server_name.into(),
            alpn_protocols: vec![ALPN_H3.to_vec()],
            fingerprint_profile: None,
            insecure: false,
            bind_low_port: false,
            migrate_after_handshake: false,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        }
    }

    /// Builder: replace the offered ALPN protocol list.
    pub fn with_alpn(mut self, alpn_protocols: Vec<Vec<u8>>) -> Self {
        self.alpn_protocols = alpn_protocols;
        self
    }

    /// Builder: set the per-profile uTLS-style fingerprint profile name.
    pub fn with_fingerprint_profile(mut self, profile: impl Into<String>) -> Self {
        self.fingerprint_profile = Some(profile.into());
        self
    }

    /// Builder: toggle certificate validation off (Reality / self-signed).
    pub fn with_insecure(mut self, insecure: bool) -> Self {
        self.insecure = insecure;
        self
    }

    /// Builder: prefer binding the QUIC UDP socket to a low port range.
    pub fn with_bind_low_port(mut self, bind_low_port: bool) -> Self {
        self.bind_low_port = bind_low_port;
        self
    }

    /// Builder: rebind the endpoint after the handshake for path validation.
    pub fn with_migrate_after_handshake(mut self, migrate: bool) -> Self {
        self.migrate_after_handshake = migrate;
        self
    }

    /// Build the shared `rustls::ClientConfig` for this profile.
    ///
    /// Applies the configured ALPN list and, for non-`insecure` profiles, the
    /// webpki root store. `insecure` profiles install the
    /// no-op certificate verifier instead -- the same behavior Hysteria2 and
    /// MASQUE each implemented separately before this factory existed.
    ///
    /// The `ring` crypto provider is selected *explicitly* via
    /// `builder_with_provider`: the workspace enables both the `ring` and
    /// `aws-lc-rs` rustls features, so the bare `ClientConfig::builder()`
    /// cannot auto-pick a provider. MASQUE's H3 client already pins `ring`
    /// the same way; centralizing that choice here is part of the point of
    /// the shared factory.
    pub fn build_rustls_client_config(&self) -> Result<rustls::ClientConfig> {
        let builder = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .map_err(|error| {
                HysteriaError::Io(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("ring provider does not support the default TLS versions: {error}"),
                ))
            })?;
        let builder = if self.insecure {
            builder.dangerous().with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
        } else {
            let mut roots = RootCertStore::empty();
            roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            builder.with_root_certificates(roots)
        };
        let mut tls_config = builder.with_no_client_auth();
        tls_config.alpn_protocols = self.alpn_protocols.clone();
        Ok(tls_config)
    }

    /// Build the `quinn::ClientConfig` for this profile, wrapping the shared
    /// `rustls::ClientConfig` in `quinn`'s crypto layer.
    pub fn build_quinn_client_config(&self) -> Result<quinn::ClientConfig> {
        let tls_config = self.build_rustls_client_config()?;
        let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls_config).map_err(|error| {
            HysteriaError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                format!("failed to build QUIC TLS config: {error}"),
            ))
        })?;
        Ok(quinn::ClientConfig::new(Arc::new(quic_crypto)))
    }
}

/// No-op certificate verifier for Reality / self-signed QUIC deployments.
///
/// This is the verifier Hysteria2 and MASQUE each carried privately; it lives
/// here now so there is exactly one copy of this `unsafe`-in-spirit code path
/// and one place to audit it.
#[derive(Debug)]
struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_defaults_to_h3_alpn_and_safe_knobs() {
        let config = QuicTransportConfig::new("example.com");
        assert_eq!(config.server_name, "example.com");
        assert_eq!(config.alpn_protocols, vec![ALPN_H3.to_vec()]);
        assert_eq!(config.fingerprint_profile, None);
        assert!(!config.insecure, "validation must be ON by default");
        assert!(!config.bind_low_port);
        assert!(!config.migrate_after_handshake);
    }

    #[test]
    fn builder_sets_every_knob() {
        let config = QuicTransportConfig::new("cover.example")
            .with_alpn(vec![b"h3".to_vec(), b"hq-interop".to_vec()])
            .with_fingerprint_profile("chrome_stable")
            .with_insecure(true)
            .with_bind_low_port(true)
            .with_migrate_after_handshake(true);
        assert_eq!(config.alpn_protocols, vec![b"h3".to_vec(), b"hq-interop".to_vec()]);
        assert_eq!(config.fingerprint_profile.as_deref(), Some("chrome_stable"));
        assert!(config.insecure);
        assert!(config.bind_low_port);
        assert!(config.migrate_after_handshake);
    }

    #[test]
    fn rustls_config_applies_configured_alpn() {
        let config = QuicTransportConfig::new("example.com").with_alpn(vec![b"h3".to_vec(), b"custom".to_vec()]);
        let tls = config.build_rustls_client_config().expect("build rustls config");
        assert_eq!(tls.alpn_protocols, vec![b"h3".to_vec(), b"custom".to_vec()]);
    }

    #[test]
    fn rustls_config_builds_for_validated_profile() {
        // A CA-validated profile builds a config with the webpki root store.
        let config = QuicTransportConfig::new("example.com");
        assert!(config.build_rustls_client_config().is_ok());
    }

    #[test]
    fn rustls_config_builds_for_insecure_profile() {
        // A Reality / self-signed profile builds a config with the no-op
        // verifier installed.
        let config = QuicTransportConfig::new("cover.example").with_insecure(true);
        assert!(config.build_rustls_client_config().is_ok());
    }

    #[test]
    fn quinn_client_config_builds_from_shared_factory() {
        // The whole point of the factory: a single call produces the
        // quinn::ClientConfig both Hysteria2 and MASQUE need.
        let config = QuicTransportConfig::new("example.com");
        assert!(config.build_quinn_client_config().is_ok());
    }

    #[test]
    fn quinn_client_config_builds_for_insecure_profile() {
        let config = QuicTransportConfig::new("cover.example").with_insecure(true);
        assert!(config.build_quinn_client_config().is_ok());
    }
}
