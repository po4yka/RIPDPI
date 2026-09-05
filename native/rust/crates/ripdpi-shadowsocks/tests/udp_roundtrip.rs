//! UDP packet framing encrypt/decrypt round-trip tests.

use ripdpi_shadowsocks::cipher::{Cipher, CipherError, PresharedKey, SecretString};
use ripdpi_shadowsocks::{Aead2022UdpPacketType, Aead2022UdpSession, UdpPacket};

fn fixture_password(s: &str) -> SecretString {
    SecretString::new(s.to_owned())
}

const FIXTURE_PSK_AES256_B64: &str = "Zml4dHVyZS1wc2stYWVzMjU2Z2NtLTMyYnl0ZWtleSE=";
const FIXTURE_PSK_CHACHA_B64: &str = "Zml4dHVyZS1wc2stY2hhY2hhMjBwb2x5MTMwNS1rZXk=";

const SIP022_TIMESTAMP: u64 = 1_700_000_000;

fn sip022_aes256_psk() -> PresharedKey {
    PresharedKey::from_base64(Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256_B64).expect("valid psk")
}

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
fn udp_aead2022_aes_gcm_packet_uses_sip022_separate_header() {
    let psk = sip022_aes256_psk();
    let session_id = *b"SID-A256";
    let mut sender = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("sender");
    let payload = b"\x00\x00\x03\x0bexample.com\x01\xbbinitial-payload";

    let packet = sender.encrypt(Aead2022UdpPacketType::Client, SIP022_TIMESTAMP, payload).expect("encrypt");

    assert_eq!(packet.len(), 16 + 1 + 8 + 2 + payload.len() + 16);
    assert_ne!(&packet[..8], &session_id);
    assert_ne!(&packet[8..16], &0u64.to_be_bytes());

    let psk = sip022_aes256_psk();
    let mut receiver = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("receiver");
    let decoded = receiver.decrypt(&packet, Aead2022UdpPacketType::Client, SIP022_TIMESTAMP).expect("decrypt");
    assert_eq!(decoded.session_id, session_id);
    assert_eq!(decoded.packet_id, 0);
    assert_eq!(decoded.packet_type, Aead2022UdpPacketType::Client);
    assert_eq!(decoded.timestamp, SIP022_TIMESTAMP);
    assert_eq!(decoded.client_session_id, None);
    assert_eq!(decoded.payload.as_slice(), payload.as_slice());
}

#[test]
fn udp_aead2022_aes_gcm_server_packet_carries_client_session_id() {
    let psk = sip022_aes256_psk();
    let session_id = *b"SID-SRVR";
    let client_session_id = *b"SID-CLNT";
    let mut sender = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("sender");
    let payload = b"\x01\x7f\x00\x00\x01\x13\x88server-payload";

    let packet = sender.encrypt_server(SIP022_TIMESTAMP, client_session_id, payload).expect("encrypt server packet");

    assert_eq!(packet.len(), 16 + 1 + 8 + 8 + 2 + payload.len() + 16);

    let psk = sip022_aes256_psk();
    let mut receiver =
        Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, client_session_id).expect("receiver");
    let decoded =
        receiver.decrypt(&packet, Aead2022UdpPacketType::Server, SIP022_TIMESTAMP).expect("decrypt server packet");
    assert_eq!(decoded.session_id, session_id);
    assert_eq!(decoded.packet_id, 0);
    assert_eq!(decoded.packet_type, Aead2022UdpPacketType::Server);
    assert_eq!(decoded.client_session_id, Some(client_session_id));
    assert_eq!(decoded.payload.as_slice(), payload.as_slice());
}

#[test]
fn udp_aead2022_chacha20poly1305_uses_sip022_xchacha_packet_shape() {
    let psk =
        PresharedKey::from_base64(Cipher::Aead2022Blake3Chacha20Poly1305, FIXTURE_PSK_CHACHA_B64).expect("valid psk");
    let session_id = *b"SID-CH20";
    let mut sender = Aead2022UdpSession::new(Cipher::Aead2022Blake3Chacha20Poly1305, psk, session_id).expect("sender");
    let payload = b"\x03\x0bexample.net\x01\xbbchacha-payload";

    let packet = sender.encrypt(Aead2022UdpPacketType::Client, SIP022_TIMESTAMP, payload).expect("encrypt");

    assert_eq!(packet.len(), 24 + 8 + 8 + 1 + 8 + 2 + payload.len() + 16);

    let psk =
        PresharedKey::from_base64(Cipher::Aead2022Blake3Chacha20Poly1305, FIXTURE_PSK_CHACHA_B64).expect("valid psk");
    let mut receiver =
        Aead2022UdpSession::new(Cipher::Aead2022Blake3Chacha20Poly1305, psk, session_id).expect("receiver");
    let decoded = receiver.decrypt(&packet, Aead2022UdpPacketType::Client, SIP022_TIMESTAMP).expect("decrypt");
    assert_eq!(decoded.session_id, session_id);
    assert_eq!(decoded.packet_id, 0);
    assert_eq!(decoded.packet_type, Aead2022UdpPacketType::Client);
    assert_eq!(decoded.client_session_id, None);
    assert_eq!(decoded.payload.as_slice(), payload.as_slice());
}

#[test]
fn udp_aead2022_rejects_duplicate_packet_id() {
    let psk = sip022_aes256_psk();
    let session_id = *b"SID-REPL";
    let mut sender = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("sender");
    let packet = sender.encrypt(Aead2022UdpPacketType::Client, SIP022_TIMESTAMP, b"payload").expect("encrypt");

    let psk = sip022_aes256_psk();
    let mut receiver = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("receiver");
    receiver.decrypt(&packet, Aead2022UdpPacketType::Client, SIP022_TIMESTAMP).expect("first decrypt");

    let duplicate = receiver.decrypt(&packet, Aead2022UdpPacketType::Client, SIP022_TIMESTAMP).expect_err("duplicate");
    assert_eq!(duplicate, CipherError::Replay);
}

#[test]
fn udp_aead2022_replay_window_updates_only_after_header_validation() {
    let psk = sip022_aes256_psk();
    let session_id = *b"SID-TIME";
    let mut sender = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("sender");
    let stale_packet =
        sender.encrypt(Aead2022UdpPacketType::Client, SIP022_TIMESTAMP - 31, b"payload").expect("encrypt");

    let psk = sip022_aes256_psk();
    let mut receiver = Aead2022UdpSession::new(Cipher::Aead2022Blake3Aes256Gcm, psk, session_id).expect("receiver");
    let stale =
        receiver.decrypt(&stale_packet, Aead2022UdpPacketType::Client, SIP022_TIMESTAMP).expect_err("stale timestamp");
    assert_eq!(stale, CipherError::Replay);

    let fresh_packet = sender.encrypt(Aead2022UdpPacketType::Client, SIP022_TIMESTAMP, b"fresh").expect("encrypt");
    let decoded = receiver
        .decrypt(&fresh_packet, Aead2022UdpPacketType::Client, SIP022_TIMESTAMP)
        .expect("fresh packet is still accepted");
    assert_eq!(decoded.packet_id, 1);
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

#[test]
fn udp_server_recipient_is_checked_before_replay_state() {
    for (cipher, credential) in [
        (Cipher::Aead2022Blake3Aes128Gcm, "AAECAwQFBgcICQoLDA0ODw=="),
        (Cipher::Aead2022Blake3Aes256Gcm, FIXTURE_PSK_AES256_B64),
        (Cipher::Aead2022Blake3Chacha20Poly1305, FIXTURE_PSK_CHACHA_B64),
    ] {
        let session = |id| {
            Aead2022UdpSession::new(cipher, PresharedKey::from_base64(cipher, credential).expect("psk"), id)
                .expect("session")
        };
        let mut client = session(*b"SID-CLNT");
        let wrong = session(*b"SID-SRVR").encrypt_server(SIP022_TIMESTAMP, *b"OTHER-ID", b"wrong").expect("packet");
        assert!(client.decrypt(&wrong, Aead2022UdpPacketType::Server, SIP022_TIMESTAMP).is_err());
        // Same server session and packet ID: a wrong recipient must not consume ID 0.
        let correct = session(*b"SID-SRVR").encrypt_server(SIP022_TIMESTAMP, *b"SID-CLNT", b"right").expect("packet");
        assert_eq!(
            client
                .decrypt(&correct, Aead2022UdpPacketType::Server, SIP022_TIMESTAMP)
                .expect("correct recipient")
                .payload,
            b"right"
        );
    }
}
