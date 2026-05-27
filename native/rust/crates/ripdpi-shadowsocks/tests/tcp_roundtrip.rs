//! TCP framing encrypt/decrypt round-trip tests.
//!
//! Exercises [`TcpStream`] for both AEAD-2022 and legacy AEAD ciphers.
//! Tests encode a payload, verify it differs from plaintext, then decode
//! and check exact round-trip fidelity.

use ripdpi_shadowsocks::cipher::{Cipher, CipherKey, PresharedKey, SecretString};
use ripdpi_shadowsocks::TcpStream;

fn fixture_password(s: &str) -> SecretString {
    SecretString::new(s.to_owned())
}

/// A 32-byte base64-encoded PSK for AEAD-2022 tests.
/// The raw bytes of "fixture-psk-aes256gcm-32bytekey!" base64-encoded.
const FIXTURE_PSK_AES256_B64: &str = "Zml4dHVyZS1wc2stYWVzMjU2Z2NtLTMyYnl0ZWtleSE=";
/// A 32-byte base64-encoded PSK for chacha20 tests.
const FIXTURE_PSK_CHACHA_B64: &str = "Zml4dHVyZS1wc2stY2hhY2hhMjBwb2x5MTMwNS1rZXk=";

fn counter_nonce_le(counter: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[..8].copy_from_slice(&counter.to_le_bytes());
    nonce
}

fn len_frame_len(cipher: Cipher) -> usize {
    2 + cipher.tag_len()
}

#[test]
fn tcp_aead2022_aes256gcm_roundtrip() {
    let password = fixture_password(FIXTURE_PSK_AES256_B64);
    let plaintext = b"fixture-tcp-payload-aes256gcm-2022";

    let (mut enc, salt) =
        TcpStream::new_encrypt(Cipher::Aead2022Blake3Aes256Gcm, &password, true).expect("new_encrypt");

    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");

    let mut dec = TcpStream::new_decrypt(Cipher::Aead2022Blake3Aes256Gcm, &password, &salt, true).expect("new_decrypt");

    let (recovered, _consumed) = dec
        .decrypt_chunk(&encrypted, 0)
        .expect("decrypt_chunk must not error")
        .expect("decrypt_chunk must return Some for complete chunk");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn tcp_aead2022_chacha20poly1305_roundtrip() {
    let password = fixture_password(FIXTURE_PSK_CHACHA_B64);
    let plaintext = b"fixture-tcp-payload-chacha20poly1305-2022";

    let (mut enc, salt) =
        TcpStream::new_encrypt(Cipher::Aead2022Blake3Chacha20Poly1305, &password, true).expect("new_encrypt");

    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");

    let mut dec =
        TcpStream::new_decrypt(Cipher::Aead2022Blake3Chacha20Poly1305, &password, &salt, true).expect("new_decrypt");

    let (recovered, _) = dec.decrypt_chunk(&encrypted, 0).expect("no error").expect("complete chunk");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn tcp_legacy_aes256gcm_roundtrip() {
    let password = fixture_password("fixture-password-tcp-legacy");
    let plaintext = b"fixture-tcp-payload-legacy-aes256gcm";

    let (mut enc, salt) = TcpStream::new_encrypt(Cipher::AeadAes256Gcm, &password, false).expect("new_encrypt");

    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");

    let mut dec = TcpStream::new_decrypt(Cipher::AeadAes256Gcm, &password, &salt, false).expect("new_decrypt");

    let (recovered, _) = dec.decrypt_chunk(&encrypted, 0).expect("no error").expect("complete chunk");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn tcp_legacy_chacha20_roundtrip() {
    let password = fixture_password("fixture-password-tcp-chacha");
    let plaintext = b"fixture-tcp-payload-legacy-chacha20ietf";

    let (mut enc, salt) =
        TcpStream::new_encrypt(Cipher::AeadChacha20IetfPoly1305, &password, false).expect("new_encrypt");

    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");

    let mut dec =
        TcpStream::new_decrypt(Cipher::AeadChacha20IetfPoly1305, &password, &salt, false).expect("new_decrypt");

    let (recovered, _) = dec.decrypt_chunk(&encrypted, 0).expect("no error").expect("complete chunk");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn tcp_incomplete_data_returns_none() {
    let password = fixture_password("fixture-incomplete-data-test");

    let (mut enc, salt) = TcpStream::new_encrypt(Cipher::AeadAes256Gcm, &password, false).expect("new_encrypt");

    let encrypted = enc.encrypt_payload(b"fixture-payload").expect("encrypt_payload");

    let mut dec = TcpStream::new_decrypt(Cipher::AeadAes256Gcm, &password, &salt, false).expect("new_decrypt");

    // Supply only 5 bytes — not enough for any chunk.
    let result = dec.decrypt_chunk(&encrypted[..5], 0).expect("no error");
    assert!(result.is_none(), "incomplete data must return None");
}

#[test]
fn tcp_legacy_payload_nonce_uses_little_endian_counter() {
    let password = fixture_password("fixture-password-tcp-nonce");
    let plaintext = b"abc";
    let (mut enc, salt) = TcpStream::new_encrypt(Cipher::AeadAes256Gcm, &password, false).expect("new_encrypt");
    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");
    let key = CipherKey::derive_legacy(Cipher::AeadAes256Gcm, &password, &salt).expect("derive legacy");
    let payload_ct = &encrypted[len_frame_len(Cipher::AeadAes256Gcm)..];

    let recovered = key.decrypt(&counter_nonce_le(1), payload_ct).expect("payload decrypt with LE counter nonce");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn tcp_legacy_splits_payload_at_sip004_chunk_cap() {
    let password = fixture_password("fixture-password-tcp-cap");
    let plaintext = vec![0x41; 0x4000];
    let (mut enc, salt) = TcpStream::new_encrypt(Cipher::AeadAes256Gcm, &password, false).expect("new_encrypt");
    let encrypted = enc.encrypt_payload(&plaintext).expect("encrypt_payload");
    let mut dec = TcpStream::new_decrypt(Cipher::AeadAes256Gcm, &password, &salt, false).expect("new_decrypt");

    let (first, consumed) = dec.decrypt_chunk(&encrypted, 0).expect("first no error").expect("first chunk");
    let (second, second_consumed) =
        dec.decrypt_chunk(&encrypted, consumed).expect("second no error").expect("second chunk");

    assert_eq!(first.len(), 0x3fff);
    assert_eq!(second.len(), 1);
    assert_eq!(consumed + second_consumed, encrypted.len());
}

#[test]
fn tcp_aead2022_uses_sip022_payload_chunk_cap() {
    let password = fixture_password(FIXTURE_PSK_AES256_B64);
    let plaintext = vec![0x42; 0x4000];
    let (mut enc, salt) =
        TcpStream::new_encrypt(Cipher::Aead2022Blake3Aes256Gcm, &password, true).expect("new_encrypt");
    let encrypted = enc.encrypt_payload(&plaintext).expect("encrypt_payload");
    let mut dec = TcpStream::new_decrypt(Cipher::Aead2022Blake3Aes256Gcm, &password, &salt, true).expect("new_decrypt");

    let (first, consumed) = dec.decrypt_chunk(&encrypted, 0).expect("first no error").expect("first chunk");

    assert_eq!(first.len(), 0x4000);
    assert_eq!(consumed, encrypted.len());
}

#[test]
fn tcp_partial_payload_frame_does_not_advance_counter() {
    let password = fixture_password("fixture-password-tcp-partial-payload");
    let plaintext = b"fixture-payload";
    let (mut enc, salt) = TcpStream::new_encrypt(Cipher::AeadAes256Gcm, &password, false).expect("new_encrypt");
    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");
    let mut dec = TcpStream::new_decrypt(Cipher::AeadAes256Gcm, &password, &salt, false).expect("new_decrypt");

    assert!(dec.decrypt_chunk(&encrypted[..encrypted.len() - 1], 0).expect("partial no error").is_none());
    let (recovered, consumed) = dec.decrypt_chunk(&encrypted, 0).expect("complete no error").expect("complete chunk");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
    assert_eq!(consumed, encrypted.len());
}

#[test]
fn tcp_aead2022_aes128gcm_roundtrip() {
    let psk =
        PresharedKey::from_base64(Cipher::Aead2022Blake3Aes128Gcm, "AAECAwQFBgcICQoLDA0ODw==").expect("base64 psk");
    let password = fixture_password("AAECAwQFBgcICQoLDA0ODw==");
    let plaintext = b"fixture-tcp-payload-aes128gcm-2022";
    assert_eq!(psk.as_bytes().len(), Cipher::Aead2022Blake3Aes128Gcm.key_len());

    let (mut enc, salt) =
        TcpStream::new_encrypt(Cipher::Aead2022Blake3Aes128Gcm, &password, true).expect("new_encrypt");
    let encrypted = enc.encrypt_payload(plaintext).expect("encrypt_payload");
    let mut dec = TcpStream::new_decrypt(Cipher::Aead2022Blake3Aes128Gcm, &password, &salt, true).expect("new_decrypt");
    let (recovered, consumed) = dec.decrypt_chunk(&encrypted, 0).expect("no error").expect("complete chunk");

    assert_eq!(recovered.as_slice(), plaintext.as_slice());
    assert_eq!(consumed, encrypted.len());
}
