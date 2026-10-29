//! Shadowsocks 2022 UDP packet framing — encrypt/decrypt with AEAD.
//!
//! # Wire format
//!
//! ```text
//! SIP022 AES-GCM:
//! [encrypted separate header 16B] [encrypted body + tag]
//!
//! SIP022 ChaCha20-Poly1305:
//! [nonce 24B] [encrypted body + tag]
//! ```
//!
//! SIP022 UDP uses session IDs, packet IDs, timestamps, and a sliding-window replay filter.
//! Legacy AEAD UDP is unsupported because it cannot enforce packet freshness.

use std::collections::HashSet;
use std::time::{Duration, Instant};

use aes::cipher::{BlockCipherDecrypt, BlockCipherEncrypt, KeyInit as AesKeyInit};
use aes::{Aes128, Aes256};
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand::RngExt;

use crate::cipher::{Cipher, CipherError, CipherKey, PresharedKey};

const SIP022_REPLAY_WINDOW: u64 = 1024;
const SIP022_TIMESTAMP_TOLERANCE_SECS: u64 = 30;
const SIP022_SESSION_RETENTION: Duration = Duration::from_secs(61);
const SIP022_XCHACHA_NONCE_LEN: usize = 24;

/// SIP022 UDP main-header type.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Aead2022UdpPacketType {
    /// Client-to-server UDP packet.
    Client = 0,
    /// Server-to-client UDP packet.
    Server = 1,
}

impl Aead2022UdpPacketType {
    fn from_byte(value: u8) -> Result<Self, CipherError> {
        match value {
            0 => Ok(Self::Client),
            1 => Ok(Self::Server),
            _ => Err(CipherError::InvalidPacket("unknown SIP022 UDP packet type")),
        }
    }
}

/// Decrypted SIP022 UDP packet fields.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Aead2022UdpPacket {
    /// Session ID from the decrypted separate header.
    pub session_id: [u8; 8],
    /// Big-endian packet ID from the decrypted separate header.
    pub packet_id: u64,
    /// Main-header packet type.
    pub packet_type: Aead2022UdpPacketType,
    /// Main-header Unix epoch timestamp.
    pub timestamp: u64,
    /// Client session ID carried by server-to-client SIP022 UDP packets.
    pub client_session_id: Option<[u8; 8]>,
    /// Remaining authenticated body bytes after type and timestamp.
    pub payload: Vec<u8>,
}

/// SIP022 UDP session codec with replay filtering for the current and previous peer sessions.
///
/// A third peer session is rejected until the previous session has been inactive for more than 61 seconds.
pub struct Aead2022UdpSession {
    cipher: Cipher,
    psk: Vec<u8>,
    session_id: [u8; 8],
    next_packet_id: u64,
    replay_windows: ReplaySessions,
}

impl Aead2022UdpSession {
    /// Creates a SIP022 UDP session for required AES-GCM methods or optional XChaCha20-Poly1305.
    pub fn new(cipher: Cipher, psk: PresharedKey, session_id: [u8; 8]) -> Result<Self, CipherError> {
        match cipher {
            Cipher::Aead2022Blake3Aes128Gcm
            | Cipher::Aead2022Blake3Aes256Gcm
            | Cipher::Aead2022Blake3Chacha20Poly1305 => {}
            _ => return Err(CipherError::Unsupported(format!("{cipher:?}"))),
        }
        Ok(Self {
            cipher,
            psk: psk.as_bytes().to_vec(),
            session_id,
            next_packet_id: 0,
            replay_windows: ReplaySessions::default(),
        })
    }

    /// Encrypts one SIP022 UDP packet.
    ///
    /// `payload` is the authenticated main-header tail after type and timestamp.
    pub fn encrypt(
        &mut self,
        packet_type: Aead2022UdpPacketType,
        timestamp: u64,
        payload: &[u8],
    ) -> Result<Vec<u8>, CipherError> {
        if packet_type == Aead2022UdpPacketType::Server {
            return Err(CipherError::InvalidPacket("server packets require client session ID"));
        }
        self.encrypt_inner(packet_type, timestamp, None, payload)
    }

    /// Encrypts one server-to-client SIP022 UDP packet with the required client session ID.
    pub fn encrypt_server(
        &mut self,
        timestamp: u64,
        client_session_id: [u8; 8],
        payload: &[u8],
    ) -> Result<Vec<u8>, CipherError> {
        self.encrypt_inner(Aead2022UdpPacketType::Server, timestamp, Some(client_session_id), payload)
    }

    /// Decrypts one SIP022 UDP packet and updates replay state only after semantic validation.
    pub fn decrypt(
        &mut self,
        packet: &[u8],
        expected_type: Aead2022UdpPacketType,
        now_timestamp: u64,
    ) -> Result<Aead2022UdpPacket, CipherError> {
        if self.cipher == Cipher::Aead2022Blake3Chacha20Poly1305 {
            return self.decrypt_chacha(packet, expected_type, now_timestamp);
        }
        if packet.len() < 16 + 1 + 8 + self.cipher.tag_len() {
            return Err(CipherError::AeadFailed);
        }

        let mut encrypted_header = [0u8; 16];
        encrypted_header.copy_from_slice(&packet[..16]);
        let separate_header = decrypt_separate_header(self.cipher, &self.psk, &encrypted_header)?;
        let mut session_id = [0u8; 8];
        session_id.copy_from_slice(&separate_header[..8]);
        let packet_id = u64::from_be_bytes(separate_header[8..16].try_into().expect("header length"));

        if self.replay_windows.is_replay(session_id, packet_id) {
            return Err(CipherError::Replay);
        }

        let key = CipherKey::derive_aead2022_udp(self.cipher, &self.psk, &session_id)?;
        let body = key.decrypt(&separate_header[4..16], &packet[16..])?;
        if body.len() < 9 {
            return Err(CipherError::InvalidPacket("short SIP022 UDP main header"));
        }
        let packet_type = Aead2022UdpPacketType::from_byte(body[0])?;
        if packet_type != expected_type {
            return Err(CipherError::InvalidPacket("unexpected SIP022 UDP packet type"));
        }
        let timestamp = u64::from_be_bytes(body[1..9].try_into().expect("timestamp length"));
        if timestamp.abs_diff(now_timestamp) > SIP022_TIMESTAMP_TOLERANCE_SECS {
            return Err(CipherError::Replay);
        }
        let mut offset = 9;
        let client_session_id = if packet_type == Aead2022UdpPacketType::Server {
            if body.len() < offset + 8 {
                return Err(CipherError::InvalidPacket("short SIP022 UDP server header"));
            }
            let mut id = [0u8; 8];
            id.copy_from_slice(&body[offset..offset + 8]);
            offset += 8;
            if id != self.session_id {
                return Err(CipherError::InvalidPacket("wrong SIP022 UDP client session ID"));
            }
            Some(id)
        } else {
            None
        };
        if body.len() < offset + 2 {
            return Err(CipherError::InvalidPacket("short SIP022 UDP padding length"));
        }
        let padding_len = u16::from_be_bytes(body[offset..offset + 2].try_into().expect("padding length")) as usize;
        offset += 2;
        if body.len() < offset + padding_len {
            return Err(CipherError::InvalidPacket("short SIP022 UDP padding"));
        }
        offset += padding_len;

        self.replay_windows.accept(session_id, packet_id, Instant::now())?;

        Ok(Aead2022UdpPacket {
            session_id,
            packet_id,
            packet_type,
            timestamp,
            client_session_id,
            payload: body[offset..].to_vec(),
        })
    }

    fn decrypt_chacha(
        &mut self,
        packet: &[u8],
        expected_type: Aead2022UdpPacketType,
        now_timestamp: u64,
    ) -> Result<Aead2022UdpPacket, CipherError> {
        if packet.len() < SIP022_XCHACHA_NONCE_LEN + 8 + 8 + 1 + 8 + 2 + self.cipher.tag_len() {
            return Err(CipherError::AeadFailed);
        }
        let cipher = XChaCha20Poly1305::new_from_slice(&self.psk).map_err(|_| CipherError::KeyLength)?;
        let nonce = <&XNonce>::try_from(&packet[..SIP022_XCHACHA_NONCE_LEN]).map_err(|_| CipherError::IvLength)?;
        let body = cipher.decrypt(nonce, &packet[SIP022_XCHACHA_NONCE_LEN..]).map_err(|_| CipherError::AeadFailed)?;
        if body.len() < 8 + 8 + 1 + 8 + 2 {
            return Err(CipherError::InvalidPacket("short SIP022 chacha UDP main header"));
        }
        let mut session_id = [0u8; 8];
        session_id.copy_from_slice(&body[..8]);
        let packet_id = u64::from_be_bytes(body[8..16].try_into().expect("packet id length"));

        if self.replay_windows.is_replay(session_id, packet_id) {
            return Err(CipherError::Replay);
        }

        let packet_type = Aead2022UdpPacketType::from_byte(body[16])?;
        if packet_type != expected_type {
            return Err(CipherError::InvalidPacket("unexpected SIP022 UDP packet type"));
        }
        let timestamp = u64::from_be_bytes(body[17..25].try_into().expect("timestamp length"));
        if timestamp.abs_diff(now_timestamp) > SIP022_TIMESTAMP_TOLERANCE_SECS {
            return Err(CipherError::Replay);
        }
        let mut offset = 25;
        let client_session_id = if packet_type == Aead2022UdpPacketType::Server {
            if body.len() < offset + 8 {
                return Err(CipherError::InvalidPacket("short SIP022 UDP server header"));
            }
            let mut id = [0u8; 8];
            id.copy_from_slice(&body[offset..offset + 8]);
            offset += 8;
            if id != self.session_id {
                return Err(CipherError::InvalidPacket("wrong SIP022 UDP client session ID"));
            }
            Some(id)
        } else {
            None
        };
        if body.len() < offset + 2 {
            return Err(CipherError::InvalidPacket("short SIP022 UDP padding length"));
        }
        let padding_len = u16::from_be_bytes(body[offset..offset + 2].try_into().expect("padding length")) as usize;
        offset += 2;
        if body.len() < offset + padding_len {
            return Err(CipherError::InvalidPacket("short SIP022 UDP padding"));
        }
        offset += padding_len;

        self.replay_windows.accept(session_id, packet_id, Instant::now())?;

        Ok(Aead2022UdpPacket {
            session_id,
            packet_id,
            packet_type,
            timestamp,
            client_session_id,
            payload: body[offset..].to_vec(),
        })
    }

    fn encrypt_inner(
        &mut self,
        packet_type: Aead2022UdpPacketType,
        timestamp: u64,
        client_session_id: Option<[u8; 8]>,
        payload: &[u8],
    ) -> Result<Vec<u8>, CipherError> {
        let packet_id = self.next_packet_id;
        self.next_packet_id =
            self.next_packet_id.checked_add(1).ok_or(CipherError::InvalidPacket("packet ID overflow"))?;

        if self.cipher == Cipher::Aead2022Blake3Chacha20Poly1305 {
            return self.encrypt_chacha(packet_id, packet_type, timestamp, client_session_id, payload);
        }

        let separate_header = separate_header(self.session_id, packet_id);
        let encrypted_header = encrypt_separate_header(self.cipher, &self.psk, &separate_header)?;
        let key = CipherKey::derive_aead2022_udp(self.cipher, &self.psk, &self.session_id)?;
        let nonce = &separate_header[4..16];

        let mut body = Vec::with_capacity(1 + 8 + client_session_id.map_or(0, |_| 8) + 2 + payload.len());
        body.push(packet_type as u8);
        body.extend_from_slice(&timestamp.to_be_bytes());
        if let Some(id) = client_session_id {
            body.extend_from_slice(&id);
        }
        body.extend_from_slice(&0u16.to_be_bytes());
        body.extend_from_slice(payload);
        let encrypted_body = key.encrypt(nonce, &body)?;

        let mut packet = Vec::with_capacity(16 + encrypted_body.len());
        packet.extend_from_slice(&encrypted_header);
        packet.extend_from_slice(&encrypted_body);
        Ok(packet)
    }

    fn encrypt_chacha(
        &self,
        packet_id: u64,
        packet_type: Aead2022UdpPacketType,
        timestamp: u64,
        client_session_id: Option<[u8; 8]>,
        payload: &[u8],
    ) -> Result<Vec<u8>, CipherError> {
        let mut nonce = [0u8; SIP022_XCHACHA_NONCE_LEN];
        rand::rng().fill(&mut nonce);
        let cipher = XChaCha20Poly1305::new_from_slice(&self.psk).map_err(|_| CipherError::KeyLength)?;

        let mut body = Vec::with_capacity(8 + 8 + 1 + 8 + client_session_id.map_or(0, |_| 8) + 2 + payload.len());
        body.extend_from_slice(&self.session_id);
        body.extend_from_slice(&packet_id.to_be_bytes());
        body.push(packet_type as u8);
        body.extend_from_slice(&timestamp.to_be_bytes());
        if let Some(id) = client_session_id {
            body.extend_from_slice(&id);
        }
        body.extend_from_slice(&0u16.to_be_bytes());
        body.extend_from_slice(payload);

        let nonce_ref = <&XNonce>::try_from(nonce.as_slice()).map_err(|_| CipherError::IvLength)?;
        let encrypted_body = cipher.encrypt(nonce_ref, body.as_slice()).map_err(|_| CipherError::AeadFailed)?;
        let mut packet = Vec::with_capacity(SIP022_XCHACHA_NONCE_LEN + encrypted_body.len());
        packet.extend_from_slice(&nonce);
        packet.extend_from_slice(&encrypted_body);
        Ok(packet)
    }
}

#[derive(Default)]
struct ReplaySessions {
    current: Option<PeerReplay>,
    previous: Option<PeerReplay>,
}

struct PeerReplay {
    session_id: [u8; 8],
    window: ReplayWindow,
    last_seen: Instant,
}

impl ReplaySessions {
    fn is_replay(&self, session_id: [u8; 8], packet_id: u64) -> bool {
        self.current
            .iter()
            .chain(self.previous.iter())
            .any(|peer| peer.session_id == session_id && peer.window.is_replay(packet_id))
    }

    fn accept(&mut self, session_id: [u8; 8], packet_id: u64, now: Instant) -> Result<(), CipherError> {
        for peer in [&mut self.current, &mut self.previous].into_iter().flatten() {
            if peer.session_id == session_id {
                peer.window.accept(packet_id)?;
                peer.last_seen = now;
                return Ok(());
            }
        }
        // Integer timestamps add up to one second to the inclusive +/-30-second acceptance horizon.
        if self.previous.as_ref().is_some_and(|peer| now.duration_since(peer.last_seen) <= SIP022_SESSION_RETENTION) {
            return Err(CipherError::InvalidPacket("too many active SIP022 UDP sessions"));
        }
        let mut window = ReplayWindow::default();
        window.accept(packet_id)?;
        self.previous = self.current.take();
        self.current = Some(PeerReplay { session_id, window, last_seen: now });
        Ok(())
    }
}

#[derive(Default)]
struct ReplayWindow {
    highest: Option<u64>,
    seen: HashSet<u64>,
}

impl ReplayWindow {
    fn is_replay(&self, packet_id: u64) -> bool {
        if self.seen.contains(&packet_id) {
            return true;
        }
        self.highest.is_some_and(|highest| highest.saturating_sub(packet_id) > SIP022_REPLAY_WINDOW)
    }

    fn accept(&mut self, packet_id: u64) -> Result<(), CipherError> {
        if self.is_replay(packet_id) {
            return Err(CipherError::Replay);
        }
        self.highest = Some(self.highest.map_or(packet_id, |highest| highest.max(packet_id)));
        self.seen.insert(packet_id);
        if let Some(highest) = self.highest {
            self.seen.retain(|seen| highest.saturating_sub(*seen) <= SIP022_REPLAY_WINDOW);
        }
        Ok(())
    }
}

fn separate_header(session_id: [u8; 8], packet_id: u64) -> [u8; 16] {
    let mut header = [0u8; 16];
    header[..8].copy_from_slice(&session_id);
    header[8..].copy_from_slice(&packet_id.to_be_bytes());
    header
}

fn encrypt_separate_header(cipher: Cipher, psk: &[u8], header: &[u8; 16]) -> Result<[u8; 16], CipherError> {
    let mut block = *header;
    match cipher {
        Cipher::Aead2022Blake3Aes128Gcm => {
            let aes = Aes128::new_from_slice(psk).map_err(|_| CipherError::KeyLength)?;
            aes.encrypt_block((&mut block).into());
        }
        Cipher::Aead2022Blake3Aes256Gcm => {
            let aes = Aes256::new_from_slice(psk).map_err(|_| CipherError::KeyLength)?;
            aes.encrypt_block((&mut block).into());
        }
        _ => return Err(CipherError::Unsupported(format!("{cipher:?}"))),
    }
    Ok(block)
}

fn decrypt_separate_header(cipher: Cipher, psk: &[u8], header: &[u8; 16]) -> Result<[u8; 16], CipherError> {
    let mut block = *header;
    match cipher {
        Cipher::Aead2022Blake3Aes128Gcm => {
            let aes = Aes128::new_from_slice(psk).map_err(|_| CipherError::KeyLength)?;
            aes.decrypt_block((&mut block).into());
        }
        Cipher::Aead2022Blake3Aes256Gcm => {
            let aes = Aes256::new_from_slice(psk).map_err(|_| CipherError::KeyLength)?;
            aes.decrypt_block((&mut block).into());
        }
        _ => return Err(CipherError::Unsupported(format!("{cipher:?}"))),
    }
    Ok(block)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_retention_covers_integer_timestamp_rounding() {
        for (cipher, credential) in [
            (Cipher::Aead2022Blake3Aes128Gcm, "AAECAwQFBgcICQoLDA0ODw=="),
            (Cipher::Aead2022Blake3Aes256Gcm, "Zml4dHVyZS1wc2stYWVzMjU2Z2NtLTMyYnl0ZWtleSE="),
            (Cipher::Aead2022Blake3Chacha20Poly1305, "Zml4dHVyZS1wc2stY2hhY2hhMjBwb2x5MTMwNS1rZXk="),
        ] {
            let session = |id| {
                Aead2022UdpSession::new(cipher, PresharedKey::from_base64(cipher, credential).expect("fixture key"), id)
                    .expect("session")
            };
            let timestamp = 1_700_000_000;
            let client_id = *b"SID-CLNT";
            let mut client = session(client_id);
            let previous = session(*b"PREVIOUS")
                .encrypt_server(timestamp + 30, client_id, b"once only")
                .expect("future timestamp packet");
            let current = session(*b"CURRENT!").encrypt_server(timestamp, client_id, b"current").expect("packet");
            for packet in [&previous, &current] {
                client.decrypt(packet, Aead2022UdpPacketType::Server, timestamp).expect("initial session");
            }
            let last_seen = Instant::now() - Duration::from_millis(60_100);
            client.replay_windows.previous.as_mut().expect("previous").last_seen = last_seen;
            client.replay_windows.current.as_mut().expect("current").last_seen = last_seen;
            let next = session(*b"NEXT-SID").encrypt_server(timestamp + 60, client_id, b"next").expect("packet");
            assert!(client.decrypt(&next, Aead2022UdpPacketType::Server, timestamp + 60).is_err());
            assert_eq!(
                client.decrypt(&previous, Aead2022UdpPacketType::Server, timestamp + 60),
                Err(CipherError::Replay)
            );
        }
    }

    #[test]
    fn invalid_packets_cannot_retire_an_expired_previous_session() {
        for (cipher, credential) in [
            (Cipher::Aead2022Blake3Aes128Gcm, "AAECAwQFBgcICQoLDA0ODw=="),
            (Cipher::Aead2022Blake3Aes256Gcm, "Zml4dHVyZS1wc2stYWVzMjU2Z2NtLTMyYnl0ZWtleSE="),
            (Cipher::Aead2022Blake3Chacha20Poly1305, "Zml4dHVyZS1wc2stY2hhY2hhMjBwb2x5MTMwNS1rZXk="),
        ] {
            let session = |id| {
                Aead2022UdpSession::new(cipher, PresharedKey::from_base64(cipher, credential).expect("fixture key"), id)
                    .expect("session")
            };
            let timestamp = 1_700_000_000;
            let client_id = *b"SID-CLNT";
            let mut client = session(client_id);
            for id in [*b"PREVIOUS", *b"CURRENT!"] {
                let packet = session(id).encrypt_server(timestamp, client_id, b"valid").expect("packet");
                client.decrypt(&packet, Aead2022UdpPacketType::Server, timestamp).expect("initial session");
            }
            client.replay_windows.previous.as_mut().expect("previous session").last_seen =
                Instant::now() - Duration::from_secs(62);
            let next = *b"NEXT-SID";
            let valid = session(next).encrypt_server(timestamp, client_id, b"new session").expect("packet");
            let mut corrupt = valid.clone();
            *corrupt.last_mut().expect("tag") ^= 1;
            let invalid = [
                corrupt,
                session(next).encrypt_server(timestamp, *b"WRONG-ID", b"wrong recipient").expect("packet"),
                session(next).encrypt_server(timestamp - 31, client_id, b"stale").expect("packet"),
                session(next).encrypt(Aead2022UdpPacketType::Client, timestamp, b"wrong type").expect("packet"),
            ];
            for packet in invalid {
                assert!(client.decrypt(&packet, Aead2022UdpPacketType::Server, timestamp).is_err());
                assert!(client.replay_windows.is_replay(*b"PREVIOUS", 0));
                assert!(client.replay_windows.is_replay(*b"CURRENT!", 0));
            }
            let decoded = client.decrypt(&valid, Aead2022UdpPacketType::Server, timestamp).expect("valid rotation");
            assert_eq!(decoded.payload, b"new session");
            assert_eq!(decoded.packet_id, 0);
            assert!(client.replay_windows.is_replay(*b"CURRENT!", 0));
        }
    }

    #[test]
    fn session_rotation_retains_previous_until_strictly_after_inactivity_horizon() {
        let start = Instant::now();
        let mut sessions = ReplaySessions::default();
        let previous = *b"PREVIOUS";
        let current = *b"CURRENT!";
        let next = *b"NEXT-SID";
        sessions.accept(previous, 0, start).expect("first session");
        sessions.accept(current, 0, start + Duration::from_secs(1)).expect("second session");
        sessions.accept(previous, 1, start + Duration::from_secs(10)).expect("previous still active");
        sessions.accept(current, 1, start + Duration::from_secs(50)).expect("current still active");

        for elapsed in [61, 71] {
            assert!(sessions.accept(next, 0, start + Duration::from_secs(elapsed)).is_err());
            assert!(sessions.is_replay(previous, 0));
            assert!(sessions.is_replay(previous, 1));
            assert!(sessions.is_replay(current, 0));
        }
        let expired = start + Duration::from_secs(71) + Duration::from_nanos(1);
        assert_eq!(sessions.accept(previous, 1, expired), Err(CipherError::Replay));
        sessions.accept(next, 0, expired).expect("duplicate did not extend retention");
        assert!(sessions.is_replay(current, 0));
        assert!(sessions.is_replay(current, 1));
        assert!(sessions.is_replay(next, 0));
        assert!(sessions.accept(*b"FOURTH!!", 0, expired).is_err());
    }

    #[test]
    fn replay_window_handles_maximum_packet_id() {
        let mut window = ReplayWindow::default();
        window.accept(u64::MAX).expect("maximum packet ID");
        assert!(window.is_replay(u64::MAX));
        assert!(!window.is_replay(u64::MAX - SIP022_REPLAY_WINDOW));
        assert!(window.is_replay(u64::MAX - SIP022_REPLAY_WINDOW - 1));
        window.accept(u64::MAX - SIP022_REPLAY_WINDOW).expect("window boundary");
        assert_eq!(window.seen.len(), 2);
    }
}
