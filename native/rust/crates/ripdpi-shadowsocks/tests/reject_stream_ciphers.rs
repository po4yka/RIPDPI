//! Verifies that every known stream cipher name is rejected with
//! [`CipherError::Unsupported`] and never silently downgraded.

use ripdpi_shadowsocks::cipher::{Cipher, CipherError};

fn assert_unsupported(name: &str) {
    match Cipher::from_name(name) {
        Err(CipherError::Unsupported(_)) => {}
        Ok(c) => panic!("stream cipher '{name}' was accepted as {c:?} — must be rejected"),
        Err(e) => panic!("stream cipher '{name}' returned unexpected error: {e}"),
    }
}

#[test]
fn rc4_is_rejected() {
    assert_unsupported("rc4");
}

#[test]
fn rc4_md5_is_rejected() {
    assert_unsupported("rc4-md5");
}

#[test]
fn aes_128_cfb_is_rejected() {
    assert_unsupported("aes-128-cfb");
}

#[test]
fn aes_192_cfb_is_rejected() {
    assert_unsupported("aes-192-cfb");
}

#[test]
fn aes_256_cfb_is_rejected() {
    assert_unsupported("aes-256-cfb");
}

#[test]
fn aes_128_ctr_is_rejected() {
    assert_unsupported("aes-128-ctr");
}

#[test]
fn aes_192_ctr_is_rejected() {
    assert_unsupported("aes-192-ctr");
}

#[test]
fn aes_256_ctr_is_rejected() {
    assert_unsupported("aes-256-ctr");
}

#[test]
fn chacha20_is_rejected() {
    assert_unsupported("chacha20");
}

#[test]
fn chacha20_ietf_is_rejected() {
    // Note: "chacha20-ietf" (without "poly1305") is a stream cipher.
    assert_unsupported("chacha20-ietf");
}

#[test]
fn salsa20_is_rejected() {
    assert_unsupported("salsa20");
}

#[test]
fn xchacha20_is_rejected() {
    assert_unsupported("xchacha20");
}

#[test]
fn camellia_128_cfb_is_rejected() {
    assert_unsupported("camellia-128-cfb");
}

#[test]
fn camellia_192_cfb_is_rejected() {
    assert_unsupported("camellia-192-cfb");
}

#[test]
fn camellia_256_cfb_is_rejected() {
    assert_unsupported("camellia-256-cfb");
}

#[test]
fn bf_cfb_is_rejected() {
    assert_unsupported("bf-cfb");
}

#[test]
fn des_cfb_is_rejected() {
    assert_unsupported("des-cfb");
}

#[test]
fn unknown_name_is_rejected() {
    assert_unsupported("totally-unknown-cipher");
}

#[test]
fn empty_name_is_rejected() {
    assert_unsupported("");
}

#[test]
fn aead_ciphers_are_accepted() {
    // Sanity check: legitimate AEAD names must not be rejected.
    assert!(Cipher::from_name("aes-128-gcm").is_ok());
    assert!(Cipher::from_name("aes-256-gcm").is_ok());
    assert!(Cipher::from_name("chacha20-ietf-poly1305").is_ok());
    assert!(Cipher::from_name("2022-blake3-aes-128-gcm").is_ok());
    assert!(Cipher::from_name("2022-blake3-aes-256-gcm").is_ok());
    assert!(Cipher::from_name("2022-blake3-chacha20-poly1305").is_ok());
}

#[test]
fn cipher_names_are_case_insensitive() {
    assert!(Cipher::from_name("AES-128-GCM").is_ok());
    assert!(Cipher::from_name("AES-256-GCM").is_ok());
    assert!(Cipher::from_name("ChaCha20-IETF-Poly1305").is_ok());
}
