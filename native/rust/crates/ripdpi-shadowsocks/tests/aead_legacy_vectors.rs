//! Known-answer tests for legacy AEAD ciphers (`aes-128-gcm`, `aes-256-gcm`,
//! `chacha20-ietf-poly1305`).
//!
//! Tests verify HKDF-SHA1 key derivation (EVP_BytesToKey + HKDF-SHA1) plus
//! AEAD encrypt/decrypt round-trips with fixed inputs.

use ripdpi_shadowsocks::cipher::{Cipher, CipherKey, SecretString};

fn fixture_secret(s: &str) -> SecretString {
    SecretString::new(s.to_owned())
}

/// 16-byte salt for aes-128-gcm.
const SALT_16: &[u8; 16] = b"fixture-salt-16b";

/// 32-byte salt for aes-256-gcm and chacha20-ietf-poly1305.
const SALT_32: &[u8; 32] = b"fixture-salt-32bytes-for-testing";

const ZERO_NONCE: [u8; 12] = [0u8; 12];

#[test]
fn legacy_aes128gcm_roundtrip() {
    let password = fixture_secret("fixture-password-1");
    let plaintext = b"fixture-payload-aes128gcm";

    let key = CipherKey::derive_legacy(Cipher::AeadAes128Gcm, &password, SALT_16).expect("derive_legacy must succeed");

    let ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");
    assert_eq!(ct.len(), plaintext.len() + 16);

    let key2 = CipherKey::derive_legacy(Cipher::AeadAes128Gcm, &password, SALT_16).expect("derive_legacy");
    let recovered = key2.decrypt(&ZERO_NONCE, &ct).expect("decrypt");
    assert_eq!(recovered, plaintext);
}

#[test]
fn legacy_aes256gcm_roundtrip() {
    let password = fixture_secret("fixture-password-2");
    let plaintext = b"fixture-payload-aes256gcm";

    let key = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, SALT_32).expect("derive_legacy");

    let ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");

    let key2 = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, SALT_32).expect("derive_legacy");
    let recovered = key2.decrypt(&ZERO_NONCE, &ct).expect("decrypt");
    assert_eq!(recovered, plaintext);
}

#[test]
fn legacy_chacha20ietf_roundtrip() {
    let password = fixture_secret("fixture-password-3");
    let plaintext = b"fixture-payload-chacha20ietf";

    let key = CipherKey::derive_legacy(Cipher::AeadChacha20IetfPoly1305, &password, SALT_32).expect("derive_legacy");

    let ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");

    let key2 = CipherKey::derive_legacy(Cipher::AeadChacha20IetfPoly1305, &password, SALT_32).expect("derive_legacy");
    let recovered = key2.decrypt(&ZERO_NONCE, &ct).expect("decrypt");
    assert_eq!(recovered, plaintext);
}

#[test]
fn legacy_tampered_ciphertext_fails() {
    let password = fixture_secret("fixture-tamper-password");
    let plaintext = b"fixture-tamper-legacy";

    let key = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, SALT_32).expect("derive_legacy");

    let mut ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");
    ct[0] ^= 0xFF;

    let key2 = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, SALT_32).expect("derive_legacy");
    assert!(key2.decrypt(&ZERO_NONCE, &ct).is_err());
}

#[test]
fn legacy_wrong_password_fails() {
    let password = fixture_secret("fixture-correct-password");
    let wrong = fixture_secret("fixture-wrong-password");
    let plaintext = b"fixture-wrong-pw-test";

    let key = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, SALT_32).expect("derive_legacy");
    let ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");

    let key2 = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &wrong, SALT_32).expect("derive_legacy");
    assert!(key2.decrypt(&ZERO_NONCE, &ct).is_err());
}

#[test]
fn legacy_different_salts_differ() {
    let password = fixture_secret("fixture-password-4");
    let salt_a = b"fixture-salt-a-32bytes-padding!!";
    let salt_b = b"fixture-salt-b-32bytes-padding!!";
    let plaintext = b"fixture-diff-salt";
    let nonce = [0u8; 12];

    let key_a = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, salt_a).expect("derive_legacy a");
    let key_b = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, salt_b).expect("derive_legacy b");

    let ct_a = key_a.encrypt(&nonce, plaintext).expect("encrypt a");
    let ct_b = key_b.encrypt(&nonce, plaintext).expect("encrypt b");
    assert_ne!(ct_a, ct_b);
}
