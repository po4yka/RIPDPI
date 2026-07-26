//! yamux (hashicorp) wire-multiplexing frame codec.
//!
//! yamux runs many logical streams over one connection using a fixed 12-byte
//! header in front of every frame:
//!
//! ```text
//! 0       1       2               4                       8                      12
//! +-------+-------+---------------+-----------------------+-----------------------+
//! | ver   | type  | flags (u16 BE)| stream id (u32 BE)    | length (u32 BE)       |
//! +-------+-------+---------------+-----------------------+-----------------------+
//! ```
//!
//! * **ver** is always `0` for the protocol version this codec speaks.
//! * **type** selects the frame kind: `Data`, `WindowUpdate`, `Ping`, `GoAway`.
//! * **flags** is a bitfield: `SYN`, `ACK`, `FIN`, `RST`.
//! * **stream id** is the logical stream the frame belongs to. Client-opened
//!   streams use odd ids, server-opened streams use even ids; id `0` is the
//!   session control stream used by `Ping` / `GoAway`.
//! * **length** means different things per type: for `Data` it is the number
//!   of payload bytes that follow the header; for `WindowUpdate` it is the
//!   window delta and *no* payload follows; for `Ping` it is the opaque ping
//!   value; for `GoAway` it is the termination reason code.
//!
//! This module is a pure codec: [`YamuxHeader`] plus [`YamuxFrame`] encode and
//! decode the wire form with no I/O and no allocation beyond the payload
//! `Vec`. Higher layers ([`super::session`]) drive stream-id allocation,
//! flow-control windows, and keepalive on top of it.

/// Length of the fixed yamux frame header, in bytes.
pub const HEADER_LEN: usize = 12;

/// The only yamux protocol version this codec implements.
pub const PROTOCOL_VERSION: u8 = 0;

/// Default initial flow-control window a peer grants a new stream (256 KiB),
/// matching the hashicorp yamux default.
pub const INITIAL_STREAM_WINDOW: u32 = 256 * 1024;

/// yamux frame types (the `type` header byte).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameType {
    /// Carries `length` payload bytes for a stream.
    Data,
    /// Adjusts a stream's receive window by `length` (no payload follows).
    WindowUpdate,
    /// Liveness probe / round-trip measurement; `length` is the opaque value.
    Ping,
    /// Terminates the whole session; `length` is the reason code.
    GoAway,
}

impl FrameType {
    /// Map the wire `type` byte to a [`FrameType`].
    pub fn from_wire(value: u8) -> Result<Self, YamuxCodecError> {
        match value {
            0 => Ok(Self::Data),
            1 => Ok(Self::WindowUpdate),
            2 => Ok(Self::Ping),
            3 => Ok(Self::GoAway),
            other => Err(YamuxCodecError::UnknownFrameType(other)),
        }
    }

    /// The wire `type` byte for this frame type.
    pub fn to_wire(self) -> u8 {
        match self {
            Self::Data => 0,
            Self::WindowUpdate => 1,
            Self::Ping => 2,
            Self::GoAway => 3,
        }
    }
}

/// `GoAway` reason codes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GoAwayReason {
    /// Graceful shutdown, no error.
    Normal,
    /// The peer committed a protocol violation.
    ProtocolError,
    /// The peer ran out of an internal resource.
    InternalError,
}

impl GoAwayReason {
    /// The wire `length`-field code for this reason.
    pub fn to_code(self) -> u32 {
        match self {
            Self::Normal => 0,
            Self::ProtocolError => 1,
            Self::InternalError => 2,
        }
    }

    /// Map a wire reason code back to a [`GoAwayReason`]; unknown codes map to
    /// [`GoAwayReason::InternalError`] so a peer can never wedge the decoder.
    pub fn from_code(code: u32) -> Self {
        match code {
            0 => Self::Normal,
            1 => Self::ProtocolError,
            _ => Self::InternalError,
        }
    }
}

/// yamux header flag bits.
pub mod flags {
    /// Begins a new stream.
    pub const SYN: u16 = 0x1;
    /// Acknowledges a new stream.
    pub const ACK: u16 = 0x2;
    /// Half-closes a stream (no more data from this side).
    pub const FIN: u16 = 0x4;
    /// Hard-resets a stream immediately.
    pub const RST: u16 = 0x8;
    /// All flag bits this codec recognizes.
    pub const ALL: u16 = SYN | ACK | FIN | RST;
}

/// Errors produced while encoding or decoding yamux frames.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum YamuxCodecError {
    /// The buffer is shorter than a complete 12-byte header.
    #[error("yamux header needs {HEADER_LEN} bytes, buffer has {0}")]
    ShortHeader(usize),
    /// The header declared a protocol version this codec does not speak.
    #[error("unsupported yamux protocol version {0}, expected {PROTOCOL_VERSION}")]
    UnsupportedVersion(u8),
    /// The `type` header byte is not a known frame type.
    #[error("unknown yamux frame type {0}")]
    UnknownFrameType(u8),
    /// The `flags` header field carried bits outside the known set.
    #[error("unknown yamux flag bits {0:#06x}")]
    UnknownFlags(u16),
    /// A `Data` frame's declared payload length exceeded the configured cap.
    #[error("yamux Data frame length {len} exceeds maximum {max}")]
    DataTooLarge {
        /// Length declared in the header.
        len: u32,
        /// Configured maximum payload length.
        max: u32,
    },
}

/// A decoded yamux frame header (no payload).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct YamuxHeader {
    /// Frame kind.
    pub frame_type: FrameType,
    /// Flag bitfield (`SYN`/`ACK`/`FIN`/`RST`).
    pub flags: u16,
    /// Logical stream id (`0` = session control stream).
    pub stream_id: u32,
    /// Type-dependent length field. For `Data` this is the payload byte
    /// count; for the other types it is a value, not a length.
    pub length: u32,
}

impl YamuxHeader {
    /// Build a header with no flags set.
    pub fn new(frame_type: FrameType, stream_id: u32, length: u32) -> Self {
        Self { frame_type, flags: 0, stream_id, length }
    }

    /// Return a copy of this header with `flags` OR-ed into the flag field.
    pub fn with_flags(mut self, flags: u16) -> Self {
        self.flags |= flags;
        self
    }

    /// True when `flag` (a `flags::*` constant) is set.
    pub fn has_flag(&self, flag: u16) -> bool {
        self.flags & flag != 0
    }

    /// Serialize the 12-byte header into `out`.
    pub fn encode(&self, out: &mut [u8; HEADER_LEN]) {
        out[0] = PROTOCOL_VERSION;
        out[1] = self.frame_type.to_wire();
        out[2..4].copy_from_slice(&self.flags.to_be_bytes());
        out[4..8].copy_from_slice(&self.stream_id.to_be_bytes());
        out[8..12].copy_from_slice(&self.length.to_be_bytes());
    }

    /// Serialize the header to a fresh 12-byte array.
    pub fn to_bytes(&self) -> [u8; HEADER_LEN] {
        let mut out = [0u8; HEADER_LEN];
        self.encode(&mut out);
        out
    }

    /// Decode a 12-byte header from the front of `buf`.
    ///
    /// Validates the protocol version and that no unknown flag bits are set,
    /// so a hostile peer cannot smuggle undefined semantics past the decoder.
    pub fn decode(buf: &[u8]) -> Result<Self, YamuxCodecError> {
        if buf.len() < HEADER_LEN {
            return Err(YamuxCodecError::ShortHeader(buf.len()));
        }
        let version = buf[0];
        if version != PROTOCOL_VERSION {
            return Err(YamuxCodecError::UnsupportedVersion(version));
        }
        let frame_type = FrameType::from_wire(buf[1])?;
        let flags = u16::from_be_bytes([buf[2], buf[3]]);
        if flags & !flags::ALL != 0 {
            return Err(YamuxCodecError::UnknownFlags(flags));
        }
        let stream_id = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
        let length = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]);
        Ok(Self { frame_type, flags, stream_id, length })
    }
}

/// A fully decoded yamux frame: header plus, for `Data` frames, the payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct YamuxFrame {
    /// The frame header.
    pub header: YamuxHeader,
    /// Payload bytes. Always empty for non-`Data` frames.
    pub payload: Vec<u8>,
}

impl YamuxFrame {
    /// Build a `Data` frame for `stream_id` carrying `payload`.
    pub fn data(stream_id: u32, payload: impl Into<Vec<u8>>) -> Self {
        let payload = payload.into();
        let header = YamuxHeader::new(FrameType::Data, stream_id, payload.len() as u32);
        Self { header, payload }
    }

    /// Build a `WindowUpdate` frame granting `delta` more receive window to
    /// `stream_id`.
    pub fn window_update(stream_id: u32, delta: u32) -> Self {
        Self { header: YamuxHeader::new(FrameType::WindowUpdate, stream_id, delta), payload: Vec::new() }
    }

    /// Build a `Ping` frame carrying the opaque `value`. `ack` selects the
    /// `ACK` flag (a reply) versus a fresh probe.
    pub fn ping(value: u32, ack: bool) -> Self {
        let mut header = YamuxHeader::new(FrameType::Ping, 0, value);
        if ack {
            header = header.with_flags(flags::ACK);
        }
        Self { header, payload: Vec::new() }
    }

    /// Build a `GoAway` frame terminating the session with `reason`.
    pub fn go_away(reason: GoAwayReason) -> Self {
        Self { header: YamuxHeader::new(FrameType::GoAway, 0, reason.to_code()), payload: Vec::new() }
    }

    /// Apply a flag bitfield to this frame's header (builder style).
    pub fn with_flags(mut self, flags: u16) -> Self {
        self.header = self.header.with_flags(flags);
        self
    }

    /// Serialize the frame (header + payload) into `out`.
    pub fn encode(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.header.to_bytes());
        if self.header.frame_type == FrameType::Data {
            out.extend_from_slice(&self.payload);
        }
    }

    /// Serialize the frame to a fresh `Vec`.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN + self.payload.len());
        self.encode(&mut out);
        out
    }
}

/// Incremental yamux frame decoder.
///
/// Wire bytes are pushed in via [`YamuxDecoder::extend`] as they arrive;
/// complete frames are pulled out via [`YamuxDecoder::next_frame`]. A partial
/// header or partial `Data` payload is retained until enough bytes arrive, so
/// the caller never has to reason about frame boundaries on the socket.
#[derive(Debug)]
pub struct YamuxDecoder {
    buffer: Vec<u8>,
    /// Cap on a single `Data` frame's payload. Protects the decoder from a
    /// hostile length field demanding an unbounded allocation.
    max_data_len: u32,
}

impl Default for YamuxDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl YamuxDecoder {
    /// Create a decoder with a 16 MiB `Data` frame cap.
    pub fn new() -> Self {
        Self { buffer: Vec::new(), max_data_len: 16 * 1024 * 1024 }
    }

    /// Create a decoder with a custom `Data` frame payload cap.
    pub fn with_max_data_len(max_data_len: u32) -> Self {
        Self { buffer: Vec::new(), max_data_len }
    }

    /// Append freshly received wire bytes to the decode buffer.
    pub fn extend(&mut self, bytes: &[u8]) {
        self.buffer.extend_from_slice(bytes);
    }

    /// True when no buffered bytes remain (all complete frames consumed).
    pub fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }

    /// Number of bytes currently buffered (a partial frame in flight).
    pub fn buffered_len(&self) -> usize {
        self.buffer.len()
    }

    /// Try to decode the next complete frame.
    ///
    /// Returns `Ok(None)` when the buffer holds only a partial frame; the
    /// partial bytes are kept for the next call. Returns `Err` only on a
    /// genuinely malformed or oversized frame.
    pub fn next_frame(&mut self) -> Result<Option<YamuxFrame>, YamuxCodecError> {
        if self.buffer.len() < HEADER_LEN {
            return Ok(None);
        }
        let header = YamuxHeader::decode(&self.buffer)?;
        let payload_len = if header.frame_type == FrameType::Data {
            if header.length > self.max_data_len {
                return Err(YamuxCodecError::DataTooLarge { len: header.length, max: self.max_data_len });
            }
            header.length as usize
        } else {
            0
        };
        let total = HEADER_LEN + payload_len;
        if self.buffer.len() < total {
            // Partial frame: wait for more bytes.
            return Ok(None);
        }
        let payload = self.buffer[HEADER_LEN..total].to_vec();
        self.buffer.drain(..total);
        Ok(Some(YamuxFrame { header, payload }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_type_round_trips_through_wire_bytes() {
        for ty in [FrameType::Data, FrameType::WindowUpdate, FrameType::Ping, FrameType::GoAway] {
            assert_eq!(FrameType::from_wire(ty.to_wire()).expect("known type"), ty);
        }
    }

    #[test]
    fn unknown_frame_type_is_rejected() {
        assert_eq!(FrameType::from_wire(9), Err(YamuxCodecError::UnknownFrameType(9)));
    }

    #[test]
    fn header_encodes_to_documented_12_byte_layout() {
        // SYN Data on stream 1, length 5 -> matches the hashicorp wire layout.
        let header = YamuxHeader::new(FrameType::Data, 1, 5).with_flags(flags::SYN);
        let bytes = header.to_bytes();
        assert_eq!(bytes[0], PROTOCOL_VERSION, "version byte");
        assert_eq!(bytes[1], 0, "Data type byte");
        assert_eq!(u16::from_be_bytes([bytes[2], bytes[3]]), flags::SYN);
        assert_eq!(u32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]), 1);
        assert_eq!(u32::from_be_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]), 5);
    }

    #[test]
    fn header_round_trips_through_encode_decode() {
        let original = YamuxHeader::new(FrameType::WindowUpdate, 7, INITIAL_STREAM_WINDOW).with_flags(flags::ACK);
        let decoded = YamuxHeader::decode(&original.to_bytes()).expect("decode");
        assert_eq!(decoded, original);
        assert!(decoded.has_flag(flags::ACK));
        assert!(!decoded.has_flag(flags::SYN));
    }

    #[test]
    fn header_decode_rejects_short_buffer() {
        assert_eq!(YamuxHeader::decode(&[0u8; 8]), Err(YamuxCodecError::ShortHeader(8)));
    }

    #[test]
    fn header_decode_rejects_wrong_version() {
        let mut bytes = YamuxHeader::new(FrameType::Ping, 0, 0).to_bytes();
        bytes[0] = 9;
        assert_eq!(YamuxHeader::decode(&bytes), Err(YamuxCodecError::UnsupportedVersion(9)));
    }

    #[test]
    fn header_decode_rejects_unknown_flag_bits() {
        let mut bytes = YamuxHeader::new(FrameType::Data, 1, 0).to_bytes();
        bytes[2..4].copy_from_slice(&0xFF00u16.to_be_bytes());
        assert_eq!(YamuxHeader::decode(&bytes), Err(YamuxCodecError::UnknownFlags(0xFF00)));
    }

    #[test]
    fn data_frame_round_trips_payload() {
        let frame = YamuxFrame::data(3, b"the quick brown fox".to_vec()).with_flags(flags::SYN);
        let bytes = frame.to_bytes();
        let mut decoder = YamuxDecoder::new();
        decoder.extend(&bytes);
        let decoded = decoder.next_frame().expect("decode ok").expect("one frame");
        assert_eq!(decoded, frame);
        assert_eq!(decoded.payload, b"the quick brown fox");
        assert!(decoder.is_empty());
    }

    #[test]
    fn window_update_frame_carries_no_payload() {
        let frame = YamuxFrame::window_update(5, 65536);
        assert!(frame.payload.is_empty());
        let bytes = frame.to_bytes();
        assert_eq!(bytes.len(), HEADER_LEN, "WindowUpdate is header-only");
        let mut decoder = YamuxDecoder::new();
        decoder.extend(&bytes);
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.header.frame_type, FrameType::WindowUpdate);
        assert_eq!(decoded.header.length, 65536);
    }

    #[test]
    fn ping_frame_sets_ack_flag_on_reply() {
        let probe = YamuxFrame::ping(0xDEAD, false);
        assert!(!probe.header.has_flag(flags::ACK));
        let reply = YamuxFrame::ping(0xDEAD, true);
        assert!(reply.header.has_flag(flags::ACK));
        assert_eq!(reply.header.length, 0xDEAD);
    }

    #[test]
    fn go_away_reason_round_trips() {
        for reason in [GoAwayReason::Normal, GoAwayReason::ProtocolError, GoAwayReason::InternalError] {
            assert_eq!(GoAwayReason::from_code(reason.to_code()), reason);
        }
        // Unknown codes degrade to InternalError rather than wedging the decoder.
        assert_eq!(GoAwayReason::from_code(999), GoAwayReason::InternalError);
    }

    #[test]
    fn multi_frame_stream_decodes_in_order() {
        let frames = [
            YamuxFrame::data(1, b"first".to_vec()).with_flags(flags::SYN),
            YamuxFrame::window_update(1, 1024),
            YamuxFrame::data(1, b"second".to_vec()),
            YamuxFrame::data(1, b"third".to_vec()).with_flags(flags::FIN),
        ];
        let mut wire = Vec::new();
        for frame in &frames {
            frame.encode(&mut wire);
        }
        let mut decoder = YamuxDecoder::new();
        decoder.extend(&wire);
        for expected in &frames {
            let decoded = decoder.next_frame().expect("decode ok").expect("a frame");
            assert_eq!(&decoded, expected);
        }
        assert!(decoder.next_frame().expect("decode ok").is_none());
    }

    #[test]
    fn partial_data_frame_is_buffered_until_complete() {
        let frame = YamuxFrame::data(9, b"deferred payload".to_vec());
        let bytes = frame.to_bytes();
        let split = bytes.len() / 2;
        let mut decoder = YamuxDecoder::new();

        decoder.extend(&bytes[..split]);
        assert!(decoder.next_frame().expect("decode ok").is_none(), "half a frame is not ready");

        decoder.extend(&bytes[split..]);
        let decoded = decoder.next_frame().expect("decode ok").expect("now complete");
        assert_eq!(decoded, frame);
    }

    #[test]
    fn byte_at_a_time_feed_still_decodes() {
        let frame = YamuxFrame::data(11, b"streamed one byte at a time".to_vec());
        let bytes = frame.to_bytes();
        let mut decoder = YamuxDecoder::new();
        let mut result = None;
        for byte in &bytes {
            decoder.extend(std::slice::from_ref(byte));
            if let Some(decoded) = decoder.next_frame().expect("decode ok") {
                result = Some(decoded);
            }
        }
        assert_eq!(result.expect("eventually decoded"), frame);
    }

    #[test]
    fn oversized_data_frame_is_rejected() {
        let mut decoder = YamuxDecoder::with_max_data_len(16);
        let header = YamuxHeader::new(FrameType::Data, 1, 17);
        decoder.extend(&header.to_bytes());
        let err = decoder.next_frame().expect_err("oversized must be rejected");
        assert_eq!(err, YamuxCodecError::DataTooLarge { len: 17, max: 16 });
    }

    /// Cross-checks the codec against a hand-assembled wire frame, the way a
    /// port of the hashicorp yamux test vectors would: a SYN Data frame on
    /// stream id 1 carrying the 3 bytes `abc`.
    #[test]
    fn decodes_hand_assembled_upstream_style_vector() {
        let wire: [u8; HEADER_LEN + 3] = [
            0x00, // version 0
            0x00, // type Data
            0x00, 0x01, // flags SYN
            0x00, 0x00, 0x00, 0x01, // stream id 1
            0x00, 0x00, 0x00, 0x03, // length 3
            b'a', b'b', b'c', // payload
        ];
        let mut decoder = YamuxDecoder::new();
        decoder.extend(&wire);
        let frame = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(frame.header.frame_type, FrameType::Data);
        assert!(frame.header.has_flag(flags::SYN));
        assert_eq!(frame.header.stream_id, 1);
        assert_eq!(frame.payload, b"abc");
    }

    /// Conformance-fixture harness for upstream-supplied yamux frame
    /// vectors. Walks every `.bin` file under the pinned upstream tag:
    /// `contract-fixtures/vless/<tag>/mux/yamux/`, then asserts each
    /// file decodes cleanly and re-encodes byte-for-byte.
    ///
    /// Tracks the upstream-conformance side of
    /// completed task `add-vless-mux-conformance-tests-against-xray-core` (see git history).
    #[test]
    fn upstream_yamux_fixtures_round_trip() {
        let repo_root = golden_test_support::repo_root();
        let upstream_tag = pinned_upstream_tag(&repo_root.join("native/rust/crates/ripdpi-vless/SPEC_VERSION.md"));
        let yamux_dir = repo_root.join("contract-fixtures/vless").join(upstream_tag).join("mux").join("yamux");
        assert!(yamux_dir.is_dir(), "missing pinned yamux fixture dir: {yamux_dir:?}");

        let mut count = 0usize;
        for entry in std::fs::read_dir(&yamux_dir).expect("read yamux fixture dir") {
            let entry = entry.expect("dir entry");
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) != Some("bin") {
                continue;
            }
            let wire = std::fs::read(&path).expect("read fixture");
            if wire.len() < HEADER_LEN {
                panic!("fixture {path:?} shorter than yamux header");
            }
            let header =
                YamuxHeader::decode(&wire[..HEADER_LEN]).unwrap_or_else(|err| panic!("decode {path:?}: {err:?}"));
            let mut re_encoded = [0u8; HEADER_LEN];
            header.encode(&mut re_encoded);
            assert_eq!(&wire[..HEADER_LEN], &re_encoded[..], "yamux header round-trip mismatch in fixture {path:?}",);
            count += 1;
        }
        assert!(count > 0, "no pinned yamux fixtures found under {yamux_dir:?}");
        eprintln!("upstream_yamux_fixtures_round_trip: exercised {count} fixtures");
    }

    fn pinned_upstream_tag(spec_version: &std::path::Path) -> String {
        let spec = std::fs::read_to_string(spec_version).expect("read SPEC_VERSION.md");
        spec.lines()
            .find_map(|line| line.strip_prefix("- **Upstream tag:**"))
            .and_then(|value| value.split_whitespace().next())
            .expect("upstream tag in SPEC_VERSION.md")
            .to_string()
    }
}
