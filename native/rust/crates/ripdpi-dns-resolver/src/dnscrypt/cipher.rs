use crypto_box::aead::Aead;
use crypto_box::{ChaChaBox, PublicKey as CryptoPublicKey, SalsaBox, SecretKey as CryptoSecretKey};

use crate::types::EncryptedDnsError;

use super::{DNSCRYPT_ES_VERSION_XCHACHA, DNSCRYPT_ES_VERSION_XSALSA, DNSCRYPT_NONCE_SIZE};

/// The DNSCrypt-v2 cipher suite selected by a certificate's `es_version`.
///
/// Both NaCl boxes use a 24-byte nonce and implement [`crypto_box::aead::Aead`];
/// only the underlying AEAD primitive differs. Everything else in the framing —
/// client magic, padding, nonce-prefix handling, response magic — is identical.
pub(crate) enum DnsCryptCipher {
    /// `es_version` 0x0001 — classic NaCl box (XSalsa20Poly1305).
    XSalsa(SalsaBox),
    /// `es_version` 0x0002 — XChaCha20Poly1305.
    XChaCha(ChaChaBox),
}

impl DnsCryptCipher {
    /// Build the cipher for the certificate's advertised `es_version`.
    ///
    /// Returns an error for any `es_version` other than the two supported suites;
    /// callers should reject unsupported certificates before reaching this point,
    /// but the guard is kept here so the routing is total.
    pub(crate) fn new(
        es_version: u16,
        resolver_pk: &CryptoPublicKey,
        client_sk: &CryptoSecretKey,
    ) -> Result<Self, EncryptedDnsError> {
        match es_version {
            DNSCRYPT_ES_VERSION_XSALSA => Ok(Self::XSalsa(SalsaBox::new(resolver_pk, client_sk))),
            DNSCRYPT_ES_VERSION_XCHACHA => Ok(Self::XChaCha(ChaChaBox::new(resolver_pk, client_sk))),
            other => Err(EncryptedDnsError::DnsCryptCertificate(format!("unsupported es_version {other}"))),
        }
    }

    pub(crate) fn encrypt(
        &self,
        nonce: &[u8; DNSCRYPT_NONCE_SIZE],
        plaintext: &[u8],
    ) -> Result<Vec<u8>, crypto_box::aead::Error> {
        match self {
            Self::XSalsa(b) => b.encrypt(nonce.into(), plaintext),
            Self::XChaCha(b) => b.encrypt(nonce.into(), plaintext),
        }
    }

    pub(crate) fn decrypt(
        &self,
        nonce: &[u8; DNSCRYPT_NONCE_SIZE],
        ciphertext: &[u8],
    ) -> Result<Vec<u8>, crypto_box::aead::Error> {
        match self {
            Self::XSalsa(b) => b.decrypt(nonce.into(), ciphertext),
            Self::XChaCha(b) => b.decrypt(nonce.into(), ciphertext),
        }
    }
}
