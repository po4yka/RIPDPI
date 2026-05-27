//! Known-answer tests for legacy AEAD ciphers (`aes-128-gcm`, `aes-256-gcm`,
//! `chacha20-ietf-poly1305`).
//!
//! Tests verify HKDF-SHA1 key derivation (EVP_BytesToKey + HKDF-SHA1) plus
//! AEAD encrypt/decrypt round-trips with fixed inputs.

use ripdpi_shadowsocks::cipher::{Cipher, CipherError, CipherKey, SecretString};

fn fixture_secret(s: &str) -> SecretString {
    SecretString::new(s.to_owned())
}

fn decode_hex(value: &str) -> Vec<u8> {
    hex::decode(value).expect("fixture hex must decode")
}

/// 16-byte salt for aes-128-gcm.
const SALT_16: &[u8; 16] = b"fixture-salt-16b";

/// 32-byte salt for aes-256-gcm and chacha20-ietf-poly1305.
const SALT_32: &[u8; 32] = b"fixture-salt-32bytes-for-testing";

const ZERO_NONCE: [u8; 12] = [0u8; 12];

const SIP004_KAT_PASSWORD: &str = "correct horse battery staple";
const SIP004_KAT_SALT_16: &[u8; 16] = b"\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f";
const SIP004_KAT_SALT_32: &[u8; 32] = b" !\"#$%&'()*+,-./0123456789:;<=>?";
const SIP004_KAT_NONCE: [u8; 12] = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b];
const SIP004_KAT_PLAINTEXT: &[u8] = b"RIPDPI shadowsocks SIP004 KAT";

#[test]
fn sip004_aes128gcm_matches_independent_kdf_and_aead_vector() {
    let password = fixture_secret(SIP004_KAT_PASSWORD);
    let key = CipherKey::derive_legacy(Cipher::AeadAes128Gcm, &password, SIP004_KAT_SALT_16).expect("derive legacy");

    assert_eq!(hex::encode(&key.key), "741ba530dc7cb08412118302a3833ce4");
    let ciphertext = key.encrypt(&SIP004_KAT_NONCE, SIP004_KAT_PLAINTEXT).expect("encrypt");
    assert_eq!(
        ciphertext,
        decode_hex("1f851d140360d510c8c53d5badd86f9c393fd2bc6a524cf59be74657480c4a3dd4ea732eabdd2dddce65609cc3"),
    );
    assert_eq!(key.decrypt(&SIP004_KAT_NONCE, &ciphertext).expect("decrypt"), SIP004_KAT_PLAINTEXT);
}

#[test]
fn sip004_aes256gcm_matches_independent_kdf_and_aead_vector() {
    let password = fixture_secret(SIP004_KAT_PASSWORD);
    let key = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, SIP004_KAT_SALT_32).expect("derive legacy");

    assert_eq!(hex::encode(&key.key), "28155bba8880a98e1ba58f9da9e7a2e6ca87796e1341abfbcf40e64ee2df3e40");
    let ciphertext = key.encrypt(&SIP004_KAT_NONCE, SIP004_KAT_PLAINTEXT).expect("encrypt");
    assert_eq!(
        ciphertext,
        decode_hex("4740660f75a6718dbc27093d5972890579549057cb17a5d060fbd6c4399350154c64e5089c24ace2fe46540d16"),
    );
    assert_eq!(key.decrypt(&SIP004_KAT_NONCE, &ciphertext).expect("decrypt"), SIP004_KAT_PLAINTEXT);
}

#[test]
fn sip004_chacha20_ietf_poly1305_matches_independent_kdf_and_aead_vector() {
    let password = fixture_secret(SIP004_KAT_PASSWORD);
    let key = CipherKey::derive_legacy(Cipher::AeadChacha20IetfPoly1305, &password, SIP004_KAT_SALT_32)
        .expect("derive legacy");

    assert_eq!(hex::encode(&key.key), "28155bba8880a98e1ba58f9da9e7a2e6ca87796e1341abfbcf40e64ee2df3e40");
    let ciphertext = key.encrypt(&SIP004_KAT_NONCE, SIP004_KAT_PLAINTEXT).expect("encrypt");
    assert_eq!(
        ciphertext,
        decode_hex("bca85439e450e89b06ded0c158c091e40aa0c528af464e501c18ad224e0c1a5e4bde24320d068608582a93515f"),
    );
    assert_eq!(key.decrypt(&SIP004_KAT_NONCE, &ciphertext).expect("decrypt"), SIP004_KAT_PLAINTEXT);
}

#[test]
fn sip004_legacy_derivation_rejects_2022_cipher_family() {
    let password = fixture_secret(SIP004_KAT_PASSWORD);
    let result = CipherKey::derive_legacy(Cipher::Aead2022Blake3Aes256Gcm, &password, SIP004_KAT_SALT_32);
    assert!(matches!(result, Err(CipherError::Unsupported(_))));
}

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
