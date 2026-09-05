//! TCP framing encrypt/decrypt round-trip tests.
//!
//! Exercises [`TcpStream`] for both AEAD-2022 and legacy AEAD ciphers.
//! Tests encode a payload, verify it differs from plaintext, then decode
//! and check exact round-trip fidelity.

use ripdpi_shadowsocks::TcpStream;
use ripdpi_shadowsocks::cipher::{Cipher, CipherKey, PresharedKey, SecretString};

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

#[test]
fn tcp_legacy_rejects_oversized_authenticated_chunk() {
    let cipher = Cipher::AeadAes128Gcm;
    let password = fixture_password("chunk-limit");
    let salt = [0; 16];
    let key = CipherKey::derive_legacy(cipher, &password, &salt).expect("key");
    let mut frame = key.encrypt(&counter_nonce_le(0), &0x4000u16.to_be_bytes()).expect("length");
    frame.extend(key.encrypt(&counter_nonce_le(1), &vec![0; 0x4000]).expect("payload"));
    let mut decoder = TcpStream::new_decrypt(cipher, &password, &salt, false).expect("decoder");
    assert!(decoder.decrypt_chunk(&frame, 0).is_err());
}

#[test]
fn tcp_invalid_offset_does_not_panic() {
    let password = fixture_password("offset-limit");
    let mut decoder = TcpStream::new_decrypt(Cipher::AeadAes128Gcm, &password, &[0; 16], false).expect("decoder");
    assert!(decoder.decrypt_chunk(&[], usize::MAX).is_err());
}

// Independent wire-field oracle: SIP022 3.1.2/3.1.3, also used by
// shadowsocks-rust tcprelay/aead_2022.rs (header nonce 0, body nonce 1).
#[test]
fn tcp_sip022_headers_match_wire_contract_and_preserve_payload_nonces() {
    for (cipher, credential) in [
        (Cipher::Aead2022Blake3Aes128Gcm, "AAECAwQFBgcICQoLDA0ODw=="),
        (Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256_B64),
        (Cipher::Aead2022Blake3Chacha20Poly1305, FIXTURE_PSK_CHACHA_B64),
    ] {
        let password = fixture_password(credential);
        let psk = PresharedKey::from_base64(cipher, credential).expect("psk");
        let request = b"\x01\x7f\x00\x00\x01\x01\xbb";
        let (mut client, request_salt) = TcpStream::new_encrypt(cipher, &password, true).expect("client");
        let frame = client.encrypt_request_header(1_700_000_000, request).expect("request header");
        let key = CipherKey::derive_aead2022(cipher, psk.as_bytes(), &request_salt).expect("key");
        let fixed = key.decrypt(&counter_nonce_le(0), &frame[..27]).expect("standalone fixed header");
        assert_eq!(&fixed[..9], &[0, 0, 0, 0, 0, 101, 83, 241, 0]);
        let body = key.decrypt(&counter_nonce_le(1), &frame[27..]).expect("variable header");
        assert_eq!(body.len(), usize::from(u16::from_be_bytes([fixed[9], fixed[10]])));
        assert_eq!(&body[..7], request);
        let padding = usize::from(u16::from_be_bytes([body[7], body[8]]));
        assert!((1..=900).contains(&padding));
        assert_eq!(body.len(), 9 + padding);
        let mut server = TcpStream::new_decrypt(cipher, &password, &request_salt, true).expect("server");
        assert_eq!(
            server.decrypt_request_header(&frame, 1_700_000_030).expect("fresh boundary").expect("header").0,
            request
        );
        let payload = client.encrypt_payload(b"request payload").expect("payload");
        assert_eq!(server.decrypt_chunk(&payload, 0).expect("decrypt").expect("chunk").0, b"request payload");

        let (mut response, response_salt) = TcpStream::new_encrypt(cipher, &password, true).expect("response");
        let frame = response.encrypt_response_header(1_700_000_000, &request_salt, b"first").expect("response header");
        let response_key = CipherKey::derive_aead2022(cipher, psk.as_bytes(), &response_salt).expect("key");
        let fixed_len = 27 + request_salt.len();
        let fixed = response_key.decrypt(&counter_nonce_le(0), &frame[..fixed_len]).expect("response fields");
        assert_eq!(fixed[0], 1);
        assert_eq!(&fixed[9..9 + request_salt.len()], &request_salt);
        assert_eq!(&fixed[fixed.len() - 2..], &5_u16.to_be_bytes());
        let mut reader = TcpStream::new_decrypt(cipher, &password, &response_salt, true).expect("reader");
        assert!(
            reader
                .decrypt_response_header(&frame[..frame.len() - 1], &request_salt, 1_700_000_000)
                .expect("partial")
                .is_none()
        );
        assert_eq!(
            reader
                .decrypt_response_header(&frame, &request_salt, 1_700_000_000)
                .expect("complete")
                .expect("response")
                .0,
            b"first"
        );
        let payload = response.encrypt_payload(b"second").expect("payload");
        assert_eq!(reader.decrypt_chunk(&payload, 0).expect("decrypt").expect("chunk").0, b"second");
    }
}

#[test]
fn tcp_sip022_rejects_wrong_direction_timestamp_salt_and_padding() {
    let cipher = Cipher::Aead2022Blake3Aes128Gcm;
    let credential = "AAECAwQFBgcICQoLDA0ODw==";
    let password = fixture_password(credential);
    let psk = PresharedKey::from_base64(cipher, credential).expect("psk");
    let salt = [7; 16];
    let request_salt = [9; 16];
    let key = CipherKey::derive_aead2022(cipher, psk.as_bytes(), &salt).expect("key");
    let frame = |kind: u8, timestamp: u64, bound_salt: &[u8], body: &[u8]| {
        let mut fixed = vec![kind];
        fixed.extend(timestamp.to_be_bytes());
        fixed.extend_from_slice(bound_salt);
        fixed.extend((body.len() as u16).to_be_bytes());
        let mut encrypted = key.encrypt(&counter_nonce_le(0), &fixed).expect("fixed");
        encrypted.extend(key.encrypt(&counter_nonce_le(1), body).expect("body"));
        encrypted
    };
    for malformed in [
        frame(0, 100, &request_salt, b"response"),
        frame(1, 69, &request_salt, b"response"),
        frame(1, 131, &request_salt, b"response"),
        frame(1, 100, &[8; 16], b"response"),
        frame(1, 100, &request_salt, b""),
    ] {
        let mut reader = TcpStream::new_decrypt(cipher, &password, &salt, true).expect("reader");
        assert!(reader.decrypt_response_header(&malformed, &request_salt, 100).is_err());
    }
    for body in [
        b"\x01\x7f\x00\x00\x01\x01\xbb\x00\x00".as_slice(),
        b"\x01\x7f\x00\x00\x01\x01\xbb\x00\x02\x00".as_slice(),
        b"\x01\x7f".as_slice(),
    ] {
        let malformed = frame(0, 100, &[], body);
        let mut reader = TcpStream::new_decrypt(cipher, &password, &salt, true).expect("reader");
        assert!(reader.decrypt_request_header(&malformed, 100).is_err());
    }
}
