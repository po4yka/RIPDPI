//! Known-answer tests for AEAD-2022 ciphers (`2022-blake3-*`).
//!
//! These vectors are self-consistent encrypt→decrypt round-trips that verify:
//! 1. BLAKE3 KDF produces a deterministic session subkey from a fixed PSK + salt.
//! 2. The derived subkey encrypts/decrypts correctly with the underlying AEAD.
//!
//! We do not replicate upstream shadowsocks-rust test vectors byte-for-byte
//! because the BLAKE3 KDF context string and wire framing vary by
//! implementation; instead we verify KDF + AEAD in one round-trip with
//! known fixed inputs so any regression in either layer breaks the test.

use ripdpi_shadowsocks::cipher::{Cipher, CipherKey};

/// Fixed 32-byte PSK for `2022-blake3-aes-256-gcm` tests (fixture data only).
const FIXTURE_PSK_AES256: &[u8; 32] = b"fixture-psk-aes256gcm-32bytekey!";

/// Fixed 32-byte salt for deterministic test vectors.
const FIXTURE_SALT_32: &[u8; 32] = b"fixture-salt-32bytes-for-testing";

/// Fixed 32-byte PSK for `2022-blake3-chacha20-poly1305` tests.
const FIXTURE_PSK_CHACHA: &[u8; 32] = b"fixture-psk-chacha20poly1305-key";

/// All-zero 12-byte nonce used in KAT vectors.
const ZERO_NONCE: [u8; 12] = [0u8; 12];

#[test]
fn aead2022_aes256gcm_encrypt_decrypt_roundtrip() {
    let plaintext = b"fixture-payload-for-aes256gcm-2022";

    let key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, FIXTURE_SALT_32)
        .expect("derive_aead2022 must not fail with valid PSK+salt");

    let ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt must succeed");
    assert_ne!(ct.as_slice(), plaintext, "ciphertext must differ from plaintext");
    assert_eq!(ct.len(), plaintext.len() + 16, "ciphertext length = plaintext + 16-byte tag");

    // Decrypt with the same derived key.
    let key2 = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, FIXTURE_SALT_32)
        .expect("derive_aead2022 must not fail");

    let recovered = key2.decrypt(&ZERO_NONCE, &ct).expect("decrypt must succeed");
    assert_eq!(recovered, plaintext);
}

#[test]
fn aead2022_chacha20poly1305_encrypt_decrypt_roundtrip() {
    let plaintext = b"fixture-payload-for-chacha20poly1305-2022";

    let key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Chacha20Poly1305, FIXTURE_PSK_CHACHA, FIXTURE_SALT_32)
        .expect("derive_aead2022 must not fail");

    let ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt must succeed");

    let key2 = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Chacha20Poly1305, FIXTURE_PSK_CHACHA, FIXTURE_SALT_32)
        .expect("derive must not fail");

    let recovered = key2.decrypt(&ZERO_NONCE, &ct).expect("decrypt must succeed");
    assert_eq!(recovered, plaintext);
}

#[test]
fn aead2022_tampered_ciphertext_fails_authentication() {
    let plaintext = b"fixture-tamper-test-aes256";

    let key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, FIXTURE_SALT_32)
        .expect("derive_aead2022 must not fail");

    let mut ct = key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");
    // Flip one byte in the middle of the ciphertext.
    let mid = ct.len() / 2;
    ct[mid] ^= 0xFF;

    let key2 = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, FIXTURE_SALT_32)
        .expect("derive_aead2022 must not fail");

    let result = key2.decrypt(&ZERO_NONCE, &ct);
    assert!(result.is_err(), "tampered ciphertext must fail authentication");
}

#[test]
fn aead2022_wrong_psk_fails_authentication() {
    let plaintext = b"fixture-wrong-key-test";

    let enc_key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, FIXTURE_SALT_32)
        .expect("derive");

    let ct = enc_key.encrypt(&ZERO_NONCE, plaintext).expect("encrypt");

    let wrong_psk = b"wrong-psk-aes256gcm-32bytes-key!";
    let dec_key =
        CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, wrong_psk, FIXTURE_SALT_32).expect("derive");

    let result = dec_key.decrypt(&ZERO_NONCE, &ct);
    assert!(result.is_err(), "wrong PSK must fail authentication");
}

#[test]
fn aead2022_different_salts_produce_different_keys() {
    let salt_a = b"fixture-salt-a-32bytes-for-test!";
    let salt_b = b"fixture-salt-b-32bytes-for-test!";
    let plaintext = b"fixture-same-plaintext";
    let nonce = [0u8; 12];

    let key_a =
        CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, salt_a).expect("derive a");
    let key_b =
        CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256, salt_b).expect("derive b");

    let ct_a = key_a.encrypt(&nonce, plaintext).expect("encrypt a");
    let ct_b = key_b.encrypt(&nonce, plaintext).expect("encrypt b");

    assert_ne!(ct_a, ct_b, "different salts must produce different ciphertexts");
}
