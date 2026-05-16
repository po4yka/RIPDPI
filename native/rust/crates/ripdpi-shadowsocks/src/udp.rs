//! Shadowsocks UDP packet framing — encrypt/decrypt with AEAD.
//!
//! # Wire format (SIP004 / SIP022 UDP)
//!
//! ```text
//! [salt (salt_len bytes)] [ciphertext + tag]
//! ```
//!
//! UDP uses a fresh random salt per packet. The nonce is all-zero (the salt
//! acts as the per-packet randomisation).

use rand::RngExt;

use crate::cipher::{Cipher, CipherError, CipherKey, SecretString};

/// Shadowsocks UDP packet framer/deframer.
///
/// Encrypts a plaintext datagram into a single AEAD-protected UDP payload
/// (with a leading salt) and decrypts it back. No state is kept between
/// calls — each packet is self-contained.
pub struct UdpPacket {
    cipher: Cipher,
    is_aead_2022: bool,
}

impl UdpPacket {
    /// Creates a new UDP packet codec for `cipher`.
    pub fn new(cipher: Cipher, is_aead_2022: bool) -> Self {
        Self { cipher, is_aead_2022 }
    }

    /// Encrypts `plaintext` into `[salt][ciphertext+tag]`.
    pub fn encrypt(&self, password: &SecretString, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        let mut salt = vec![0u8; self.cipher.salt_len()];
        rand::rng().fill(&mut salt[..]);

        let key = self.derive_key(password, &salt)?;
        let nonce = vec![0u8; self.cipher.nonce_len()];
        let ct = key.encrypt(&nonce, plaintext)?;

        let mut out = Vec::with_capacity(salt.len() + ct.len());
        out.extend_from_slice(&salt);
        out.extend_from_slice(&ct);
        Ok(out)
    }

    /// Decrypts a `[salt][ciphertext+tag]` packet back to plaintext.
    pub fn decrypt(&self, password: &SecretString, packet: &[u8]) -> Result<Vec<u8>, CipherError> {
        let salt_len = self.cipher.salt_len();
        if packet.len() < salt_len + self.cipher.tag_len() {
            return Err(CipherError::AeadFailed);
        }
        let salt = &packet[..salt_len];
        let ct = &packet[salt_len..];

        let key = self.derive_key(password, salt)?;
        let nonce = vec![0u8; self.cipher.nonce_len()];
        key.decrypt(&nonce, ct)
    }

    fn derive_key(&self, password: &SecretString, salt: &[u8]) -> Result<CipherKey, CipherError> {
        if self.is_aead_2022 {
            let psk = decode_psk(password.expose())?;
            CipherKey::derive_aead2022(self.cipher, &psk, salt)
        } else {
            CipherKey::derive_legacy(self.cipher, password, salt)
        }
    }
}

fn decode_psk(b64: &str) -> Result<Vec<u8>, CipherError> {
    ::base64::engine::Engine::decode(&::base64::engine::general_purpose::STANDARD, b64.trim())
        .or_else(|_| ::base64::engine::Engine::decode(&::base64::engine::general_purpose::URL_SAFE_NO_PAD, b64.trim()))
        .map_err(|_| CipherError::KeyLength)
}
