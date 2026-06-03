//! Cipher enum and key derivation for Shadowsocks AEAD-2022 and legacy AEAD.
//!
//! # AEAD-2022 key derivation (SIP022)
//!
//! For `2022-blake3-*` ciphers the pre-shared key is fixed-length raw binary
//! (not hex, not base64 in the AEAD sense — the password field in the URI is
//! base64 and decoded to raw bytes before use). Session-subkeys are derived
//! with BLAKE3 KDF:
//!
//! ```text
//! session_subkey = blake3::derive_key("shadowsocks 2022 session subkey", psk || session_salt)
//! ```
//!
//! # Legacy AEAD key derivation (SIP004 / OpenSSL EVP_BytesToKey)
//!
//! Legacy AEAD ciphers derive the key with the MD5-based `hkdf_sha1` approach
//! from the original spec: the password string is run through repeated MD5 to
//! fill `key_len` bytes, then a random `salt` is mixed in with HKDF-SHA1 to
//! produce the session subkey.

use aes_gcm::{
    Aes128Gcm, Aes256Gcm,
    aead::{Aead, KeyInit, Payload},
};
use chacha20poly1305::ChaCha20Poly1305;
use hkdf::Hkdf;
use sha1::Sha1;

/// A Shadowsocks password that does not leak via `Debug` or `Display`.
///
/// Use [`SecretString::expose`] in the narrow scope where the raw bytes are
/// needed for key derivation — never outside that scope.
pub struct SecretString(String);

impl SecretString {
    /// Wraps a raw password string.
    pub fn new(password: String) -> Self {
        Self(password)
    }

    /// Returns the raw password bytes for key derivation only.
    ///
    /// Call this only in key-derivation code; never pass the result to
    /// a formatter, logger, or serializer.
    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for SecretString {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SecretString(<redacted>)")
    }
}

impl std::fmt::Display for SecretString {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("<redacted>")
    }
}

/// A decoded SIP022 pre-shared key whose length has been validated against the selected AEAD-2022 cipher.
pub struct PresharedKey(Vec<u8>);

impl PresharedKey {
    /// Decodes a SIP022 base64 PSK and validates that its byte length matches `cipher`.
    pub fn from_base64(cipher: Cipher, encoded: &str) -> Result<Self, CipherError> {
        if !cipher.is_aead_2022() {
            return Err(CipherError::Unsupported(format!("{cipher:?}")));
        }
        let key = decode_base64_psk(encoded)?;
        if key.len() != cipher.key_len() {
            return Err(CipherError::KeyLength);
        }
        Ok(Self(key))
    }

    /// Returns the raw PSK bytes for SIP022 BLAKE3 subkey derivation.
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

impl std::fmt::Debug for PresharedKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("PresharedKey(<redacted>)")
    }
}

/// Errors produced by cipher operations.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum CipherError {
    /// The cipher name is a stream cipher or otherwise unsupported.
    ///
    /// Stream ciphers (`rc4`, `aes-cfb`, `chacha20`, `salsa20`, etc.) are
    /// rejected at the type level — they never appear in [`Cipher`].
    #[error("unsupported cipher: {0}")]
    Unsupported(String),

    /// The key material has the wrong length for this cipher.
    #[error("invalid key length")]
    KeyLength,

    /// The nonce / IV has the wrong length for this cipher.
    #[error("invalid IV length")]
    IvLength,

    /// AEAD tag verification failed (decryption error).
    #[error("AEAD authentication failed")]
    AeadFailed,

    /// Packet replay or stale timestamp detected.
    #[error("packet replay detected")]
    Replay,

    /// Packet authenticated but failed protocol-level validation.
    #[error("invalid packet: {0}")]
    InvalidPacket(&'static str),
}

/// AEAD-only cipher set supported by `ripdpi-shadowsocks`.
///
/// Stream ciphers are deliberately absent from this enum — attempting to
/// construct one via [`Cipher::from_name`] returns
/// [`CipherError::Unsupported`], never a variant here.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cipher {
    /// `2022-blake3-aes-128-gcm` — AEAD-2022 with AES-128-GCM (SIP022).
    Aead2022Blake3Aes128Gcm,
    /// `2022-blake3-aes-256-gcm` — AEAD-2022 with AES-256-GCM (SIP022).
    Aead2022Blake3Aes256Gcm,
    /// `2022-blake3-chacha20-poly1305` — AEAD-2022 with ChaCha20-Poly1305 (SIP022).
    Aead2022Blake3Chacha20Poly1305,
    /// `aes-128-gcm` — legacy AEAD (SIP004).
    AeadAes128Gcm,
    /// `aes-256-gcm` — legacy AEAD (SIP004).
    AeadAes256Gcm,
    /// `chacha20-ietf-poly1305` — legacy AEAD (SIP004).
    AeadChacha20IetfPoly1305,
}

/// Stream cipher names that must be rejected rather than silently accepted.
const STREAM_CIPHER_NAMES: &[&str] = &[
    "rc4",
    "rc4-md5",
    "aes-128-cfb",
    "aes-192-cfb",
    "aes-256-cfb",
    "aes-128-ctr",
    "aes-192-ctr",
    "aes-256-ctr",
    "camellia-128-cfb",
    "camellia-192-cfb",
    "camellia-256-cfb",
    "chacha20",
    "chacha20-ietf",
    "salsa20",
    "xchacha20",
    "bf-cfb",
    "cast5-cfb",
    "des-cfb",
    "idea-cfb",
    "rc2-cfb",
    "seed-cfb",
];

impl Cipher {
    /// Parses a Shadowsocks cipher name into a [`Cipher`] variant.
    ///
    /// Returns [`CipherError::Unsupported`] for any stream cipher name or any
    /// other unrecognised string.
    pub fn from_name(name: &str) -> Result<Self, CipherError> {
        let lower = name.trim().to_ascii_lowercase();

        // Explicit rejection of all known stream cipher names.
        if STREAM_CIPHER_NAMES.contains(&lower.as_str()) {
            return Err(CipherError::Unsupported(name.to_owned()));
        }

        match lower.as_str() {
            "2022-blake3-aes-128-gcm" => Ok(Self::Aead2022Blake3Aes128Gcm),
            "2022-blake3-aes-256-gcm" => Ok(Self::Aead2022Blake3Aes256Gcm),
            "2022-blake3-chacha20-poly1305" => Ok(Self::Aead2022Blake3Chacha20Poly1305),
            "aes-128-gcm" => Ok(Self::AeadAes128Gcm),
            "aes-256-gcm" => Ok(Self::AeadAes256Gcm),
            "chacha20-ietf-poly1305" => Ok(Self::AeadChacha20IetfPoly1305),
            other => Err(CipherError::Unsupported(other.to_owned())),
        }
    }

    /// Key length in bytes for this cipher.
    pub fn key_len(self) -> usize {
        match self {
            Self::Aead2022Blake3Aes128Gcm | Self::AeadAes128Gcm => 16,
            Self::Aead2022Blake3Aes256Gcm | Self::AeadAes256Gcm => 32,
            Self::Aead2022Blake3Chacha20Poly1305 | Self::AeadChacha20IetfPoly1305 => 32,
        }
    }

    /// Salt length in bytes (random bytes prepended to every TCP/UDP message).
    pub fn salt_len(self) -> usize {
        match self {
            Self::Aead2022Blake3Aes128Gcm | Self::AeadAes128Gcm => 16,
            _ => 32,
        }
    }

    /// Nonce length in bytes for this cipher's AEAD.
    pub fn nonce_len(self) -> usize {
        // All supported AEAD ciphers use 12-byte nonces.
        12
    }

    /// AEAD tag length in bytes (always 16 for the supported set).
    pub fn tag_len(self) -> usize {
        16
    }

    /// Whether this cipher uses AEAD-2022 (BLAKE3) key derivation.
    pub fn is_aead_2022(self) -> bool {
        matches!(
            self,
            Self::Aead2022Blake3Aes128Gcm | Self::Aead2022Blake3Aes256Gcm | Self::Aead2022Blake3Chacha20Poly1305
        )
    }
}

/// Derived session key ready for AEAD operations.
pub struct CipherKey {
    pub cipher: Cipher,
    pub key: Vec<u8>,
}

impl CipherKey {
    /// Derives the session key for AEAD-2022 ciphers using BLAKE3 KDF.
    ///
    /// `psk` is the raw binary pre-shared key (decoded from base64 in the URI).
    /// `salt` is the random session salt (32 bytes for AEAD-2022).
    pub fn derive_aead2022(cipher: Cipher, psk: &[u8], salt: &[u8]) -> Result<Self, CipherError> {
        if !cipher.is_aead_2022() {
            return Err(CipherError::Unsupported(format!("{cipher:?}")));
        }
        if psk.len() != cipher.key_len() {
            return Err(CipherError::KeyLength);
        }
        if salt.len() != cipher.salt_len() {
            return Err(CipherError::IvLength);
        }
        // BLAKE3 KDF: context string is "shadowsocks 2022 session subkey"
        // input key material = psk || salt
        let mut ikm = Vec::with_capacity(psk.len() + salt.len());
        ikm.extend_from_slice(psk);
        ikm.extend_from_slice(salt);
        let key = blake3::derive_key("shadowsocks 2022 session subkey", &ikm);
        Ok(Self { cipher, key: key[..cipher.key_len()].to_vec() })
    }

    /// Derives the SIP022 UDP session key from the 8-byte session ID.
    pub fn derive_aead2022_udp(cipher: Cipher, psk: &[u8], session_id: &[u8; 8]) -> Result<Self, CipherError> {
        if !cipher.is_aead_2022() {
            return Err(CipherError::Unsupported(format!("{cipher:?}")));
        }
        if psk.len() != cipher.key_len() {
            return Err(CipherError::KeyLength);
        }
        let mut ikm = Vec::with_capacity(psk.len() + session_id.len());
        ikm.extend_from_slice(psk);
        ikm.extend_from_slice(session_id);
        let key = blake3::derive_key("shadowsocks 2022 session subkey", &ikm);
        Ok(Self { cipher, key: key[..cipher.key_len()].to_vec() })
    }

    /// Derives the session key for legacy AEAD ciphers using HKDF-SHA1.
    ///
    /// `password` is the raw password string from the URI.
    /// `salt` is the random session salt.
    pub fn derive_legacy(cipher: Cipher, password: &SecretString, salt: &[u8]) -> Result<Self, CipherError> {
        if cipher.is_aead_2022() {
            return Err(CipherError::Unsupported(format!("{cipher:?}")));
        }
        if salt.len() != cipher.salt_len() {
            return Err(CipherError::IvLength);
        }
        // Step 1: derive `key_len` bytes from password using EVP_BytesToKey (MD5).
        let ikm = evp_bytes_to_key(password.expose().as_bytes(), cipher.key_len());
        // Step 2: HKDF-SHA1 with the session salt as salt, "ss-subkey" as info.
        let hk = Hkdf::<Sha1>::new(Some(salt), &ikm);
        let mut key = vec![0u8; cipher.key_len()];
        hk.expand(b"ss-subkey", &mut key).map_err(|_| CipherError::KeyLength)?;
        Ok(Self { cipher, key })
    }

    /// Encrypts `plaintext` with the given `nonce`.
    pub fn encrypt(&self, nonce: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        if nonce.len() != self.cipher.nonce_len() {
            return Err(CipherError::IvLength);
        }
        dispatch_encrypt(self.cipher, &self.key, nonce, plaintext)
    }

    /// Decrypts `ciphertext` (which includes the tag) with the given `nonce`.
    pub fn decrypt(&self, nonce: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, CipherError> {
        if nonce.len() != self.cipher.nonce_len() {
            return Err(CipherError::IvLength);
        }
        dispatch_decrypt(self.cipher, &self.key, nonce, ciphertext)
    }
}

fn dispatch_encrypt(cipher: Cipher, key: &[u8], nonce: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
    use aes_gcm::aead::generic_array::GenericArray;
    match cipher {
        Cipher::Aead2022Blake3Aes128Gcm | Cipher::AeadAes128Gcm => {
            let k = aes_gcm::Key::<Aes128Gcm>::from_slice(key);
            let c = Aes128Gcm::new(k);
            let n = GenericArray::from_slice(nonce);
            c.encrypt(n, Payload { msg: plaintext, aad: b"" }).map_err(|_| CipherError::AeadFailed)
        }
        Cipher::Aead2022Blake3Aes256Gcm | Cipher::AeadAes256Gcm => {
            let k = aes_gcm::Key::<Aes256Gcm>::from_slice(key);
            let c = Aes256Gcm::new(k);
            let n = GenericArray::from_slice(nonce);
            c.encrypt(n, Payload { msg: plaintext, aad: b"" }).map_err(|_| CipherError::AeadFailed)
        }
        Cipher::Aead2022Blake3Chacha20Poly1305 | Cipher::AeadChacha20IetfPoly1305 => {
            let k = chacha20poly1305::Key::from_slice(key);
            let c = ChaCha20Poly1305::new(k);
            let n = chacha20poly1305::Nonce::from_slice(nonce);
            c.encrypt(n, Payload { msg: plaintext, aad: b"" }).map_err(|_| CipherError::AeadFailed)
        }
    }
}

fn dispatch_decrypt(cipher: Cipher, key: &[u8], nonce: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, CipherError> {
    use aes_gcm::aead::generic_array::GenericArray;
    match cipher {
        Cipher::Aead2022Blake3Aes128Gcm | Cipher::AeadAes128Gcm => {
            let k = aes_gcm::Key::<Aes128Gcm>::from_slice(key);
            let c = Aes128Gcm::new(k);
            let n = GenericArray::from_slice(nonce);
            c.decrypt(n, Payload { msg: ciphertext, aad: b"" }).map_err(|_| CipherError::AeadFailed)
        }
        Cipher::Aead2022Blake3Aes256Gcm | Cipher::AeadAes256Gcm => {
            let k = aes_gcm::Key::<Aes256Gcm>::from_slice(key);
            let c = Aes256Gcm::new(k);
            let n = GenericArray::from_slice(nonce);
            c.decrypt(n, Payload { msg: ciphertext, aad: b"" }).map_err(|_| CipherError::AeadFailed)
        }
        Cipher::Aead2022Blake3Chacha20Poly1305 | Cipher::AeadChacha20IetfPoly1305 => {
            let k = chacha20poly1305::Key::from_slice(key);
            let c = ChaCha20Poly1305::new(k);
            let n = chacha20poly1305::Nonce::from_slice(nonce);
            c.decrypt(n, Payload { msg: ciphertext, aad: b"" }).map_err(|_| CipherError::AeadFailed)
        }
    }
}

fn decode_base64_psk(encoded: &str) -> Result<Vec<u8>, CipherError> {
    use base64::engine::Engine;

    let trimmed = encoded.trim();
    base64::engine::general_purpose::STANDARD.decode(trimmed).map_err(|_| CipherError::KeyLength)
}

/// OpenSSL `EVP_BytesToKey` with MD5 and no salt (Shadowsocks legacy key derivation).
///
/// Produces `key_len` bytes from the password by chaining MD5 digests.
///
/// MD5 here is PROTOCOL-MANDATED, not a free cryptographic choice: the
/// Shadowsocks wire format defines the master-key KDF as OpenSSL
/// `EVP_BytesToKey(MD5, password, key_len)` with no salt and one iteration.
/// Every interoperable Shadowsocks implementation derives the key this exact
/// way, so swapping in SHA-256/HKDF would break compatibility with all
/// existing servers. This is key derivation, NOT message integrity or any
/// security-sensitive hashing — MD5's collision/preimage weaknesses are not
/// in scope, because the digest output is only stretched into key material,
/// never used as an authentication tag. Do NOT "upgrade" this hash.
fn evp_bytes_to_key(password: &[u8], key_len: usize) -> Vec<u8> {
    let mut result = Vec::with_capacity(key_len);
    let mut prev: Vec<u8> = Vec::new();
    while result.len() < key_len {
        let mut ctx = md5_digest(&prev, password);
        result.extend_from_slice(&ctx);
        prev = ctx.to_vec();
        ctx.fill(0);
    }
    result.truncate(key_len);
    result
}

fn md5_digest(prev: &[u8], password: &[u8]) -> [u8; 16] {
    let mut input = Vec::with_capacity(prev.len() + password.len());
    input.extend_from_slice(prev);
    input.extend_from_slice(password);
    md5_raw(&input)
}

/// MD5 implementation using the `md5` workspace crate (already present).
///
/// Used ONLY by the protocol-mandated `evp_bytes_to_key` legacy KDF above —
/// not for message integrity or any security-sensitive digest. See that
/// function's doc comment for why MD5 is required and must not be replaced.
fn md5_raw(data: &[u8]) -> [u8; 16] {
    let digest = md5::compute(data);
    digest.0
}
