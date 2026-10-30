use std::sync::{Arc, Once};

use ripdpi_tls_profiles::{EchFacadeError, EchSetup};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::crypto::hash::{Hash, HashAlgorithm};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    CertificateError, ClientConfig as RustlsClientConfig, DigitallySignedStruct, RootCertStore, SignatureScheme,
    SupportedCipherSuite,
};

/// SHA-256 digest of the server leaf certificate in DER form.
pub(crate) type CertificatePinSha256 = [u8; 32];

static RUSTLS_PROVIDER: Once = Once::new();

#[cfg(test)]
pub(crate) fn default_tls_config() -> Arc<RustlsClientConfig> {
    tls_config_with_ech_setup(&EchSetup::Grease, None).expect("default NaiveProxy TLS config must support ECH GREASE")
}

/// Parse `--server-pin-sha256`: exactly 64 lowercase hex digits (the Kotlin side normalizes first).
pub(crate) fn parse_certificate_pin_sha256(raw: &str) -> Result<CertificatePinSha256, String> {
    if raw.len() != 64 || !raw.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)) {
        return Err("expected 64 lowercase hex digits".to_owned());
    }
    let mut pin = [0_u8; 32];
    for (slot, pair) in pin.iter_mut().zip(raw.as_bytes().as_chunks::<2>().0) {
        let digit = |byte: u8| if byte.is_ascii_digit() { byte - b'0' } else { byte - b'a' + 10 };
        *slot = (digit(pair[0]) << 4) | digit(pair[1]);
    }
    Ok(pin)
}

/// With a pin the webpki root check is replaced by [`PinnedLeafVerifier`].
pub(crate) fn tls_config_with_ech_setup(
    setup: &EchSetup,
    pin: Option<CertificatePinSha256>,
) -> Result<Arc<RustlsClientConfig>, EchFacadeError> {
    ensure_rustls_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let builder = ripdpi_tls_profiles::configure_rustls_ech(
        RustlsClientConfig::builder_with_provider(Arc::clone(&provider)),
        setup,
    )?;
    let mut config = match pin {
        Some(pin) => builder
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(PinnedLeafVerifier { pin, provider }))
            .with_no_client_auth(),
        // Infallible: the store is seeded from webpki_roots::TLS_SERVER_ROOTS and no CRLs are set.
        None => ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
            .expect("bundled webpki roots are non-empty")
            .with_no_client_auth(),
    };
    config.alpn_protocols = vec![b"h2".to_vec()];
    Ok(Arc::new(config))
}

/// Accepts a server only when the SHA-256 of its TLS leaf certificate (DER) equals the pin.
///
/// Semantics match the Hysteria2 `pinSHA256` with `insecure`: the pin replaces CA
/// validation, so a private-CA or self-signed server works, and the hostname is
/// not checked (the pin already names one exact certificate). The handshake
/// signature is still verified with the leaf key, so a match proves the peer
/// holds the pinned certificate's private key. The pin is public, but the
/// comparison does not exit early. Validity dates are not checked either, as
/// with the Hysteria2 pin. There is no mode that skips verification.
#[derive(Debug)]
struct PinnedLeafVerifier {
    pin: CertificatePinSha256,
    provider: Arc<CryptoProvider>,
}

/// SHA-256 from the provider, taken from a TLS 1.3 SHA-256 suite (no direct hash dependency).
pub(crate) fn sha256(data: &[u8]) -> Option<CertificatePinSha256> {
    let SupportedCipherSuite::Tls13(suite) = rustls::crypto::aws_lc_rs::cipher_suite::TLS13_AES_128_GCM_SHA256 else {
        return None;
    };
    let hasher: &dyn Hash = suite.common.hash_provider;
    if hasher.algorithm() != HashAlgorithm::SHA256 {
        return None;
    }
    hasher.hash(data).as_ref().try_into().ok()
}

fn digests_equal(left: &CertificatePinSha256, right: &CertificatePinSha256) -> bool {
    let difference = left.iter().zip(right).fold(0_u8, |acc, (a, b)| acc | (a ^ b));
    std::hint::black_box(difference) == 0
}

impl ServerCertVerifier for PinnedLeafVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if sha256(end_entity.as_ref()).is_some_and(|digest| digests_equal(&digest, &self.pin)) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(CertificateError::ApplicationVerificationFailure))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

pub(crate) fn ensure_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_tls_profiles::EchSetup;

    #[test]
    fn pin_parser_accepts_only_64_lowercase_hex_and_sha256_matches_fips_vector() {
        let abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assert_eq!(sha256(b"abc"), Some(parse_certificate_pin_sha256(abc).expect("pin")));
        for bad in ["", "ab", &abc.to_uppercase(), &abc[..63], &format!("{abc}0"), &abc.replace('b', "g")] {
            assert!(parse_certificate_pin_sha256(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn naive_proxy_tls_config_uses_ech_facade_grease_for_outbound_tunnel() {
        let config = tls_config_with_ech_setup(&EchSetup::Grease, None).expect("ECH TLS config");

        assert_eq!(config.alpn_protocols, vec![b"h2".to_vec()]);
        assert!(rustls_client_hello_has_ech_extension(config), "NaiveProxy must send ECH or GREASE");
    }

    fn rustls_client_hello_has_ech_extension(config: Arc<RustlsClientConfig>) -> bool {
        let server_name = rustls::pki_types::ServerName::try_from("inner.example").expect("server name");
        let mut conn = rustls::ClientConnection::new(config, server_name).expect("client conn");
        let mut bytes = Vec::new();
        conn.write_tls(&mut bytes).expect("write ClientHello");
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&bytes).expect("parse ClientHello");
        layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d)
    }
}
