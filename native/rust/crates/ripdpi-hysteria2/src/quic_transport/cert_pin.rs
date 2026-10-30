//! Leaf-certificate SHA-256 pinning for QUIC client connections.
//!
//! This implements the upstream Hysteria2 `pinSHA256` option (and the
//! Clash/mihomo `fingerprint` option): the pin is the SHA-256 digest of the
//! server's leaf certificate in DER form.
//!
//! Semantics:
//!
//! * pin + `insecure`: the pin replaces CA validation. The verifier accepts the
//!   leaf only when its digest matches the pin.
//! * pin without `insecure`: the verifier requires both a valid webpki CA chain
//!   and a matching pin. Upstream Hysteria2 does the same, because Go runs
//!   `VerifyPeerCertificate` after the standard chain verification.
//! * In both modes the TLS 1.2 / TLS 1.3 handshake signatures are verified
//!   against the leaf public key. A matching pin therefore proves that the peer
//!   holds the pinned certificate's private key.
//!
//! Deviation from upstream: upstream Hysteria2 and mihomo accept a pin that
//! matches *any* certificate the server sends. This verifier matches only the
//! leaf. The handshake signature is checked with the leaf key, so a server that
//! adds a copy of the pinned certificate after its own leaf cannot pass.

use std::fmt;
use std::sync::Arc;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::crypto::hash::{Hash, HashAlgorithm};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{CertificateError, DigitallySignedStruct, SignatureScheme, SupportedCipherSuite};

use crate::error::{HysteriaError, Result};

/// SHA-256 digest of a leaf certificate in DER form.
pub type CertificatePinSha256 = [u8; 32];

const SHA256_LEN: usize = 32;

/// Parse a Hysteria2 `pinSHA256` value.
///
/// Accepts the forms that upstream accepts: 64 hex digits, optionally
/// separated by `:` or `-`, in any letter case.
///
/// # Errors
///
/// Returns [`HysteriaError::InvalidCertificatePin`] when the value is not a
/// SHA-256 digest.
pub fn parse_certificate_pin_sha256(raw: &str) -> Result<CertificatePinSha256> {
    let hex: Vec<u8> = raw.trim().bytes().filter(|byte| *byte != b':' && *byte != b'-').collect();
    if hex.len() != SHA256_LEN * 2 {
        return Err(HysteriaError::InvalidCertificatePin(format!(
            "expected {} hex digits, got {}",
            SHA256_LEN * 2,
            hex.len()
        )));
    }
    let mut pin = [0_u8; SHA256_LEN];
    let (pairs, _) = hex.as_chunks::<2>();
    for (slot, [high, low]) in pin.iter_mut().zip(pairs) {
        let (Some(high), Some(low)) = (hex_value(*high), hex_value(*low)) else {
            return Err(HysteriaError::InvalidCertificatePin("value contains a non-hex character".to_string()));
        };
        *slot = (high << 4) | low;
    }
    Ok(pin)
}

fn hex_value(digit: u8) -> Option<u8> {
    match digit {
        b'0'..=b'9' => Some(digit - b'0'),
        b'a'..=b'f' => Some(digit - b'a' + 10),
        b'A'..=b'F' => Some(digit - b'A' + 10),
        _ => None,
    }
}

/// The SHA-256 implementation of the `ring` provider, taken from a TLS 1.3
/// SHA-256 cipher suite. This avoids a direct `ring` or `sha2` dependency.
fn sha256_hasher() -> Option<&'static dyn Hash> {
    let SupportedCipherSuite::Tls13(suite) = rustls::crypto::ring::cipher_suite::TLS13_AES_128_GCM_SHA256 else {
        return None;
    };
    let hasher = suite.common.hash_provider;
    (hasher.algorithm() == HashAlgorithm::SHA256).then_some(hasher)
}

fn sha256(data: &[u8]) -> Option<CertificatePinSha256> {
    let output = sha256_hasher()?.hash(data);
    output.as_ref().try_into().ok()
}

/// Compare two digests without an early exit on the first different byte.
fn digests_equal(left: &CertificatePinSha256, right: &CertificatePinSha256) -> bool {
    let difference = left.iter().zip(right).fold(0_u8, |acc, (a, b)| acc | (a ^ b));
    std::hint::black_box(difference) == 0
}

/// Server certificate verifier that enforces a leaf-certificate SHA-256 pin.
pub(crate) struct PinnedCertificateVerifier {
    pin: CertificatePinSha256,
    /// `Some` when the profile is not `insecure`: the CA chain must also pass.
    ca_verifier: Option<Arc<dyn ServerCertVerifier>>,
    provider: Arc<CryptoProvider>,
}

impl PinnedCertificateVerifier {
    pub(crate) fn new(
        pin: CertificatePinSha256,
        ca_verifier: Option<Arc<dyn ServerCertVerifier>>,
        provider: Arc<CryptoProvider>,
    ) -> Result<Self> {
        if sha256_hasher().is_none() {
            return Err(HysteriaError::InvalidCertificatePin(
                "the TLS provider has no SHA-256 implementation".to_string(),
            ));
        }
        Ok(Self { pin, ca_verifier, provider })
    }

    fn leaf_matches_pin(&self, end_entity: &CertificateDer<'_>) -> bool {
        sha256(end_entity.as_ref()).is_some_and(|digest| digests_equal(&digest, &self.pin))
    }
}

impl fmt::Debug for PinnedCertificateVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("PinnedCertificateVerifier")
            .field("requires_ca_chain", &self.ca_verifier.is_some())
            .finish_non_exhaustive()
    }
}

impl ServerCertVerifier for PinnedCertificateVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp_response: &[u8],
        now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        if let Some(ca_verifier) = &self.ca_verifier {
            ca_verifier.verify_server_cert(end_entity, intermediates, server_name, ocsp_response, now)?;
        }
        if !self.leaf_matches_pin(end_entity) {
            return Err(rustls::Error::InvalidCertificate(CertificateError::ApplicationVerificationFailure));
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use rustls::internal::msgs::codec::Codec;

    fn ring_provider() -> Arc<CryptoProvider> {
        Arc::new(rustls::crypto::ring::default_provider())
    }

    fn self_signed() -> rcgen::CertifiedKey<rcgen::KeyPair> {
        rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("self-signed cert")
    }

    fn server_name() -> ServerName<'static> {
        ServerName::try_from("localhost").expect("server name")
    }

    #[test]
    fn sha256_matches_the_fips_180_2_test_vector() {
        let digest = sha256(b"abc").expect("sha256 available");
        let expected = parse_certificate_pin_sha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            .expect("pin");
        assert_eq!(digest, expected);
    }

    #[test]
    fn parse_accepts_plain_colon_and_dash_forms_in_any_case() {
        let plain = "ab".repeat(32);
        let colon_upper = plain
            .to_uppercase()
            .as_bytes()
            .chunks(2)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect::<Vec<_>>()
            .join(":");
        let dashed = plain.as_bytes().chunks(2).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join("-");
        for value in [plain.as_str(), colon_upper.as_str(), dashed.as_str()] {
            assert_eq!(parse_certificate_pin_sha256(value).expect("valid pin"), [0xab; 32], "{value}");
        }
    }

    #[test]
    fn parse_rejects_short_long_and_non_hex_values() {
        for value in ["", "abcd", &"ab".repeat(33), &"zz".repeat(32)] {
            assert!(matches!(parse_certificate_pin_sha256(value), Err(HysteriaError::InvalidCertificatePin(_))));
        }
    }

    #[test]
    fn insecure_pin_accepts_the_matching_self_signed_leaf() {
        let cert = self_signed();
        let pin = sha256(cert.cert.der()).expect("digest");
        let verifier = PinnedCertificateVerifier::new(pin, None, ring_provider()).expect("verifier");

        let result = verifier.verify_server_cert(cert.cert.der(), &[], &server_name(), &[], UnixTime::now());

        assert!(result.is_ok());
    }

    #[test]
    fn insecure_pin_rejects_a_different_leaf() {
        let pinned = self_signed();
        let presented = self_signed();
        let pin = sha256(pinned.cert.der()).expect("digest");
        let verifier = PinnedCertificateVerifier::new(pin, None, ring_provider()).expect("verifier");

        let result = verifier.verify_server_cert(presented.cert.der(), &[], &server_name(), &[], UnixTime::now());

        assert!(matches!(
            result,
            Err(rustls::Error::InvalidCertificate(CertificateError::ApplicationVerificationFailure))
        ));
    }

    #[test]
    fn pin_does_not_match_a_pinned_certificate_sent_as_an_intermediate() {
        let pinned = self_signed();
        let presented = self_signed();
        let pin = sha256(pinned.cert.der()).expect("digest");
        let verifier = PinnedCertificateVerifier::new(pin, None, ring_provider()).expect("verifier");

        let result = verifier.verify_server_cert(
            presented.cert.der(),
            std::slice::from_ref(pinned.cert.der()),
            &server_name(),
            &[],
            UnixTime::now(),
        );

        assert!(result.is_err(), "only the leaf is bound to the handshake signature");
    }

    #[test]
    fn pin_without_insecure_still_requires_the_ca_chain() {
        let cert = self_signed();
        let pin = sha256(cert.cert.der()).expect("digest");
        let provider = ring_provider();
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let ca = ripdpi_tls_profiles::cert_blocklist::blocklisting_webpki_verifier(Arc::new(roots), provider.clone())
            .expect("webpki verifier");
        let verifier = PinnedCertificateVerifier::new(pin, Some(ca), provider).expect("verifier");

        let result = verifier.verify_server_cert(cert.cert.der(), &[], &server_name(), &[], UnixTime::now());

        assert!(result.is_err(), "a self-signed leaf must fail CA validation even when the pin matches");
    }

    #[test]
    fn pinned_verifier_does_not_rubber_stamp_handshake_signatures() {
        let cert = self_signed();
        let pin = sha256(cert.cert.der()).expect("digest");
        let verifier = PinnedCertificateVerifier::new(pin, None, ring_provider()).expect("verifier");
        // ecdsa_secp256r1_sha256 with a 4-byte garbage signature.
        let forged = DigitallySignedStruct::read_bytes(&[0x04, 0x03, 0x00, 0x04, 1, 2, 3, 4]).expect("dss");

        assert!(verifier.verify_tls13_signature(b"transcript", cert.cert.der(), &forged).is_err());
        assert!(verifier.verify_tls12_signature(b"transcript", cert.cert.der(), &forged).is_err());
        assert!(!verifier.supported_verify_schemes().is_empty());
    }
}
