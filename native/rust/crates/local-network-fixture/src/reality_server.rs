//! Server side of REALITY authentication for the loopback fixtures.
//!
//! A REALITY client accepts the server only when the leaf certificate is
//! Ed25519 and its signature field equals `HMAC-SHA512(AuthKey, ed25519_pub)`
//! (Xray-core `transport/internet/reality/reality.go`). The server derives the
//! AuthKey from its static X25519 private key and the client's X25519 key
//! share: `HKDF-SHA256(ECDH, salt = client_random[..20], info = "REALITY")`.
//!
//! This module derives the AuthKey independently of `ripdpi-vless`, so the
//! fixtures act as an oracle for the client. It does not validate the sealed
//! SessionID; the fixtures only have to prove that they hold the REALITY
//! private key. Clients that connect to these fixtures must use
//! [`reality_fixture_public_key`].
//!
//! The fixtures accept a ClientHello whose only X25519 share is the
//! standalone one; current REALITY servers reject that form. A fixture pass
//! therefore does not prove interoperability with Xray-core: the owner-only
//! `ripdpi-vless/tests/live_reality.rs` test covers that.

use std::io;

use base64::Engine;
use boring::derive::Deriver;
use boring::pkey::{PKey, Private};
use boring::ssl::{ClientHello, ExtensionType, SelectCertError, SslContextBuilder};
use boring::x509::X509;
use ring::{hkdf, hmac};

/// Static REALITY X25519 private key of the loopback fixtures. Test-only.
const REALITY_FIXTURE_PRIVATE_KEY: [u8; 32] = [0x52; 32];

/// PKCS#8 prefix of a raw X25519 private key (RFC 8410).
const X25519_PKCS8_PREFIX: [u8; 16] =
    [0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20];
/// PKCS#8 v1 prefix of a raw Ed25519 seed (RFC 8410). BoringSSL rejects the
/// PKCS#8 v2 encoding that rcgen generates, so the fixture builds v1 itself.
const ED25519_PKCS8_PREFIX: [u8; 16] =
    [0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20];
/// Seed of the fixtures' temporary-certificate Ed25519 key. Test-only.
const CERTIFICATE_ED25519_SEED: [u8; 32] = [0x45; 32];
/// SubjectPublicKeyInfo prefix of a raw X25519 public key (RFC 8410).
const X25519_SPKI_PREFIX: [u8; 12] = [0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00];
const X25519_GROUP: u16 = 0x001d;
const X25519_KEY_LEN: usize = 32;
const ED25519_SIGNATURE_LEN: usize = 64;

/// Base64 REALITY public key that matches the fixtures' private key.
///
/// # Panics
///
/// Panics if BoringSSL cannot load the constant fixture key.
#[must_use]
pub fn reality_fixture_public_key() -> String {
    // Infallible: the key is a constant, valid RFC 8410 X25519 key.
    let key = fixture_private_key().expect("load REALITY fixture private key");
    let mut raw = [0u8; X25519_KEY_LEN];
    // Infallible: an X25519 key always has a 32-byte raw public key.
    let raw = key.raw_public_key(&mut raw).expect("REALITY fixture public key");
    base64::engine::general_purpose::STANDARD.encode(raw)
}

/// Makes `builder` serve, for each handshake, a temporary Ed25519 certificate
/// whose signature field is the REALITY AuthKey HMAC. The handshake fails
/// when the ClientHello has no X25519 key share.
pub(crate) fn install_reality_server_auth(builder: &mut SslContextBuilder) -> io::Result<()> {
    let private_key = fixture_private_key()?;
    let mut certificate_key_der = ED25519_PKCS8_PREFIX.to_vec();
    certificate_key_der.extend_from_slice(&CERTIFICATE_ED25519_SEED);
    let certificate_key = rcgen::KeyPair::try_from(certificate_key_der.as_slice()).map_err(io::Error::other)?;
    let template = rcgen::CertificateParams::new(vec!["reality.fixture.test".to_owned()])
        .and_then(|params| params.self_signed(&certificate_key))
        .map_err(io::Error::other)?
        .der()
        .to_vec();
    let ed25519_public_key = certificate_key.public_key_raw().to_vec();
    let signing_key =
        PKey::private_key_from_der(&certificate_key_der).map_err(|error| io::Error::other(error.to_string()))?;

    builder.set_select_certificate_callback(move |mut hello: ClientHello<'_>| {
        let auth_key = server_auth_key(&private_key, &hello).ok_or(SelectCertError::ERROR)?;
        let mut der = template.clone();
        let signature_start = der.len() - ED25519_SIGNATURE_LEN;
        let signature = hmac::sign(&hmac::Key::new(hmac::HMAC_SHA512, &auth_key), &ed25519_public_key);
        der[signature_start..].copy_from_slice(signature.as_ref());
        let certificate = X509::from_der(&der).map_err(|_| SelectCertError::ERROR)?;
        let ssl = hello.ssl_mut();
        ssl.set_certificate(&certificate).map_err(|_| SelectCertError::ERROR)?;
        ssl.set_private_key(&signing_key).map_err(|_| SelectCertError::ERROR)?;
        Ok(())
    });
    Ok(())
}

fn fixture_private_key() -> io::Result<PKey<Private>> {
    let mut der = X25519_PKCS8_PREFIX.to_vec();
    der.extend_from_slice(&REALITY_FIXTURE_PRIVATE_KEY);
    PKey::private_key_from_der(&der).map_err(|error| io::Error::other(error.to_string()))
}

fn server_auth_key(private_key: &PKey<Private>, hello: &ClientHello<'_>) -> Option<[u8; 32]> {
    let client_random = hello.random();
    let client_share = x25519_key_share(hello.get_extension(ExtensionType::KEY_SHARE)?)?;
    let mut spki = X25519_SPKI_PREFIX.to_vec();
    spki.extend_from_slice(client_share);
    let peer = PKey::public_key_from_der(&spki).ok()?;
    let mut deriver = Deriver::new(private_key).ok()?;
    deriver.set_peer(&peer).ok()?;
    let mut shared = [0u8; X25519_KEY_LEN];
    if deriver.derive(&mut shared).ok()? != X25519_KEY_LEN {
        return None;
    }

    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, client_random.get(..20)?);
    let info: [&[u8]; 1] = [b"REALITY"];
    let prk = salt.extract(&shared);
    let okm = prk.expand(&info, AuthKeyLen).ok()?;
    let mut auth_key = [0u8; 32];
    okm.fill(&mut auth_key).ok()?;
    Some(auth_key)
}

/// Returns the X25519 entry of a TLS 1.3 `key_share` ClientHello extension.
fn x25519_key_share(extension: &[u8]) -> Option<&[u8]> {
    let (length, entries) = extension.split_first_chunk::<2>()?;
    let mut entries = entries.get(..usize::from(u16::from_be_bytes(*length)))?;
    while let Some((header, rest)) = entries.split_first_chunk::<4>() {
        let group = u16::from_be_bytes([header[0], header[1]]);
        let key_length = usize::from(u16::from_be_bytes([header[2], header[3]]));
        let key = rest.get(..key_length)?;
        if group == X25519_GROUP && key_length == X25519_KEY_LEN {
            return Some(key);
        }
        entries = &rest[key_length..];
    }
    None
}

struct AuthKeyLen;

impl hkdf::KeyType for AuthKeyLen {
    fn len(&self) -> usize {
        32
    }
}

#[cfg(test)]
mod tests {
    use super::{install_reality_server_auth, reality_fixture_public_key, x25519_key_share};
    use base64::Engine;
    use boring::ssl::{SslContext, SslMethod};

    #[test]
    fn fixture_keys_load_in_boringssl() {
        let public_key = base64::engine::general_purpose::STANDARD
            .decode(reality_fixture_public_key())
            .expect("base64 REALITY public key");
        assert_eq!(public_key.len(), 32);
        let mut builder = SslContext::builder(SslMethod::tls()).expect("context builder");
        install_reality_server_auth(&mut builder).expect("install REALITY server auth");
    }

    #[test]
    fn key_share_parser_finds_x25519_after_other_groups() {
        let mut extension = Vec::new();
        let mut entries = Vec::new();
        entries.extend_from_slice(&[0x11, 0xec, 0x00, 0x03, 0xaa, 0xbb, 0xcc]);
        entries.extend_from_slice(&[0x00, 0x1d, 0x00, 0x20]);
        entries.extend_from_slice(&[0x09; 32]);
        extension.extend_from_slice(&u16::try_from(entries.len()).expect("length").to_be_bytes());
        extension.extend_from_slice(&entries);
        assert_eq!(x25519_key_share(&extension), Some(&[0x09; 32][..]));
    }

    #[test]
    fn key_share_parser_rejects_truncated_entry() {
        let extension = [0x00, 0x06, 0x00, 0x1d, 0x00, 0x20, 0x01, 0x02];
        assert_eq!(x25519_key_share(&extension), None);
    }
}
