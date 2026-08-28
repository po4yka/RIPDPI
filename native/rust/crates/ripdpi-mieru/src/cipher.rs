//! Mieru cipher layer — XChaCha20-Poly1305 with time-rotated PBKDF2 keys.
//!
//! Byte-exact transcription of upstream `pkg/cipher/api.go`; see `PROTOCOL.md`
//! §1. All hashing, PBKDF2 and secure randomness use `ring` (already a
//! workspace dependency); only the XChaCha20-Poly1305 AEAD (which `ring` does
//! not provide at a 24-byte nonce) uses the `chacha20poly1305` crate.

use std::num::NonZeroU32;

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use ring::digest::{SHA256, digest};
use ring::pbkdf2;
use ring::rand::{SecureRandom, SystemRandom};

use crate::error::{MieruError, Result};

/// AEAD key length (bytes).
pub(crate) const KEY_LEN: usize = 32;
/// AEAD nonce length (bytes) — XChaCha20-Poly1305 uses a 24-byte nonce.
pub(crate) const NONCE_LEN: usize = 24;
/// AEAD authentication tag length (bytes).
pub(crate) const TAG_LEN: usize = 16;
/// PBKDF2-HMAC-SHA256 iteration count (upstream constant).
const PBKDF2_ITERS: u32 = 64;
/// Key-rotation window: the time salt is rounded to the nearest 2 minutes.
pub(crate) const SALT_ROUND_SECS: i64 = 120;

fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    // ring has no incremental-free one-shot over multiple slices, so join first.
    let total: usize = parts.iter().map(|p| p.len()).sum();
    let mut buf = Vec::with_capacity(total);
    for p in parts {
        buf.extend_from_slice(p);
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(digest(&SHA256, &buf).as_ref());
    out
}

/// `hashedPassword = SHA256(password || 0x00 || username)`.
fn hashed_password(password: &[u8], username: &[u8]) -> [u8; 32] {
    sha256(&[password, &[0x00], username])
}

/// `timeSalt = SHA256(be_u64(round_to_nearest(unix_secs, 2min)))`.
fn time_salt(unix_secs: i64) -> [u8; 32] {
    let rounded = (unix_secs + SALT_ROUND_SECS / 2).div_euclid(SALT_ROUND_SECS) * SALT_ROUND_SECS;
    #[allow(clippy::cast_sign_loss)] // negative epochs are not representable on device; rounded >= 0 in practice.
    let be = (rounded as u64).to_be_bytes();
    sha256(&[&be])
}

/// Derive the 32-byte session key for the 2-minute window at
/// `unix_secs + window_offset * 2min`. The client uses `window_offset == 0`; a
/// server tolerating clock skew tries `-1, 0, 1`.
pub(crate) fn derive_key(password: &[u8], username: &[u8], unix_secs: i64, window_offset: i64) -> [u8; KEY_LEN] {
    let hashed = hashed_password(password, username);
    let salt = time_salt(unix_secs + window_offset * SALT_ROUND_SECS);
    let mut key = [0u8; KEY_LEN];
    pbkdf2::derive(
        pbkdf2::PBKDF2_HMAC_SHA256,
        NonZeroU32::new(PBKDF2_ITERS).expect("PBKDF2 iteration count is non-zero"),
        &salt,
        &hashed,
        &mut key,
    );
    key
}

/// A 24-byte AEAD nonce that increments (big-endian) per AEAD operation,
/// matching upstream `increaseNonce`.
#[derive(Clone)]
pub(crate) struct Nonce {
    bytes: [u8; NONCE_LEN],
}

impl Nonce {
    /// A fresh random nonce whose last 4 bytes are stamped with the user-lookup
    /// hash `SHA256(username || nonce[0..16])[0..4]` (upstream user-lookup
    /// acceleration).
    pub(crate) fn random(username: &[u8], rng: &SystemRandom) -> Result<Self> {
        let mut bytes = [0u8; NONCE_LEN];
        rng.fill(&mut bytes).map_err(|_| MieruError::Crypto("nonce rng"))?;
        Self::stamp_user(&mut bytes, username);
        Ok(Self { bytes })
    }

    fn stamp_user(bytes: &mut [u8; NONCE_LEN], username: &[u8]) {
        let hash = sha256(&[username, &bytes[0..16]]);
        bytes[20..24].copy_from_slice(&hash[0..4]);
    }

    pub(crate) fn from_bytes(bytes: [u8; NONCE_LEN]) -> Self {
        Self { bytes }
    }

    pub(crate) fn as_bytes(&self) -> &[u8; NONCE_LEN] {
        &self.bytes
    }

    /// Increment the nonce by one, big-endian, matching upstream
    /// `increaseNonce` (carry from the last byte toward the first).
    pub(crate) fn increment(&mut self) {
        for byte in self.bytes.iter_mut().rev() {
            *byte = byte.wrapping_add(1);
            if *byte != 0 {
                break;
            }
        }
    }
}

/// AEAD-seal `plaintext` under `key`/`nonce`; returns ciphertext ‖ 16-byte tag.
pub(crate) fn seal(key: &[u8; KEY_LEN], nonce: &Nonce, plaintext: &[u8]) -> Result<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).map_err(|_| MieruError::Crypto("key"))?;
    let nonce = <&XNonce>::try_from(nonce.as_bytes().as_slice()).map_err(|_| MieruError::Crypto("nonce"))?;
    cipher.encrypt(nonce, plaintext).map_err(|_| MieruError::Crypto("seal"))
}

/// AEAD-open `ciphertext` (ciphertext ‖ 16-byte tag) under `key`/`nonce`.
pub(crate) fn open(key: &[u8; KEY_LEN], nonce: &Nonce, ciphertext: &[u8]) -> Result<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).map_err(|_| MieruError::Crypto("key"))?;
    let nonce = <&XNonce>::try_from(nonce.as_bytes().as_slice()).map_err(|_| MieruError::Crypto("nonce"))?;
    cipher.decrypt(nonce, ciphertext).map_err(|_| MieruError::Crypto("open"))
}

#[cfg(test)]
mod tests {
    use super::*;

    // Fixed, deterministic vectors. These pin the byte-exact derivation so a
    // regression (or an accidental algorithm change) is caught even though we
    // cannot test against a live mieru server. Values are derived from this
    // implementation; they assert internal determinism + the documented shape.

    #[test]
    fn hashed_password_is_sha256_of_password_zero_username() {
        // SHA256("pw" || 0x00 || "user") — recomputed independently here.
        let expected = sha256(&[b"pw", &[0x00], b"user"]);
        assert_eq!(hashed_password(b"pw", b"user"), expected);
        // Different separator placement must change the hash (guards the 0x00).
        assert_ne!(hashed_password(b"pw", b"user"), sha256(&[b"pwuser"]));
    }

    #[test]
    fn time_salt_is_deterministic_and_window_sensitive() {
        // Same input => same salt; a full window away => different salt. (Nearest
        // rounding means two times <2min apart may still straddle a boundary —
        // that is exactly why the server tries 3 windows.)
        assert_eq!(time_salt(1_000_000_000), time_salt(1_000_000_000));
        assert_ne!(time_salt(1_000_000_000), time_salt(1_000_000_000 + SALT_ROUND_SECS));
    }

    #[test]
    fn derive_key_is_deterministic_and_supports_skew_recovery() {
        let k0 = derive_key(b"pw", b"user", 1_000_000_000, 0);
        assert_eq!(k0, derive_key(b"pw", b"user", 1_000_000_000, 0), "deterministic");
        assert_ne!(k0, derive_key(b"pw", b"user", 1_000_000_000, 1), "adjacent window => different key");
        // A server one window ahead, probing offset -1, recovers the client's key
        // regardless of where nearest-rounding placed the boundary.
        let server_view = derive_key(b"pw", b"user", 1_000_000_000 + SALT_ROUND_SECS, -1);
        assert_eq!(server_view, k0, "skew window recovery");
    }

    #[test]
    fn nonce_last_four_bytes_are_user_hash() {
        let mut bytes = [7u8; NONCE_LEN];
        Nonce::stamp_user(&mut bytes, b"alice");
        let expected = sha256(&[b"alice", &[7u8; 16]]);
        assert_eq!(&bytes[20..24], &expected[0..4]);
    }

    #[test]
    fn nonce_increment_is_big_endian_with_carry() {
        // Pinned upstream cipher/cipher_test.go's carry vector, extended to
        // XChaCha20's full nonce width.
        let mut bytes = [0; NONCE_LEN];
        bytes[NONCE_LEN - 2..].fill(0xff);
        let mut n = Nonce::from_bytes(bytes);
        n.increment();
        let mut expected = [0; NONCE_LEN];
        expected[NONCE_LEN - 3] = 1;
        assert_eq!(n.as_bytes(), &expected);
    }

    #[test]
    fn seal_open_round_trips_and_rejects_tampering() {
        let key = derive_key(b"pw", b"user", 1_000_000_000, 0);
        let nonce = Nonce::from_bytes([1u8; NONCE_LEN]);
        let ct = seal(&key, &nonce, b"hello mieru").expect("seal");
        assert_eq!(ct.len(), b"hello mieru".len() + TAG_LEN);
        assert_eq!(open(&key, &nonce, &ct).expect("open"), b"hello mieru");
        // Wrong nonce fails.
        let mut bad = nonce.clone();
        bad.increment();
        assert!(open(&key, &bad, &ct).is_err());
        // Tampered ciphertext fails.
        let mut tampered = ct.clone();
        tampered[0] ^= 0x01;
        assert!(open(&key, &nonce, &tampered).is_err());
    }
}
