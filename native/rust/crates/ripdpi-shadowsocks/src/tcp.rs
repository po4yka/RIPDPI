//! Shadowsocks TCP framing — encrypt/decrypt with AEAD chunked framing.
//!
//! # Wire format (SIP004 / SIP022 TCP)
//!
//! ```text
//! [salt (salt_len bytes)]
//! [chunk 0]
//! [chunk 1]
//! ...
//!
//! Each chunk:
//! [2-byte big-endian length ciphertext + tag (2 + tag_len bytes)]
//! [payload ciphertext + tag (length_plaintext + tag_len bytes)]
//! ```
//!
//! The nonce for chunk N is the 96-bit little-endian representation of N,
//! incremented by 1 for each chunk (counter mode).

use rand::RngExt;

use crate::cipher::{Cipher, CipherError, CipherKey, SecretString};

/// Maximum plaintext payload per SIP004 chunk.
const SIP004_MAX_CHUNK_LEN: usize = 0x3FFF;

/// Maximum plaintext payload per SIP022 chunk.
const SIP022_MAX_CHUNK_LEN: usize = 0xFFFF;

/// Shadowsocks TCP stream framer/deframer.
///
/// Encrypts plaintext into chunked AEAD frames (with a leading salt) and
/// decrypts them back. This type does not own a network socket — the caller
/// supplies raw byte slices and receives `Vec<u8>` back. Tokio integration
/// lives one layer up in the adapter crates.
pub struct TcpStream {
    cipher: Cipher,
    key: CipherKey,
    counter: u64,
}

impl TcpStream {
    /// Creates a new framer for the given cipher and pre-shared key material.
    ///
    /// For AEAD-2022 ciphers `password_or_psk` must be the raw binary PSK
    /// (base64-decoded from the URI `password` field). For legacy AEAD ciphers
    /// it is the UTF-8 password string wrapped in [`SecretString`].
    pub fn new_encrypt(
        cipher: Cipher,
        password: &SecretString,
        is_aead_2022: bool,
    ) -> Result<(Self, Vec<u8>), CipherError> {
        let mut salt = vec![0u8; cipher.salt_len()];
        rand::rng().fill(&mut salt[..]);
        let key = if is_aead_2022 {
            let psk = base64::decode_psk(password.expose())?;
            CipherKey::derive_aead2022(cipher, &psk, &salt)?
        } else {
            CipherKey::derive_legacy(cipher, password, &salt)?
        };
        Ok((Self { cipher, key, counter: 0 }, salt))
    }

    /// Creates a new deframer given the `salt` read from the wire.
    pub fn new_decrypt(
        cipher: Cipher,
        password: &SecretString,
        salt: &[u8],
        is_aead_2022: bool,
    ) -> Result<Self, CipherError> {
        let key = if is_aead_2022 {
            let psk = base64::decode_psk(password.expose())?;
            CipherKey::derive_aead2022(cipher, &psk, salt)?
        } else {
            CipherKey::derive_legacy(cipher, password, salt)?
        };
        Ok(Self { cipher, key, counter: 0 })
    }

    /// Encrypts `plaintext` into one or more AEAD chunks (without the leading
    /// salt — the caller prepends it from [`new_encrypt`]).
    pub fn encrypt_payload(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        let mut out = Vec::new();
        let mut offset = 0;
        let max_chunk_len = max_chunk_len(self.cipher);
        while offset < plaintext.len() || (plaintext.is_empty() && offset == 0) {
            let end = (offset + max_chunk_len).min(plaintext.len());
            let chunk = &plaintext[offset..end];
            let len_bytes = (chunk.len() as u16).to_be_bytes();

            // Encrypt 2-byte length field.
            let nonce = counter_nonce(self.counter);
            self.counter += 1;
            let len_ct = self.key.encrypt(&nonce, &len_bytes)?;
            out.extend_from_slice(&len_ct);

            // Encrypt payload.
            let nonce2 = counter_nonce(self.counter);
            self.counter += 1;
            let payload_ct = self.key.encrypt(&nonce2, chunk)?;
            out.extend_from_slice(&payload_ct);

            offset = end;
            if plaintext.is_empty() {
                break;
            }
        }
        Ok(out)
    }

    /// Decrypts a single AEAD chunk from `data` starting at `offset`.
    ///
    /// Returns `(plaintext, bytes_consumed)` on success. Returns `None` when
    /// there are not yet enough bytes for a complete chunk (caller should buffer
    /// and retry). Returns `Err` on authentication failure.
    pub fn decrypt_chunk(&mut self, data: &[u8], offset: usize) -> Result<Option<(Vec<u8>, usize)>, CipherError> {
        let tag_len = self.cipher.tag_len();
        let len_frame = 2 + tag_len;

        if data.len() < offset + len_frame {
            return Ok(None);
        }

        // Decrypt length field.
        let nonce = counter_nonce(self.counter);
        let len_ct = &data[offset..offset + len_frame];
        let len_bytes = self.key.decrypt(&nonce, len_ct)?;
        let payload_len = u16::from_be_bytes([len_bytes[0], len_bytes[1]]) as usize;
        let payload_frame = payload_len + tag_len;

        if data.len() < offset + len_frame + payload_frame {
            return Ok(None);
        }

        self.counter += 1;
        let nonce2 = counter_nonce(self.counter);
        self.counter += 1;
        let payload_ct = &data[offset + len_frame..offset + len_frame + payload_frame];
        let plaintext = self.key.decrypt(&nonce2, payload_ct)?;

        Ok(Some((plaintext, len_frame + payload_frame)))
    }
}

/// Builds a 12-byte little-endian nonce from a 64-bit counter (upper 4 bytes = 0).
fn counter_nonce(counter: u64) -> Vec<u8> {
    let mut nonce = [0u8; 12];
    nonce[..8].copy_from_slice(&counter.to_le_bytes());
    nonce.to_vec()
}

fn max_chunk_len(cipher: Cipher) -> usize {
    if cipher.is_aead_2022() { SIP022_MAX_CHUNK_LEN } else { SIP004_MAX_CHUNK_LEN }
}

/// Minimal base64 decode helper scoped to this module.
mod base64 {
    use crate::cipher::CipherError;

    pub fn decode_psk(b64: &str) -> Result<Vec<u8>, CipherError> {
        ::base64::engine::Engine::decode(&::base64::engine::general_purpose::STANDARD, b64.trim())
            .or_else(|_| {
                ::base64::engine::Engine::decode(&::base64::engine::general_purpose::URL_SAFE_NO_PAD, b64.trim())
            })
            .map_err(|_| CipherError::KeyLength)
    }
}
