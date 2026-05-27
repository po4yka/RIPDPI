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

use ripdpi_shadowsocks::cipher::{Cipher, CipherError, CipherKey, PresharedKey};

fn decode_hex(value: &str) -> Vec<u8> {
    hex::decode(value).expect("fixture hex must decode")
}

/// Fixed 32-byte PSK for `2022-blake3-aes-256-gcm` tests (fixture data only).
const FIXTURE_PSK_AES256: &[u8; 32] = b"fixture-psk-aes256gcm-32bytekey!";

/// Fixed 32-byte salt for deterministic test vectors.
const FIXTURE_SALT_32: &[u8; 32] = b"fixture-salt-32bytes-for-testing";

/// Fixed 32-byte PSK for `2022-blake3-chacha20-poly1305` tests.
const FIXTURE_PSK_CHACHA: &[u8; 32] = b"fixture-psk-chacha20poly1305-key";

/// All-zero 12-byte nonce used in KAT vectors.
const ZERO_NONCE: [u8; 12] = [0u8; 12];

const SIP022_KAT_PLAINTEXT: &[u8] = b"RIPDPI shadowsocks SIP022 KAT";
const SIP022_KAT_NONCE: [u8; 12] = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b];
const SIP022_AES128_PSK_B64: &str = "AAECAwQFBgcICQoLDA0ODw==";
const SIP022_AES128_SALT: &[u8; 16] = b"\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f";
const SIP022_AES256_PSK_B64: &str = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
const SIP022_AES256_SALT: &[u8; 32] = b" !\"#$%&'()*+,-./0123456789:;<=>?";
const SIP022_CHACHA_SALT: &[u8; 32] = b"@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_";

#[test]
fn sip022_aes128gcm_required_method_matches_independent_blake3_and_aead_vector() {
    assert_eq!(
        Cipher::from_name("2022-blake3-aes-128-gcm").expect("required SIP022 method"),
        Cipher::Aead2022Blake3Aes128Gcm
    );
    let psk = PresharedKey::from_base64(Cipher::Aead2022Blake3Aes128Gcm, SIP022_AES128_PSK_B64).expect("base64 psk");
    let key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes128Gcm, psk.as_bytes(), SIP022_AES128_SALT)
        .expect("derive");

    assert_eq!(hex::encode(&key.key), "bc32fb8d5205f7b84f9691dfb9f04ff3");
    let ciphertext = key.encrypt(&SIP022_KAT_NONCE, SIP022_KAT_PLAINTEXT).expect("encrypt");
    assert_eq!(
        ciphertext,
        decode_hex("6192d5fd13562315f0b2550dcdff0f0b54cb2d1dd58f1040c3a1f24ae056a48e69fc4bf831ab974213daaae71e"),
    );
    assert_eq!(key.decrypt(&SIP022_KAT_NONCE, &ciphertext).expect("decrypt"), SIP022_KAT_PLAINTEXT);
}

#[test]
fn sip022_aes256gcm_matches_independent_blake3_and_aead_vector() {
    let psk = PresharedKey::from_base64(Cipher::Aead2022Blake3Aes256Gcm, SIP022_AES256_PSK_B64).expect("base64 psk");
    let key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Aes256Gcm, psk.as_bytes(), SIP022_AES256_SALT)
        .expect("derive");

    assert_eq!(hex::encode(&key.key), "374fca03e4dae7f998fd7e59c1edfcc8e3197f4db1c19ca1671be3b66a92ddda");
    let ciphertext = key.encrypt(&SIP022_KAT_NONCE, SIP022_KAT_PLAINTEXT).expect("encrypt");
    assert_eq!(
        ciphertext,
        decode_hex("e6b3160151326354adb3937dabc102a1dbfedb3e9b4bae7f5036f905cd7b1ca194357512414c7b148aa9232c3a"),
    );
    assert_eq!(key.decrypt(&SIP022_KAT_NONCE, &ciphertext).expect("decrypt"), SIP022_KAT_PLAINTEXT);
}

#[test]
fn sip022_chacha20_poly1305_matches_independent_blake3_and_aead_vector() {
    let psk =
        PresharedKey::from_base64(Cipher::Aead2022Blake3Chacha20Poly1305, SIP022_AES256_PSK_B64).expect("base64 psk");
    let key = CipherKey::derive_aead2022(Cipher::Aead2022Blake3Chacha20Poly1305, psk.as_bytes(), SIP022_CHACHA_SALT)
        .expect("derive");

    assert_eq!(hex::encode(&key.key), "cb4edecf23461aaaeee9dcb3c1eb1be555c77e3661c7dd58c96bd5c3bcb6a064");
    let ciphertext = key.encrypt(&SIP022_KAT_NONCE, SIP022_KAT_PLAINTEXT).expect("encrypt");
    assert_eq!(
        ciphertext,
        decode_hex("d4e0616718a43e1aeab75c17768cc0968208044b72c0086e00b41e2aa723ded39940dc7ad0cbfdb8d4a0b7f775"),
    );
    assert_eq!(key.decrypt(&SIP022_KAT_NONCE, &ciphertext).expect("decrypt"), SIP022_KAT_PLAINTEXT);
}

#[test]
fn sip022_psk_parser_rejects_non_base64_and_wrong_length_material() {
    assert_eq!(
        PresharedKey::from_base64(Cipher::Aead2022Blake3Aes128Gcm, "not base64").expect_err("invalid base64"),
        CipherError::KeyLength,
    );
    assert_eq!(
        PresharedKey::from_base64(Cipher::Aead2022Blake3Aes256Gcm, SIP022_AES128_PSK_B64).expect_err("wrong length"),
        CipherError::KeyLength,
    );
}

#[test]
fn sip022_psk_parser_rejects_base64url_no_padding_material() {
    let standard_psk = "+/v7+/v7+/v7+/v7+/v7+w==";
    let urlsafe_no_padding_psk = "-_v7-_v7-_v7-_v7-_v7-w";

    assert!(PresharedKey::from_base64(Cipher::Aead2022Blake3Aes128Gcm, standard_psk).is_ok());
    assert_eq!(
        PresharedKey::from_base64(Cipher::Aead2022Blake3Aes128Gcm, urlsafe_no_padding_psk)
            .expect_err("SIP022 PSK must use standard base64, not Base64URL without padding"),
        CipherError::KeyLength,
    );
}

#[test]
fn sip022_derivation_rejects_legacy_cipher_family() {
    let psk = PresharedKey::from_base64(Cipher::Aead2022Blake3Aes128Gcm, SIP022_AES128_PSK_B64).expect("base64 psk");
    let result = CipherKey::derive_aead2022(Cipher::AeadAes128Gcm, psk.as_bytes(), SIP022_AES128_SALT);
    assert!(matches!(result, Err(CipherError::Unsupported(_))));
}

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
