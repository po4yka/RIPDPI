//! Mieru segment metadata — the fixed 32-byte header that prefixes every
//! segment's (encrypted) payload. Byte-exact transcription of upstream
//! `pkg/protocol/metadata.go`; see `PROTOCOL.md` §2.
//!
//! Time enters only through caller-supplied `unix_secs` (the relay's
//! network-time source), never `SystemTime::now()` — the replay clock must not
//! depend on the device wall clock.

use crate::error::{MieruError, Result};

/// Every metadata header is exactly 32 bytes on the wire.
pub(crate) const METADATA_LEN: usize = 32;
// Pinned upstream v3.36.0 sessionStruct.Unmarshal applies this to all four
// session header types, before allocating or reading the encrypted payload.
const MAX_SESSION_OPEN_PAYLOAD: u16 = 1024;

/// Mieru protocol type (metadata byte 0). Values are wire-stable (upstream
/// `protocolType` constants 0..=9).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ProtocolType {
    CloseConnRequest = 0,
    CloseConnResponse = 1,
    OpenSessionRequest = 2,
    OpenSessionResponse = 3,
    CloseSessionRequest = 4,
    CloseSessionResponse = 5,
    DataClientToServer = 6,
    DataServerToClient = 7,
    AckClientToServer = 8,
    AckServerToClient = 9,
}

impl ProtocolType {
    pub(crate) fn as_u8(self) -> u8 {
        self as u8
    }

    pub(crate) fn from_u8(value: u8) -> Result<Self> {
        Ok(match value {
            0 => Self::CloseConnRequest,
            1 => Self::CloseConnResponse,
            2 => Self::OpenSessionRequest,
            3 => Self::OpenSessionResponse,
            4 => Self::CloseSessionRequest,
            5 => Self::CloseSessionResponse,
            6 => Self::DataClientToServer,
            7 => Self::DataServerToClient,
            8 => Self::AckClientToServer,
            9 => Self::AckServerToClient,
            other => return Err(MieruError::Protocol(format!("unknown protocol type {other}"))),
        })
    }

    /// Whether this type uses the session metadata layout (open/close session).
    pub(crate) fn is_session(self) -> bool {
        matches!(
            self,
            Self::OpenSessionRequest
                | Self::OpenSessionResponse
                | Self::CloseSessionRequest
                | Self::CloseSessionResponse
        )
    }
}

/// `timestamp` field = unix seconds at minute granularity.
fn minute_timestamp(unix_secs: i64) -> u32 {
    #[allow(clippy::cast_sign_loss, clippy::cast_possible_truncation)]
    // device epochs fit u32-of-minutes until year ~10136.
    let minutes = unix_secs.div_euclid(60) as u32;
    minutes
}

/// Recover an estimated Unix-seconds timestamp from any decrypted metadata
/// header (bytes 2..6, the minute-granularity timestamp common to both the data
/// and session layouts). Used to calibrate the network-time provider from a
/// server segment. The estimate adds 30 s (mid-minute) to centre the ±60 s
/// quantisation introduced by minute granularity.
pub(crate) fn timestamp_estimate_unix(meta: &[u8; METADATA_LEN]) -> i64 {
    let minutes = u32::from_be_bytes([meta[2], meta[3], meta[4], meta[5]]);
    i64::from(minutes) * 60 + 30
}

/// The session id (bytes 6..10) carried by every segment, in both the data and
/// session layouts. Used by the connection multiplexer to route an inbound
/// segment to the owning sub-session.
pub(crate) fn session_id_of(meta: &[u8; METADATA_LEN]) -> u32 {
    u32::from_be_bytes([meta[6], meta[7], meta[8], meta[9]])
}

/// Metadata for data / ack segments (`dataAckStruct`, types 6..=9). Offsets per
/// `PROTOCOL.md` §2.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DataAckMeta {
    pub(crate) protocol: ProtocolType,
    pub(crate) session_id: u32,
    pub(crate) seq: u32,
    pub(crate) unack_seq: u32,
    pub(crate) window_size: u16,
    pub(crate) fragment: u8,
    pub(crate) prefix_len: u8,
    pub(crate) payload_len: u16,
    pub(crate) suffix_len: u8,
}

impl DataAckMeta {
    pub(crate) fn marshal(&self, unix_secs: i64) -> [u8; METADATA_LEN] {
        let mut b = [0u8; METADATA_LEN];
        b[0] = self.protocol.as_u8();
        b[2..6].copy_from_slice(&minute_timestamp(unix_secs).to_be_bytes());
        b[6..10].copy_from_slice(&self.session_id.to_be_bytes());
        b[10..14].copy_from_slice(&self.seq.to_be_bytes());
        b[14..18].copy_from_slice(&self.unack_seq.to_be_bytes());
        b[18..20].copy_from_slice(&self.window_size.to_be_bytes());
        b[20] = self.fragment;
        b[21] = self.prefix_len;
        b[22..24].copy_from_slice(&self.payload_len.to_be_bytes());
        b[24] = self.suffix_len;
        b
    }

    pub(crate) fn unmarshal(b: &[u8; METADATA_LEN]) -> Result<Self> {
        let protocol = ProtocolType::from_u8(b[0])?;
        if protocol.is_session() {
            return Err(MieruError::Protocol("session metadata in data slot".to_owned()));
        }
        Ok(Self {
            protocol,
            session_id: u32::from_be_bytes([b[6], b[7], b[8], b[9]]),
            seq: u32::from_be_bytes([b[10], b[11], b[12], b[13]]),
            unack_seq: u32::from_be_bytes([b[14], b[15], b[16], b[17]]),
            window_size: u16::from_be_bytes([b[18], b[19]]),
            fragment: b[20],
            prefix_len: b[21],
            payload_len: u16::from_be_bytes([b[22], b[23]]),
            suffix_len: b[24],
        })
    }
}

/// Metadata for open / close session segments (`sessionStruct`, types 2..=5).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SessionMeta {
    pub(crate) protocol: ProtocolType,
    pub(crate) session_id: u32,
    pub(crate) seq: u32,
    pub(crate) status_code: u8,
    pub(crate) payload_len: u16,
    pub(crate) suffix_len: u8,
}

impl SessionMeta {
    pub(crate) fn marshal(&self, unix_secs: i64) -> [u8; METADATA_LEN] {
        let mut b = [0u8; METADATA_LEN];
        b[0] = self.protocol.as_u8();
        b[2..6].copy_from_slice(&minute_timestamp(unix_secs).to_be_bytes());
        b[6..10].copy_from_slice(&self.session_id.to_be_bytes());
        b[10..14].copy_from_slice(&self.seq.to_be_bytes());
        b[14] = self.status_code;
        b[15..17].copy_from_slice(&self.payload_len.to_be_bytes());
        b[17] = self.suffix_len;
        b
    }

    pub(crate) fn unmarshal(b: &[u8; METADATA_LEN]) -> Result<Self> {
        let protocol = ProtocolType::from_u8(b[0])?;
        if !protocol.is_session() {
            return Err(MieruError::Protocol("data metadata in session slot".to_owned()));
        }
        let payload_len = u16::from_be_bytes([b[15], b[16]]);
        if payload_len > MAX_SESSION_OPEN_PAYLOAD {
            return Err(MieruError::Protocol(format!(
                "session payload {payload_len} exceeds {MAX_SESSION_OPEN_PAYLOAD}"
            )));
        }
        Ok(Self {
            protocol,
            session_id: u32::from_be_bytes([b[6], b[7], b[8], b[9]]),
            seq: u32::from_be_bytes([b[10], b[11], b[12], b[13]]),
            status_code: b[14],
            payload_len,
            suffix_len: b[17],
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_type_round_trips_all_values() {
        for v in 0u8..=9 {
            assert_eq!(ProtocolType::from_u8(v).unwrap().as_u8(), v);
        }
        assert!(ProtocolType::from_u8(10).is_err());
    }

    #[test]
    fn data_meta_round_trips_with_exact_offsets() {
        let meta = DataAckMeta {
            protocol: ProtocolType::DataClientToServer,
            session_id: 0x0102_0304,
            seq: 0x1112_1314,
            unack_seq: 0x2122_2324,
            window_size: 0x3132,
            fragment: 0x41,
            prefix_len: 0x05,
            payload_len: 0x5152,
            suffix_len: 0x07,
        };
        let b = meta.marshal(1_000_000_020); // 16666667 minutes
        assert_eq!(b[0], 6);
        assert_eq!(&b[6..10], &[0x01, 0x02, 0x03, 0x04]); // session id, big-endian
        assert_eq!(&b[22..24], &[0x51, 0x52]); // payload len, big-endian
        assert_eq!(b.len(), METADATA_LEN);
        assert_eq!(DataAckMeta::unmarshal(&b).unwrap(), meta);
    }

    #[test]
    fn session_meta_round_trips() {
        let meta = SessionMeta {
            protocol: ProtocolType::OpenSessionRequest,
            session_id: 0xdead_beef,
            seq: 1,
            status_code: 0,
            payload_len: 7,
            suffix_len: 3,
        };
        let b = meta.marshal(1_000_000_000);
        assert_eq!(b[0], 2);
        assert_eq!(b[14], 0); // status code
        assert_eq!(SessionMeta::unmarshal(&b).unwrap(), meta);
    }

    #[test]
    fn session_payload_matches_pinned_upstream_1024_byte_bound() {
        for protocol in [
            ProtocolType::OpenSessionRequest,
            ProtocolType::OpenSessionResponse,
            ProtocolType::CloseSessionRequest,
            ProtocolType::CloseSessionResponse,
        ] {
            for payload_len in [0, 1024, 1025, u16::MAX] {
                let meta = SessionMeta { protocol, session_id: 1, seq: 1, status_code: 0, payload_len, suffix_len: 0 };
                let parsed = SessionMeta::unmarshal(&meta.marshal(1_000_000_000));
                assert_eq!(parsed.is_ok(), payload_len <= 1024, "{protocol:?} payload={payload_len}");
            }
        }
    }

    #[test]
    fn data_and_session_layouts_do_not_alias() {
        let b = DataAckMeta {
            protocol: ProtocolType::DataClientToServer,
            session_id: 1,
            seq: 1,
            unack_seq: 0,
            window_size: 0,
            fragment: 0,
            prefix_len: 0,
            payload_len: 0,
            suffix_len: 0,
        }
        .marshal(1_000_000_000);
        assert!(SessionMeta::unmarshal(&b).is_err(), "data type must not parse as session");
    }
}
