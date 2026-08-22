//! sing-mux (sing-box) wire-multiplexing frame codec.
//!
//! sing-mux is sing-box's own stream multiplexer. A session opens with a
//! single **version byte** (`1` for the base protocol, `2` once padding /
//! Brutal congestion-control extensions are negotiated). After that, the
//! connection carries a sequence of length-delimited frames:
//!
//! ```text
//! +-----------------------+-------+-----------------------+-----------+
//! | stream id (u32 BE)    | cmd   | data length (u16 BE)  | data ...  |
//! +-----------------------+-------+-----------------------+-----------+
//!     4 bytes               1       2                       N
//! ```
//!
//! * **stream id** is the logical substream the frame belongs to. Stream id
//!   allocation is monotonic and the opener's responsibility.
//! * **cmd** selects the frame kind: `New` opens a substream (its `data` is
//!   the destination request), `Data` carries payload, `Close` half-closes a
//!   substream, `KeepAlive` is a session-level liveness probe.
//! * **data length** + **data** carry the per-frame payload. `KeepAlive`
//!   frames always carry zero-length data.
//!
//! When the `2` version is negotiated, `New` and `Data` frames may be padded:
//! a `u16` pad length followed by that many discard bytes is appended after
//! `data`. [`PaddingMode`] controls whether this codec emits padding;
//! decoding always tolerates it.
//!
//! This module is a pure codec with no I/O. Higher layers ([`super::session`])
//! drive stream-id allocation, keepalive scheduling, and per-stream
//! backpressure on top of it.

use rand::RngExt;

/// sing-mux base protocol version (no extensions).
pub const VERSION_BASE: u8 = 1;
/// sing-mux protocol version with padding / Brutal extensions negotiated.
pub const VERSION_PADDED: u8 = 2;

/// Fixed prefix length of a sing-mux frame: stream id (4) + cmd (1) + data
/// length (2).
pub const FRAME_PREFIX_LEN: usize = 7;

/// sing-mux frame commands (the `cmd` byte).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    /// Opens a new substream; `data` carries the destination request.
    New,
    /// Carries payload bytes for an existing substream.
    Data,
    /// Half-closes a substream (no more data from this side).
    Close,
    /// Session-level liveness probe; `data` is always empty.
    KeepAlive,
}

impl Command {
    /// Map the wire `cmd` byte to a [`Command`].
    pub fn from_wire(value: u8) -> Result<Self, SingMuxCodecError> {
        match value {
            0 => Ok(Self::New),
            1 => Ok(Self::Data),
            2 => Ok(Self::Close),
            3 => Ok(Self::KeepAlive),
            other => Err(SingMuxCodecError::UnknownCommand(other)),
        }
    }

    /// The wire `cmd` byte for this command.
    pub fn to_wire(self) -> u8 {
        match self {
            Self::New => 0,
            Self::Data => 1,
            Self::Close => 2,
            Self::KeepAlive => 3,
        }
    }
}

/// Whether the encoder appends sing-mux v2 padding to `New` / `Data` frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PaddingMode {
    /// Never emit padding (sing-mux v1 wire form). The default.
    #[default]
    Disabled,
    /// Emit a random amount of padding (1..=`max`) on each `New` / `Data`
    /// frame, the sing-mux v2 anti-fingerprinting behavior.
    Enabled {
        /// Upper bound on the random pad length added to a frame.
        max: u16,
    },
}

impl PaddingMode {
    /// The session version byte implied by this padding mode.
    pub fn version_byte(self) -> u8 {
        match self {
            Self::Disabled => VERSION_BASE,
            Self::Enabled { .. } => VERSION_PADDED,
        }
    }

    /// Pick a pad length for one frame. `Disabled` always yields `0`.
    fn pad_len(self) -> u16 {
        match self {
            Self::Disabled | Self::Enabled { max: 0 } => 0,
            Self::Enabled { max } => rand::rng().random_range(1..=max),
        }
    }
}

/// Errors produced while encoding or decoding sing-mux frames.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SingMuxCodecError {
    /// The `cmd` byte is not a known command.
    #[error("unknown sing-mux command {0}")]
    UnknownCommand(u8),
    /// A frame declared a `data` length larger than the configured cap.
    #[error("sing-mux frame data length {len} exceeds maximum {max}")]
    DataTooLarge {
        /// Length declared in the frame prefix.
        len: u16,
        /// Configured maximum data length.
        max: u16,
    },
    /// A frame's payload plus pad block does not fit the protocol's 16-bit
    /// on-wire `data` length. The encoder refuses to truncate silently:
    /// writing a wrapped length would desynchronize the entire session.
    #[error(
        "sing-mux frame payload of {data_len} bytes plus {pad_block_len} pad-block bytes exceeds the u16 on-wire data length"
    )]
    DataTooLargeForWire {
        /// Frame payload length in bytes.
        data_len: usize,
        /// Pad block overhead (2-byte pad-length field plus pad bytes)
        /// included in the on-wire data block, if any.
        pad_block_len: usize,
    },
    /// A `KeepAlive` frame carried non-empty `data`, which is a protocol
    /// violation.
    #[error("sing-mux KeepAlive frame must carry empty data, got {0} bytes")]
    NonEmptyKeepAlive(u16),
    /// The session version byte was not one this codec understands.
    #[error("unsupported sing-mux version byte {0}")]
    UnsupportedVersion(u8),
    /// A v2 `New` / `Data` frame's pad block was malformed: either the data
    /// block was too short to hold the 2-byte pad-length field, or the
    /// declared pad length ran past the end of the data block.
    #[error("malformed sing-mux v2 pad block: declared pad length {pad_len} does not fit data block of {data_len}")]
    MalformedPadding {
        /// Pad length declared in the frame's data block.
        pad_len: usize,
        /// On-wire data block length the pad block had to fit within.
        data_len: usize,
    },
}

/// A decoded sing-mux frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SingMuxFrame {
    /// Logical substream id this frame belongs to.
    pub stream_id: u32,
    /// Frame command.
    pub command: Command,
    /// Frame payload. Empty for `Close` and `KeepAlive`.
    pub data: Vec<u8>,
}

impl SingMuxFrame {
    /// Build a `New` frame opening `stream_id`, carrying the destination
    /// `request` bytes.
    pub fn new_stream(stream_id: u32, request: impl Into<Vec<u8>>) -> Self {
        Self { stream_id, command: Command::New, data: request.into() }
    }

    /// Build a `Data` frame carrying `payload` for `stream_id`.
    pub fn data(stream_id: u32, payload: impl Into<Vec<u8>>) -> Self {
        Self { stream_id, command: Command::Data, data: payload.into() }
    }

    /// Build a `Close` frame half-closing `stream_id`.
    pub fn close(stream_id: u32) -> Self {
        Self { stream_id, command: Command::Close, data: Vec::new() }
    }

    /// Build a session-level `KeepAlive` frame (stream id `0`).
    pub fn keep_alive() -> Self {
        Self { stream_id: 0, command: Command::KeepAlive, data: Vec::new() }
    }

    /// Serialize the frame into `out`.
    ///
    /// When `padding` is [`PaddingMode::Enabled`] *and* this is a `New` /
    /// `Data` frame, a sing-mux v2 pad block is prepended to the data:
    /// `[u16 pad_len][pad_len zero bytes][payload]`. The pad-length field
    /// sits at a *fixed* position (the first two bytes of the data block) so
    /// a v2 decoder strips it deterministically -- never by guessing. `Close`
    /// / `KeepAlive` frames and v1 (`Disabled`) sessions emit the payload
    /// verbatim with no pad block.
    ///
    /// Errors with [`SingMuxCodecError::DataTooLargeForWire`] when the
    /// payload plus pad block does not fit the protocol's 16-bit on-wire
    /// data length; callers must split oversized payloads into smaller
    /// frames. A lossy cast here would silently corrupt the length field
    /// and desynchronize the whole session.
    pub fn encode(&self, out: &mut Vec<u8>, padding: PaddingMode) -> Result<(), SingMuxCodecError> {
        let pad_len = match self.command {
            Command::New | Command::Data => padding.pad_len(),
            Command::Close | Command::KeepAlive => 0,
        };
        // v2 New/Data frames always carry a pad-length field (possibly 0);
        // every other case has no pad block at all.
        let has_pad_block =
            matches!(padding, PaddingMode::Enabled { .. }) && matches!(self.command, Command::New | Command::Data);
        let pad_block_len = if has_pad_block { 2 + pad_len as usize } else { 0 };
        let Ok(on_wire_data_len) = u16::try_from(self.data.len() + pad_block_len) else {
            return Err(SingMuxCodecError::DataTooLargeForWire { data_len: self.data.len(), pad_block_len });
        };
        out.reserve(FRAME_PREFIX_LEN + on_wire_data_len as usize);
        out.extend_from_slice(&self.stream_id.to_be_bytes());
        out.push(self.command.to_wire());
        out.extend_from_slice(&on_wire_data_len.to_be_bytes());
        if has_pad_block {
            out.extend_from_slice(&pad_len.to_be_bytes());
            out.extend(std::iter::repeat_n(0u8, pad_len as usize));
        }
        out.extend_from_slice(&self.data);
        Ok(())
    }

    /// Serialize the frame to a fresh `Vec` with no padding (v1 wire form).
    ///
    /// Errors with [`SingMuxCodecError::DataTooLargeForWire`] when the
    /// payload does not fit the 16-bit on-wire data length.
    pub fn to_bytes(&self) -> Result<Vec<u8>, SingMuxCodecError> {
        let mut out = Vec::with_capacity(FRAME_PREFIX_LEN.saturating_add(self.data.len()));
        self.encode(&mut out, PaddingMode::Disabled)?;
        Ok(out)
    }
}

/// Serialize the one-byte sing-mux session version prefix for `padding`.
pub fn session_version_prefix(padding: PaddingMode) -> u8 {
    padding.version_byte()
}

/// Validate a received sing-mux session version byte.
pub fn parse_session_version(version: u8) -> Result<(), SingMuxCodecError> {
    match version {
        VERSION_BASE | VERSION_PADDED => Ok(()),
        other => Err(SingMuxCodecError::UnsupportedVersion(other)),
    }
}

/// Incremental sing-mux frame decoder.
///
/// Wire bytes are pushed in via [`SingMuxDecoder::extend`] as they arrive;
/// complete frames are pulled out via [`SingMuxDecoder::next_frame`]. A
/// partial prefix or partial `data` block is retained until enough bytes
/// arrive.
///
/// Padding handling is *deterministic*, not heuristic: the decoder knows the
/// negotiated session version (from the leading version byte, or set
/// explicitly via [`SingMuxDecoder::without_version_prefix`]). For a v2
/// session, a `New` / `Data` frame's data block begins with a fixed-position
/// `u16` pad-length field followed by that many pad bytes; the decoder skips
/// exactly those and hands back the trailing payload. A v1 session has no
/// pad block at all. The [`SingMuxFrame::data`] handed back never includes
/// pad bytes.
#[derive(Debug)]
pub struct SingMuxDecoder {
    buffer: Vec<u8>,
    /// Cap on a single frame's on-wire `data` block (payload + any padding).
    max_data_len: u16,
    /// `true` once the leading session version byte has been consumed.
    version_seen: bool,
    /// The negotiated session version. `true` once a v2 (padded) session is
    /// known, so `New` / `Data` frames are decoded with a leading pad block.
    padded_session: bool,
}

impl Default for SingMuxDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl SingMuxDecoder {
    /// Create a decoder that still expects the leading session version byte
    /// and caps a frame's data block at `u16::MAX`.
    pub fn new() -> Self {
        Self { buffer: Vec::new(), max_data_len: u16::MAX, version_seen: false, padded_session: false }
    }

    /// Create a decoder for a session whose version byte was already consumed
    /// elsewhere (for example by a handshake layer). `padded_session` must
    /// reflect the negotiated version: `true` for v2 (padded), `false` for
    /// v1.
    pub fn without_version_prefix(padded_session: bool) -> Self {
        Self { buffer: Vec::new(), max_data_len: u16::MAX, version_seen: true, padded_session }
    }

    /// Set the maximum on-wire `data` block length a frame may declare.
    pub fn with_max_data_len(mut self, max_data_len: u16) -> Self {
        self.max_data_len = max_data_len;
        self
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
    /// On the first call this also consumes and validates the leading session
    /// version byte (unless the decoder was built with
    /// [`SingMuxDecoder::without_version_prefix`]).
    ///
    /// Returns `Ok(None)` when the buffer holds only a partial frame; the
    /// partial bytes are kept. Returns `Err` only on a genuinely malformed,
    /// oversized, or protocol-violating frame.
    pub fn next_frame(&mut self) -> Result<Option<SingMuxFrame>, SingMuxCodecError> {
        if !self.version_seen {
            if self.buffer.is_empty() {
                return Ok(None);
            }
            let version = self.buffer[0];
            parse_session_version(version)?;
            self.padded_session = version == VERSION_PADDED;
            self.buffer.drain(..1);
            self.version_seen = true;
        }
        if self.buffer.len() < FRAME_PREFIX_LEN {
            return Ok(None);
        }
        let stream_id = u32::from_be_bytes([self.buffer[0], self.buffer[1], self.buffer[2], self.buffer[3]]);
        let command = Command::from_wire(self.buffer[4])?;
        let data_len = u16::from_be_bytes([self.buffer[5], self.buffer[6]]);
        if data_len > self.max_data_len {
            return Err(SingMuxCodecError::DataTooLarge { len: data_len, max: self.max_data_len });
        }
        if command == Command::KeepAlive && data_len != 0 {
            return Err(SingMuxCodecError::NonEmptyKeepAlive(data_len));
        }
        let total = FRAME_PREFIX_LEN + data_len as usize;
        if self.buffer.len() < total {
            // Partial frame: wait for more bytes.
            return Ok(None);
        }
        let on_wire_data = &self.buffer[FRAME_PREFIX_LEN..total];
        // For a v2 session, a `New` / `Data` frame's data block begins with a
        // fixed-position pad block; strip it deterministically.
        let payload = strip_padding(self.padded_session, command, on_wire_data)?;
        let frame = SingMuxFrame { stream_id, command, data: payload };
        self.buffer.drain(..total);
        Ok(Some(frame))
    }
}

/// Strip the leading sing-mux v2 pad block from a frame's on-wire data block.
///
/// In a v2 (`padded_session`) session, every `New` / `Data` frame's data
/// block is laid out as `[u16 pad_len][pad_len pad bytes][payload]` -- the
/// pad-length field is at a *fixed* position, so this is fully deterministic,
/// never a guess. v1 sessions, `Close` / `KeepAlive` frames, and any frame in
/// a non-padded session carry the payload verbatim.
fn strip_padding(padded_session: bool, command: Command, on_wire_data: &[u8]) -> Result<Vec<u8>, SingMuxCodecError> {
    if !padded_session || !matches!(command, Command::New | Command::Data) {
        return Ok(on_wire_data.to_vec());
    }
    if on_wire_data.len() < 2 {
        // A v2 New/Data frame must at least carry its 2-byte pad-length field.
        return Err(SingMuxCodecError::MalformedPadding { pad_len: 0, data_len: on_wire_data.len() });
    }
    let pad_len = u16::from_be_bytes([on_wire_data[0], on_wire_data[1]]) as usize;
    let payload_start = 2 + pad_len;
    if payload_start > on_wire_data.len() {
        // The declared pad length runs past the data block: malformed frame.
        return Err(SingMuxCodecError::MalformedPadding { pad_len, data_len: on_wire_data.len() });
    }
    Ok(on_wire_data[payload_start..].to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn command_round_trips_through_wire_bytes() {
        for cmd in [Command::New, Command::Data, Command::Close, Command::KeepAlive] {
            assert_eq!(Command::from_wire(cmd.to_wire()).expect("known cmd"), cmd);
        }
    }

    #[test]
    fn unknown_command_is_rejected() {
        assert_eq!(Command::from_wire(7), Err(SingMuxCodecError::UnknownCommand(7)));
    }

    #[test]
    fn session_version_prefix_matches_padding_mode() {
        assert_eq!(session_version_prefix(PaddingMode::Disabled), VERSION_BASE);
        assert_eq!(session_version_prefix(PaddingMode::Enabled { max: 256 }), VERSION_PADDED);
    }

    #[test]
    fn parse_session_version_accepts_known_versions() {
        assert!(parse_session_version(VERSION_BASE).is_ok());
        assert!(parse_session_version(VERSION_PADDED).is_ok());
        assert_eq!(parse_session_version(9), Err(SingMuxCodecError::UnsupportedVersion(9)));
    }

    #[test]
    fn frame_encodes_to_documented_prefix_layout() {
        // New frame on stream 1 carrying "abc" -> 4-byte id, cmd, u16 len, data.
        let frame = SingMuxFrame::new_stream(1, b"abc".to_vec());
        let bytes = frame.to_bytes().expect("encodable frame");
        assert_eq!(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]), 1);
        assert_eq!(bytes[4], Command::New.to_wire());
        assert_eq!(u16::from_be_bytes([bytes[5], bytes[6]]), 3);
        assert_eq!(&bytes[7..], b"abc");
    }

    #[test]
    fn data_frame_round_trips_through_decoder() {
        let frame = SingMuxFrame::data(42, b"the quick brown fox".to_vec());
        let mut decoder = SingMuxDecoder::without_version_prefix(false);
        decoder.extend(&frame.to_bytes().expect("encodable frame"));
        let decoded = decoder.next_frame().expect("decode ok").expect("one frame");
        assert_eq!(decoded, frame);
        assert!(decoder.is_empty());
    }

    #[test]
    fn new_frame_round_trips_request_bytes() {
        let frame = SingMuxFrame::new_stream(3, b"example.com:443".to_vec());
        let mut decoder = SingMuxDecoder::without_version_prefix(false);
        decoder.extend(&frame.to_bytes().expect("encodable frame"));
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.command, Command::New);
        assert_eq!(decoded.data, b"example.com:443");
    }

    #[test]
    fn close_and_keepalive_frames_carry_no_data() {
        let close = SingMuxFrame::close(9);
        let keepalive = SingMuxFrame::keep_alive();
        assert!(close.data.is_empty());
        assert!(keepalive.data.is_empty());
        assert_eq!(keepalive.stream_id, 0);

        let mut decoder = SingMuxDecoder::without_version_prefix(false);
        decoder.extend(&close.to_bytes().expect("encodable frame"));
        decoder.extend(&keepalive.to_bytes().expect("encodable frame"));
        assert_eq!(decoder.next_frame().expect("ok").expect("close"), close);
        assert_eq!(decoder.next_frame().expect("ok").expect("keepalive"), keepalive);
    }

    #[test]
    fn decoder_consumes_leading_version_byte() {
        let mut wire = vec![session_version_prefix(PaddingMode::Disabled)];
        SingMuxFrame::data(1, b"hello".to_vec()).encode(&mut wire, PaddingMode::Disabled).expect("encodable frame");

        let mut decoder = SingMuxDecoder::new();
        decoder.extend(&wire);
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.data, b"hello");
    }

    #[test]
    fn decoder_rejects_bad_version_byte() {
        let mut decoder = SingMuxDecoder::new();
        decoder.extend(&[0xFF]);
        assert_eq!(decoder.next_frame(), Err(SingMuxCodecError::UnsupportedVersion(0xFF)));
    }

    #[test]
    fn multi_frame_stream_decodes_in_order() {
        let frames = [
            SingMuxFrame::new_stream(1, b"target:80".to_vec()),
            SingMuxFrame::data(1, b"GET / HTTP/1.1".to_vec()),
            SingMuxFrame::keep_alive(),
            SingMuxFrame::close(1),
        ];
        let mut wire = vec![session_version_prefix(PaddingMode::Disabled)];
        for frame in &frames {
            frame.encode(&mut wire, PaddingMode::Disabled).expect("encodable frame");
        }
        let mut decoder = SingMuxDecoder::new();
        decoder.extend(&wire);
        for expected in &frames {
            assert_eq!(&decoder.next_frame().expect("ok").expect("frame"), expected);
        }
        assert!(decoder.next_frame().expect("ok").is_none());
    }

    #[test]
    fn partial_frame_is_buffered_until_complete() {
        let frame = SingMuxFrame::data(5, b"deferred payload".to_vec());
        let bytes = frame.to_bytes().expect("encodable frame");
        let split = bytes.len() / 2;
        let mut decoder = SingMuxDecoder::without_version_prefix(false);

        decoder.extend(&bytes[..split]);
        assert!(decoder.next_frame().expect("ok").is_none(), "half a frame is not ready");

        decoder.extend(&bytes[split..]);
        assert_eq!(decoder.next_frame().expect("ok").expect("now complete"), frame);
    }

    #[test]
    fn byte_at_a_time_feed_still_decodes() {
        let frame = SingMuxFrame::data(7, b"streamed one byte at a time".to_vec());
        let bytes = frame.to_bytes().expect("encodable frame");
        let mut decoder = SingMuxDecoder::without_version_prefix(false);
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
    fn keepalive_with_data_is_rejected() {
        // Hand-assemble a KeepAlive frame that wrongly declares data.
        let mut wire = Vec::new();
        wire.extend_from_slice(&0u32.to_be_bytes());
        wire.push(Command::KeepAlive.to_wire());
        wire.extend_from_slice(&3u16.to_be_bytes());
        wire.extend_from_slice(b"bad");
        let mut decoder = SingMuxDecoder::without_version_prefix(false);
        decoder.extend(&wire);
        assert_eq!(decoder.next_frame(), Err(SingMuxCodecError::NonEmptyKeepAlive(3)));
    }

    #[test]
    fn oversized_frame_is_rejected() {
        let mut decoder = SingMuxDecoder::without_version_prefix(false).with_max_data_len(8);
        let mut wire = Vec::new();
        wire.extend_from_slice(&1u32.to_be_bytes());
        wire.push(Command::Data.to_wire());
        wire.extend_from_slice(&9u16.to_be_bytes());
        decoder.extend(&wire);
        assert_eq!(decoder.next_frame(), Err(SingMuxCodecError::DataTooLarge { len: 9, max: 8 }));
    }

    #[test]
    fn v2_padded_data_frame_strips_padding_on_decode() {
        // Encode with padding enabled, decode in a v2 (padded) session, and
        // confirm the payload is recovered exactly with no pad bytes leaking
        // through. The decoder strips the fixed-position pad block because it
        // *knows* the session is v2 -- it is not a guess.
        let frame = SingMuxFrame::data(1, b"real-payload".to_vec());
        let mut wire = Vec::new();
        frame.encode(&mut wire, PaddingMode::Enabled { max: 64 }).expect("encodable frame");
        assert!(wire.len() > FRAME_PREFIX_LEN + b"real-payload".len(), "padding should grow the frame");

        let mut decoder = SingMuxDecoder::without_version_prefix(true);
        decoder.extend(&wire);
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.data, b"real-payload", "padding must be stripped");
    }

    #[test]
    fn v1_session_keeps_data_block_verbatim() {
        // The mirror of the v2 test: in a v1 (unpadded) session the decoder
        // never strips anything, even if the payload happens to look like a
        // pad block. A v1-encoded frame round-trips byte-exact.
        let frame = SingMuxFrame::data(1, vec![0x00, 0x03, 0, 0, 0, b'h', b'i']);
        let mut decoder = SingMuxDecoder::without_version_prefix(false);
        decoder.extend(&frame.to_bytes().expect("encodable frame"));
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded, frame, "v1 session must not strip anything");
    }

    #[test]
    fn v2_padded_new_frame_round_trips() {
        let frame = SingMuxFrame::new_stream(2, b"host:443".to_vec());
        let mut wire = vec![session_version_prefix(PaddingMode::Enabled { max: 32 })];
        frame.encode(&mut wire, PaddingMode::Enabled { max: 32 }).expect("encodable frame");
        // `new()` consumes the v2 version byte and switches the decoder into
        // padded-session mode on its own.
        let mut decoder = SingMuxDecoder::new();
        decoder.extend(&wire);
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.command, Command::New);
        assert_eq!(decoded.data, b"host:443");
    }

    #[test]
    fn v2_zero_pad_frame_still_round_trips() {
        // With `Enabled { max: 0 }` the pad length is always 0, but a v2
        // New/Data frame still carries the 2-byte pad-length field. The
        // decoder must skip exactly those 2 bytes and recover the payload.
        let frame = SingMuxFrame::data(4, b"zero-pad".to_vec());
        let mut wire = Vec::new();
        frame.encode(&mut wire, PaddingMode::Enabled { max: 0 }).expect("encodable frame");
        assert_eq!(wire.len(), FRAME_PREFIX_LEN + 2 + b"zero-pad".len(), "2-byte pad-length field, no pad bytes");
        let mut decoder = SingMuxDecoder::without_version_prefix(true);
        decoder.extend(&wire);
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.data, b"zero-pad");
    }

    #[test]
    fn v2_pad_length_running_past_data_block_is_rejected() {
        // Hand-assemble a v2 Data frame whose pad-length field claims more
        // pad bytes than the data block actually holds.
        let mut wire = Vec::new();
        wire.extend_from_slice(&1u32.to_be_bytes());
        wire.push(Command::Data.to_wire());
        wire.extend_from_slice(&4u16.to_be_bytes()); // data block: 4 bytes
        wire.extend_from_slice(&99u16.to_be_bytes()); // pad_len = 99 (does not fit)
        wire.extend_from_slice(&[0, 0]); // only 2 more bytes present
        let mut decoder = SingMuxDecoder::without_version_prefix(true);
        decoder.extend(&wire);
        assert_eq!(decoder.next_frame(), Err(SingMuxCodecError::MalformedPadding { pad_len: 99, data_len: 4 }));
    }

    /// Cross-checks the codec against a hand-assembled wire frame, the way a
    /// port of the sing-box mux test vectors would: a `New` frame on stream 1
    /// carrying the 3 bytes `abc`, prefixed by the v1 session version byte.
    #[test]
    fn decodes_hand_assembled_upstream_style_vector() {
        let wire: [u8; 1 + FRAME_PREFIX_LEN + 3] = [
            0x01, // session version 1
            0x00, 0x00, 0x00, 0x01, // stream id 1
            0x00, // cmd New
            0x00, 0x03, // data length 3
            b'a', b'b', b'c', // data
        ];
        let mut decoder = SingMuxDecoder::new();
        decoder.extend(&wire);
        let frame = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(frame.stream_id, 1);
        assert_eq!(frame.command, Command::New);
        assert_eq!(frame.data, b"abc");
    }

    /// Regression test for the encoder's lossy `as u16` cast: a payload whose
    /// on-wire data length does not fit 16 bits used to be written with a
    /// silently wrapped length field (70000 bytes declared as 4464),
    /// desynchronizing the whole session. The encoder must refuse instead.
    #[test]
    fn oversized_payload_is_rejected_not_truncated() {
        let frame = SingMuxFrame::data(1, vec![0_u8; u16::MAX as usize + 1]);
        assert_eq!(
            frame.to_bytes(),
            Err(SingMuxCodecError::DataTooLargeForWire { data_len: u16::MAX as usize + 1, pad_block_len: 0 })
        );
    }

    #[test]
    fn oversized_payload_with_pad_block_is_rejected_accounting_for_padding() {
        // The payload alone would fit (65534 <= 65535), but the v2 pad-length
        // field pushes the on-wire data block to 65536: still too large.
        let frame = SingMuxFrame::data(1, vec![0_u8; u16::MAX as usize - 1]);
        let mut out = Vec::new();
        assert_eq!(
            frame.encode(&mut out, PaddingMode::Enabled { max: 0 }),
            Err(SingMuxCodecError::DataTooLargeForWire { data_len: u16::MAX as usize - 1, pad_block_len: 2 })
        );
        assert!(out.is_empty(), "a rejected frame must not leave partial bytes in the output");
    }

    #[test]
    fn boundary_payloads_fit_exactly() {
        // v1: the payload itself may use the full 16-bit range.
        let frame = SingMuxFrame::data(1, vec![0_u8; u16::MAX as usize]);
        let bytes = frame.to_bytes().expect("exact v1 fit");
        assert_eq!(u16::from_be_bytes([bytes[5], bytes[6]]), u16::MAX);
        assert_eq!(bytes.len(), FRAME_PREFIX_LEN + u16::MAX as usize);

        // v2 with a pad block: payload + 2-byte pad-length field must fit.
        let frame = SingMuxFrame::data(1, vec![0_u8; u16::MAX as usize - 2]);
        let mut wire = Vec::new();
        frame.encode(&mut wire, PaddingMode::Enabled { max: 0 }).expect("exact v2 fit");
        assert_eq!(u16::from_be_bytes([wire[5], wire[6]]), u16::MAX);

        // The exact-fit frames must decode back intact.
        let mut decoder = SingMuxDecoder::without_version_prefix(true);
        decoder.extend(&wire);
        let decoded = decoder.next_frame().expect("decode ok").expect("frame");
        assert_eq!(decoded.data.len(), u16::MAX as usize - 2);
    }
}
