use std::collections::HashSet;

use boring::x509::X509;
use ring::digest::{SHA256, digest};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error as TlsError, SignatureScheme};

#[derive(Debug, Clone)]
pub struct TlsPinVerifier {
    spki_sha256: HashSet<[u8; 32]>,
    cert_sha256: HashSet<[u8; 32]>,
}

impl TlsPinVerifier {
    pub fn new(
        spki_sha256: impl IntoIterator<Item = [u8; 32]>,
        cert_sha256: impl IntoIterator<Item = [u8; 32]>,
    ) -> Self {
        Self { spki_sha256: spki_sha256.into_iter().collect(), cert_sha256: cert_sha256.into_iter().collect() }
    }

    pub fn from_spki_sha256(spki_sha256: impl IntoIterator<Item = [u8; 32]>) -> Self {
        Self::new(spki_sha256, [])
    }

    pub fn from_cert_sha256(cert_sha256: impl IntoIterator<Item = [u8; 32]>) -> Self {
        Self::new([], cert_sha256)
    }

    pub fn is_empty(&self) -> bool {
        self.spki_sha256.is_empty() && self.cert_sha256.is_empty()
    }
}

impl ServerCertVerifier for TlsPinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        if self.is_empty() {
            return Err(TlsError::General("DoT TLS pin verifier has no pins".to_string()));
        }
        if self.cert_sha256.contains(&sha256_array(end_entity.as_ref())) {
            return Ok(ServerCertVerified::assertion());
        }
        let certificate = X509::from_der(end_entity.as_ref())
            .map_err(|err| TlsError::General(format!("DoT TLS pin certificate parse failed: {err}")))?;
        let spki_der = certificate
            .public_key()
            .and_then(|key| key.public_key_to_der())
            .map_err(|err| TlsError::General(format!("DoT TLS pin SPKI parse failed: {err}")))?;
        if self.spki_sha256.contains(&sha256_array(&spki_der)) {
            return Ok(ServerCertVerified::assertion());
        }
        Err(TlsError::General("DoT TLS certificate did not match any configured pin".to_string()))
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

pub fn sha256_array(bytes: &[u8]) -> [u8; 32] {
    let hash = digest(&SHA256, bytes);
    let mut output = [0_u8; 32];
    output.copy_from_slice(hash.as_ref());
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::rand::SystemRandom;
    use ring::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair};
    use rustls::internal::msgs::codec::Codec;

    #[test]
    fn pinned_certificate_requires_a_valid_handshake_signature() {
        let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let verifier = TlsPinVerifier::from_cert_sha256([sha256_array(certificate.cert.der())]);
        let random = SystemRandom::new();
        let key = EcdsaKeyPair::from_pkcs8(
            &ECDSA_P256_SHA256_ASN1_SIGNING,
            &certificate.signing_key.serialize_der(),
            &random,
        )
        .unwrap();
        let message = b"TLS handshake transcript";
        let signature = key.sign(&random, message).unwrap();
        let mut encoded = vec![0x04, 0x03]; // ecdsa_secp256r1_sha256
        encoded.extend_from_slice(&(signature.as_ref().len() as u16).to_be_bytes());
        encoded.extend_from_slice(signature.as_ref());
        let signed = DigitallySignedStruct::read_bytes(&encoded).unwrap();
        for tls13 in [false, true] {
            let verify = |data: &[u8]| {
                if tls13 {
                    verifier.verify_tls13_signature(data, certificate.cert.der(), &signed)
                } else {
                    verifier.verify_tls12_signature(data, certificate.cert.der(), &signed)
                }
            };
            assert!(verify(message).is_ok());
            assert!(verify(b"forged handshake transcript").is_err(), "TLS 1.3={tls13}");
        }
    }
}
