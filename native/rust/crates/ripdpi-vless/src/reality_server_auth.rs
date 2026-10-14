//! REALITY server authentication.
//!
//! The REALITY client turns off X.509 chain validation because the server
//! presents a temporary certificate instead of the cover site's certificate.
//! That alone does not authenticate the server: any TLS interceptor, or the
//! cover site that receives an unauthenticated client, also completes the
//! handshake. Xray-core (`transport/internet/reality/reality.go`,
//! `VerifyPeerCertificate`) accepts the server only when the leaf key is
//! Ed25519 and the certificate signature field equals
//! `HMAC-SHA512(AuthKey, ed25519_public_key)`. Only a server that holds the
//! REALITY private key can derive the AuthKey from the client key share.
//!
//! [`verify_reality_server_certificate`] applies the same check. The caller
//! must run it before it sends the VLESS header, because that header carries
//! the user UUID and the flow destination.

use std::io;

use boring::pkey::Id;
use boring::x509::X509Ref;
use ring::hmac;

const ED25519_PUBLIC_KEY_LEN: usize = 32;

/// Accepts the server only when its leaf certificate proves that the server
/// derived the same REALITY AuthKey as the client.
///
/// # Errors
///
/// Returns [`io::ErrorKind::InvalidData`] when the server sent no
/// certificate, the leaf key is not Ed25519, or the signature field is not
/// `HMAC-SHA512(auth_key, ed25519_public_key)`.
pub(crate) fn verify_reality_server_certificate(leaf: Option<&X509Ref>, auth_key: &[u8; 32]) -> io::Result<()> {
    let leaf = leaf.ok_or_else(|| rejected("the server sent no certificate"))?;
    let public_key = leaf.public_key().map_err(|_| rejected("the certificate public key cannot be read"))?;
    if public_key.id() != Id::ED25519 {
        return Err(rejected("the certificate key is not Ed25519"));
    }
    let mut raw = [0u8; ED25519_PUBLIC_KEY_LEN];
    let raw = public_key.raw_public_key(&mut raw).map_err(|_| rejected("the Ed25519 public key cannot be read"))?;
    verify_auth_signature(raw, leaf.signature().as_slice(), auth_key)
}

/// Constant-time check that `signature` is `HMAC-SHA512(auth_key, public_key)`.
fn verify_auth_signature(public_key: &[u8], signature: &[u8], auth_key: &[u8; 32]) -> io::Result<()> {
    let key = hmac::Key::new(hmac::HMAC_SHA512, auth_key);
    hmac::verify(&key, public_key, signature)
        .map_err(|_| rejected("the certificate signature does not match the REALITY AuthKey"))
}

/// `InvalidData`, not `PermissionDenied`: the workspace uses
/// `PermissionDenied` for a `VpnService.protect()` denial, and telemetry
/// labels the two differently.
fn rejected(reason: &str) -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        format!(
            "Reality server authentication failed: {reason}. The server did not prove that it holds the REALITY \
             private key: check the public key, short id, server name and device clock, or a TLS interceptor is \
             on the path"
        ),
    )
}

#[cfg(test)]
mod tests {
    use super::{verify_auth_signature, verify_reality_server_certificate};
    use boring::x509::X509;
    use ring::hmac;
    use std::io;

    const AUTH_KEY: [u8; 32] = [0x5a; 32];

    fn auth_signature(auth_key: &[u8; 32], public_key: &[u8]) -> Vec<u8> {
        hmac::sign(&hmac::Key::new(hmac::HMAC_SHA512, auth_key), public_key).as_ref().to_vec()
    }

    /// An Ed25519 certificate whose signature field is the REALITY HMAC, as a
    /// REALITY server builds it. The Ed25519 signature is the last 64 bytes
    /// of the DER encoding.
    fn reality_certificate(auth_key: &[u8; 32]) -> X509 {
        let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ED25519).expect("Ed25519 key");
        let params = rcgen::CertificateParams::new(vec!["reality.test".to_string()]).expect("cert params");
        let mut der = params.self_signed(&key).expect("self-signed cert").der().to_vec();
        let signature_start = der.len() - 64;
        der[signature_start..].copy_from_slice(&auth_signature(auth_key, key.public_key_raw()));
        X509::from_der(&der).expect("parse REALITY cert")
    }

    #[test]
    fn accepts_certificate_signed_with_the_auth_key() {
        let cert = reality_certificate(&AUTH_KEY);
        verify_reality_server_certificate(Some(&cert), &AUTH_KEY).expect("matching AuthKey must be accepted");
    }

    #[test]
    fn rejects_certificate_signed_with_another_auth_key() {
        let cert = reality_certificate(&[0x11; 32]);
        let error = verify_reality_server_certificate(Some(&cert), &AUTH_KEY).expect_err("foreign AuthKey");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn rejects_ordinary_self_signed_ed25519_certificate() {
        let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ED25519).expect("Ed25519 key");
        let params = rcgen::CertificateParams::new(vec!["reality.test".to_string()]).expect("cert params");
        let cert = X509::from_der(params.self_signed(&key).expect("self-signed cert").der()).expect("parse cert");
        let error = verify_reality_server_certificate(Some(&cert), &AUTH_KEY).expect_err("plain self-signed cert");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn rejects_non_ed25519_certificate() {
        let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).expect("P-256 key");
        let params = rcgen::CertificateParams::new(vec!["reality.test".to_string()]).expect("cert params");
        let cert = X509::from_der(params.self_signed(&key).expect("self-signed cert").der()).expect("parse cert");
        let error = verify_reality_server_certificate(Some(&cert), &AUTH_KEY).expect_err("ECDSA leaf");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn rejects_missing_certificate() {
        let error = verify_reality_server_certificate(None, &AUTH_KEY).expect_err("no certificate");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn rejects_truncated_signature() {
        let public_key = [0x07; 32];
        let signature = auth_signature(&AUTH_KEY, &public_key);
        verify_auth_signature(&public_key, &signature, &AUTH_KEY).expect("full signature");
        assert!(verify_auth_signature(&public_key, &signature[..32], &AUTH_KEY).is_err());
    }
}
