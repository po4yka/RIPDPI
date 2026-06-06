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
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
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

pub fn sha256_array(bytes: &[u8]) -> [u8; 32] {
    let hash = digest(&SHA256, bytes);
    let mut output = [0_u8; 32];
    output.copy_from_slice(hash.as_ref());
    output
}
