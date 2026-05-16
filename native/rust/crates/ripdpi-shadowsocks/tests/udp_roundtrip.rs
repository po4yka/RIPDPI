//! UDP packet framing encrypt/decrypt round-trip tests.

use ripdpi_shadowsocks::cipher::{Cipher, SecretString};
use ripdpi_shadowsocks::UdpPacket;

fn fixture_password(s: &str) -> SecretString {
    SecretString::new(s.to_owned())
}

const FIXTURE_PSK_AES256_B64: &str = "Zml4dHVyZS1wc2stYWVzMjU2Z2NtLTMyYnl0ZWtleSE=";
const FIXTURE_PSK_CHACHA_B64: &str = "Zml4dHVyZS1wc2stY2hhY2hhMjBwb2x5MTMwNS1rZXk=";

#[test]
fn udp_aead2022_aes256gcm_roundtrip() {
    let password = fixture_password(FIXTURE_PSK_AES256_B64);
    let plaintext = b"fixture-udp-payload-aes256gcm";

    let codec = UdpPacket::new(Cipher::Aead2022Blake3Aes256Gcm, true);
    let packet = codec.encrypt(&password, plaintext).expect("encrypt");
    assert!(packet.len() > plaintext.len());

    let recovered = codec.decrypt(&password, &packet).expect("decrypt");
    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn udp_aead2022_chacha20poly1305_roundtrip() {
    let password = fixture_password(FIXTURE_PSK_CHACHA_B64);
    let plaintext = b"fixture-udp-payload-chacha20";

    let codec = UdpPacket::new(Cipher::Aead2022Blake3Chacha20Poly1305, true);
    let packet = codec.encrypt(&password, plaintext).expect("encrypt");
    let recovered = codec.decrypt(&password, &packet).expect("decrypt");
    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn udp_legacy_aes256gcm_roundtrip() {
    let password = fixture_password("fixture-password-udp-legacy");
    let plaintext = b"fixture-udp-payload-legacy-aes256";

    let codec = UdpPacket::new(Cipher::AeadAes256Gcm, false);
    let packet = codec.encrypt(&password, plaintext).expect("encrypt");
    let recovered = codec.decrypt(&password, &packet).expect("decrypt");
    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn udp_legacy_chacha20_roundtrip() {
    let password = fixture_password("fixture-password-udp-chacha");
    let plaintext = b"fixture-udp-chacha20-payload";

    let codec = UdpPacket::new(Cipher::AeadChacha20IetfPoly1305, false);
    let packet = codec.encrypt(&password, plaintext).expect("encrypt");
    let recovered = codec.decrypt(&password, &packet).expect("decrypt");
    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn udp_tampered_packet_fails() {
    let password = fixture_password("fixture-tamper-udp");
    let plaintext = b"fixture-udp-tamper-test";

    let codec = UdpPacket::new(Cipher::AeadAes256Gcm, false);
    let mut packet = codec.encrypt(&password, plaintext).expect("encrypt");
    let mid = packet.len() / 2;
    packet[mid] ^= 0xFF;

    assert!(codec.decrypt(&password, &packet).is_err());
}

#[test]
fn udp_too_short_fails() {
    let password = fixture_password("fixture-short-udp");
    let codec = UdpPacket::new(Cipher::AeadAes256Gcm, false);
    // 5 bytes is far shorter than salt_len(32) + tag_len(16).
    assert!(codec.decrypt(&password, &[0u8; 5]).is_err());
}
