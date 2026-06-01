//! Xray/V2Fly-compatible gRPC transport framing.
//!
//! Xray's "gRPC" transport is not a full gRPC stack: it is a `GunService` /
//! `Tun` unary-stream pattern carried over HTTP/2. The client issues an
//! HTTP/2 `POST` to `/<serviceName>/Tun` and both request and response
//! bodies are sequences of length-delimited protobuf `Hunk { bytes data = 1 }`
//! messages wrapped in the standard gRPC length-prefixed message framing.
//!
//! On the wire a single tunneled chunk looks like:
//!
//! ```text
//! 0x00                      gRPC compression flag (0 = uncompressed)
//! <u32 big-endian>          gRPC message length (length of the protobuf below)
//! 0x0A                      protobuf tag: field 1, wire type 2 (length-delimited)
//! <varint>                  length of the `data` bytes
//! <data ...>                the tunneled payload bytes
//! ```
//!
//! This module implements that framing directly over the `bytes` / `http`
//! machinery the crate already depends on, so no `tonic`/`prost` codegen
//! stack is required. It exposes a [`GrpcTransport`] with an async
//! `AsyncRead + AsyncWrite` surface over the tunneled byte stream.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::{Buf, BufMut, Bytes, BytesMut};
use tokio::io::{AsyncRead, AsyncWrite, DuplexStream, ReadBuf};

/// gRPC compression flag for an uncompressed message.
const GRPC_FLAG_UNCOMPRESSED: u8 = 0x00;
/// Protobuf tag for `Hunk.data` (field number 1, wire type 2 = length-delimited).
const HUNK_DATA_TAG: u8 = 0x0A;
/// gRPC message framing prefix: 1 flag byte + 4 length bytes.
const GRPC_PREFIX_LEN: usize = 5;
/// Upper bound on a single decoded Hunk payload, mirroring Xray's 16 KiB-ish
/// chunking; protects the decoder from a hostile length prefix.
const MAX_HUNK_LEN: usize = 1 << 20;
/// Duplex buffer size for the tunneled byte stream.
const STREAM_BUFFER_SIZE: usize = 64 * 1024;

/// Errors produced while encoding or decoding gRPC `Hunk` framing.
#[derive(Debug, thiserror::Error)]
pub enum GrpcFramingError {
    /// The gRPC message declared a length larger than [`MAX_HUNK_LEN`].
    #[error("gRPC message length {0} exceeds maximum {MAX_HUNK_LEN}")]
    MessageTooLarge(usize),
    /// The protobuf body did not start with the expected `Hunk.data` tag.
    #[error("unexpected protobuf tag {0:#04x}, expected Hunk.data tag {HUNK_DATA_TAG:#04x}")]
    UnexpectedTag(u8),
    /// A protobuf varint was malformed (overlong or truncated past the buffer).
    #[error("malformed protobuf varint in Hunk frame")]
    MalformedVarint,
    /// The declared `Hunk.data` length did not match the remaining message bytes.
    #[error("Hunk.data length {declared} does not fit message remainder {remaining}")]
    LengthMismatch {
        /// Length declared by the inner protobuf varint.
        declared: usize,
        /// Bytes actually remaining in the gRPC message.
        remaining: usize,
    },
}

/// Encode a single payload as an Xray/V2Fly gRPC `Hunk` frame and append it to `out`.
///
/// The frame is `0x00 <u32-be len> 0x0A <varint data_len> <data>`.
pub fn encode_hunk(data: &[u8], out: &mut BytesMut) {
    let mut varint = [0u8; 10];
    let varint_len = encode_varint(data.len() as u64, &mut varint);
    // Inner protobuf message: tag + varint length + data.
    let message_len = 1 + varint_len + data.len();
    out.reserve(GRPC_PREFIX_LEN + message_len);
    out.put_u8(GRPC_FLAG_UNCOMPRESSED);
    out.put_u32(message_len as u32);
    out.put_u8(HUNK_DATA_TAG);
    out.put_slice(&varint[..varint_len]);
    out.put_slice(data);
}

/// Encode a single `Hunk` frame into a fresh [`Bytes`] buffer.
pub fn encode_hunk_to_bytes(data: &[u8]) -> Bytes {
    let mut out = BytesMut::new();
    encode_hunk(data, &mut out);
    out.freeze()
}

/// Incremental decoder for a stream of length-delimited gRPC `Hunk` frames.
///
/// Bytes are pushed in via [`HunkDecoder::extend`] as they arrive on the
/// HTTP/2 body; complete payloads are pulled out via [`HunkDecoder::next_hunk`].
/// Partial / truncated frames are retained until enough bytes arrive.
#[derive(Debug, Default)]
pub struct HunkDecoder {
    buffer: BytesMut,
}

impl HunkDecoder {
    /// Create an empty decoder.
    pub fn new() -> Self {
        Self::default()
    }

    /// Append freshly received wire bytes to the decode buffer.
    pub fn extend(&mut self, bytes: &[u8]) {
        self.buffer.extend_from_slice(bytes);
    }

    /// True when no buffered bytes remain (all complete frames consumed).
    pub fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }

    /// Try to decode the next complete `Hunk` payload.
    ///
    /// Returns `Ok(None)` when the buffer holds only a partial frame; the
    /// partial bytes are kept for the next call. Returns `Err` only on a
    /// genuinely malformed frame.
    pub fn next_hunk(&mut self) -> Result<Option<Bytes>, GrpcFramingError> {
        if self.buffer.len() < GRPC_PREFIX_LEN {
            return Ok(None);
        }
        // Peek the 5-byte gRPC prefix without consuming it yet.
        let message_len = u32::from_be_bytes([self.buffer[1], self.buffer[2], self.buffer[3], self.buffer[4]]) as usize;
        if message_len > MAX_HUNK_LEN {
            return Err(GrpcFramingError::MessageTooLarge(message_len));
        }
        if self.buffer.len() < GRPC_PREFIX_LEN + message_len {
            // Partial frame: wait for more bytes.
            return Ok(None);
        }

        // We have a full frame: consume the prefix and the message body.
        self.buffer.advance(GRPC_PREFIX_LEN);
        let mut message = self.buffer.split_to(message_len);

        // The gRPC compression flag may legitimately be 0; we only support
        // uncompressed messages, which is what Xray sends.
        let tag = message.get_u8();
        if tag != HUNK_DATA_TAG {
            return Err(GrpcFramingError::UnexpectedTag(tag));
        }
        let data_len = decode_varint(&mut message)? as usize;
        if data_len != message.len() {
            return Err(GrpcFramingError::LengthMismatch { declared: data_len, remaining: message.len() });
        }
        Ok(Some(message.freeze()))
    }
}

/// Encode an unsigned 64-bit integer as a protobuf varint into `out`.
/// Returns the number of bytes written.
fn encode_varint(mut value: u64, out: &mut [u8; 10]) -> usize {
    let mut index = 0;
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            out[index] = byte;
            index += 1;
            return index;
        }
        out[index] = byte | 0x80;
        index += 1;
    }
}

/// Decode a protobuf varint from the front of `buf`, advancing it.
fn decode_varint(buf: &mut BytesMut) -> Result<u64, GrpcFramingError> {
    let mut value: u64 = 0;
    let mut shift: u32 = 0;
    loop {
        if buf.is_empty() || shift >= 64 {
            return Err(GrpcFramingError::MalformedVarint);
        }
        let byte = buf.get_u8();
        value |= u64::from(byte & 0x7F) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
        shift += 7;
    }
}

/// HTTP/2 path for the gRPC `Tun` method of a configured service.
///
/// `service_name` may be supplied with or without a leading slash; the result
/// is always `/<service>/Tun`, matching Xray's `GunService` / V2Fly layout.
pub fn tun_path(service_name: &str) -> String {
    let trimmed = service_name.trim().trim_matches('/');
    if trimmed.is_empty() { "/Tun".to_owned() } else { format!("/{trimmed}/Tun") }
}

/// Configuration for an outbound Xray/V2Fly-compatible gRPC transport.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GrpcTransportConfig {
    /// Target host, used for the HTTP/2 `host` / `:authority` value when no
    /// explicit `authority` override is given.
    pub host: String,
    /// gRPC service name (Xray default `proxy.v2ray.com.Service`, the
    /// `GunService` fork name, or a custom value).
    pub service_name: String,
    /// Optional `:authority` override; when `None`, [`Self::host`] is used.
    pub authority: Option<String>,
}

impl GrpcTransportConfig {
    /// Build a config from a host and gRPC service name, defaulting the
    /// authority to the host.
    pub fn new(host: impl Into<String>, service_name: impl Into<String>) -> Self {
        Self { host: host.into(), service_name: service_name.into(), authority: None }
    }

    /// Override the HTTP/2 `:authority` pseudo-header.
    pub fn with_authority(mut self, authority: impl Into<String>) -> Self {
        self.authority = Some(authority.into());
        self
    }

    /// The HTTP/2 request path (`/<serviceName>/Tun`).
    pub fn tun_path(&self) -> String {
        tun_path(&self.service_name)
    }

    /// The effective `:authority` value for the HTTP/2 request.
    pub fn effective_authority(&self) -> &str {
        self.authority.as_deref().unwrap_or(&self.host)
    }
}

/// Outbound transport speaking Xray/V2Fly-compatible gRPC `Hunk` framing.
///
/// The transport exposes the *tunneled* byte stream as `AsyncRead +
/// AsyncWrite`. Bytes written here are encoded into gRPC `Hunk` frames before
/// hitting the HTTP/2 request body; gRPC `Hunk` frames received on the HTTP/2
/// response body are decoded back into the plain tunneled bytes read here.
///
/// Construct one with [`GrpcTransport::new`], driving the actual HTTP/2
/// exchange with the two duplex halves returned by
/// [`GrpcTransport::into_wire_halves`].
pub struct GrpcTransport {
    config: GrpcTransportConfig,
    reader: DuplexStream,
    writer: DuplexStream,
}

/// The HTTP/2-facing halves of a [`GrpcTransport`].
///
/// `upstream` yields already-`Hunk`-framed bytes to be placed in the HTTP/2
/// request body; `downstream` accepts the raw HTTP/2 response body bytes,
/// which are decoded into tunneled bytes for the transport's reader.
pub struct GrpcWireHalves {
    /// Reader side: framed `Hunk` bytes destined for the HTTP/2 request body.
    pub upstream: DuplexStream,
    /// Writer side: raw HTTP/2 response body bytes to be decoded.
    pub downstream: DuplexStream,
}

impl GrpcTransport {
    /// Create a transport plus its HTTP/2 wire halves.
    ///
    /// The returned [`GrpcWireHalves`] must be connected to an HTTP/2 `POST`
    /// to [`GrpcTransportConfig::tun_path`] for the transport to carry data.
    pub fn new(config: GrpcTransportConfig) -> (Self, GrpcWireHalves) {
        let (user_write, mut transport_read) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (mut transport_write, user_read) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (mut wire_up_write, wire_up_read) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (wire_down_write, mut wire_down_read) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        // Encode tunneled bytes -> Hunk frames -> HTTP/2 request body.
        tokio::spawn(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
            loop {
                match transport_read.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => {
                        let frame = encode_hunk_to_bytes(&buffer[..read]);
                        if wire_up_write.write_all(&frame).await.is_err() {
                            break;
                        }
                        if wire_up_write.flush().await.is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
            let _ = wire_up_write.shutdown().await;
        });

        // Decode HTTP/2 response body -> Hunk frames -> tunneled bytes.
        tokio::spawn(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
            let mut decoder = HunkDecoder::new();
            loop {
                match wire_down_read.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => {
                        decoder.extend(&buffer[..read]);
                        loop {
                            match decoder.next_hunk() {
                                Ok(Some(payload)) => {
                                    if transport_write.write_all(&payload).await.is_err() {
                                        return;
                                    }
                                }
                                Ok(None) => break,
                                Err(error) => {
                                    tracing::debug!(error = %error, "gRPC Hunk decode failed");
                                    return;
                                }
                            }
                        }
                    }
                    Err(_) => break,
                }
            }
            let _ = transport_write.shutdown().await;
        });

        let transport = Self { config, reader: user_read, writer: user_write };
        let halves = GrpcWireHalves { upstream: wire_up_read, downstream: wire_down_write };
        (transport, halves)
    }

    /// The configuration this transport was built with.
    pub fn config(&self) -> &GrpcTransportConfig {
        &self.config
    }
}

impl AsyncRead for GrpcTransport {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.reader).poll_read(cx, buf)
    }
}

impl AsyncWrite for GrpcTransport {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<Result<usize, io::Error>> {
        Pin::new(&mut self.writer).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        Pin::new(&mut self.writer).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        Pin::new(&mut self.writer).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[test]
    fn encode_hunk_produces_grpc_prefixed_protobuf() {
        let frame = encode_hunk_to_bytes(b"hello");
        // 0x00 flag, u32-be message length, 0x0A tag, varint(5), data.
        assert_eq!(frame[0], GRPC_FLAG_UNCOMPRESSED);
        let message_len = u32::from_be_bytes([frame[1], frame[2], frame[3], frame[4]]) as usize;
        assert_eq!(message_len, frame.len() - GRPC_PREFIX_LEN);
        assert_eq!(frame[5], HUNK_DATA_TAG);
        assert_eq!(frame[6], 5); // varint length of "hello"
        assert_eq!(&frame[7..], b"hello");
    }

    #[test]
    fn hunk_round_trips_through_decoder() {
        let payload = b"the quick brown fox";
        let frame = encode_hunk_to_bytes(payload);
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame);
        let decoded = decoder.next_hunk().expect("decode ok").expect("one hunk");
        assert_eq!(&decoded[..], payload);
        assert!(decoder.is_empty());
        assert!(decoder.next_hunk().expect("decode ok").is_none());
    }

    #[test]
    fn empty_hunk_round_trips() {
        let frame = encode_hunk_to_bytes(b"");
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame);
        let decoded = decoder.next_hunk().expect("decode ok").expect("one hunk");
        assert!(decoded.is_empty());
        assert!(decoder.is_empty());
    }

    #[test]
    fn large_hunk_uses_multibyte_varint() {
        // 200 bytes forces a 2-byte protobuf varint for the data length.
        let payload = vec![0xABu8; 200];
        let frame = encode_hunk_to_bytes(&payload);
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame);
        let decoded = decoder.next_hunk().expect("decode ok").expect("one hunk");
        assert_eq!(decoded.len(), 200);
        assert_eq!(&decoded[..], &payload[..]);
    }

    #[test]
    fn multi_hunk_stream_decodes_in_order() {
        let payloads: [&[u8]; 3] = [b"first", b"second-chunk", b"3"];
        let mut wire = BytesMut::new();
        for payload in payloads {
            encode_hunk(payload, &mut wire);
        }
        let mut decoder = HunkDecoder::new();
        decoder.extend(&wire);
        for expected in payloads {
            let decoded = decoder.next_hunk().expect("decode ok").expect("a hunk");
            assert_eq!(&decoded[..], expected);
        }
        assert!(decoder.next_hunk().expect("decode ok").is_none());
        assert!(decoder.is_empty());
    }

    #[test]
    fn partial_frame_is_buffered_until_complete() {
        let frame = encode_hunk_to_bytes(b"deferred payload");
        let split = frame.len() / 2;
        let mut decoder = HunkDecoder::new();

        // First half: not enough bytes yet.
        decoder.extend(&frame[..split]);
        assert!(decoder.next_hunk().expect("decode ok").is_none());

        // Second half completes the frame.
        decoder.extend(&frame[split..]);
        let decoded = decoder.next_hunk().expect("decode ok").expect("now complete");
        assert_eq!(&decoded[..], b"deferred payload");
    }

    #[test]
    fn truncated_prefix_is_buffered_not_errored() {
        let mut decoder = HunkDecoder::new();
        // Fewer than the 5-byte gRPC prefix.
        decoder.extend(&[0x00, 0x00, 0x00]);
        assert!(decoder.next_hunk().expect("decode ok").is_none());
    }

    #[test]
    fn byte_at_a_time_feed_still_decodes() {
        let payload = b"streamed one byte at a time";
        let frame = encode_hunk_to_bytes(payload);
        let mut decoder = HunkDecoder::new();
        let mut result = None;
        for byte in &frame {
            decoder.extend(std::slice::from_ref(byte));
            if let Some(hunk) = decoder.next_hunk().expect("decode ok") {
                result = Some(hunk);
            }
        }
        assert_eq!(&result.expect("eventually decoded")[..], payload);
    }

    #[test]
    fn oversized_message_length_is_rejected() {
        let mut decoder = HunkDecoder::new();
        let mut frame = BytesMut::new();
        frame.put_u8(GRPC_FLAG_UNCOMPRESSED);
        frame.put_u32((MAX_HUNK_LEN + 1) as u32);
        // Body bytes need not be present; the length check fires on the prefix.
        decoder.extend(&frame);
        let error = decoder.next_hunk().expect_err("oversized rejected");
        assert!(matches!(error, GrpcFramingError::MessageTooLarge(_)));
    }

    #[test]
    fn unexpected_protobuf_tag_is_rejected() {
        let mut frame = BytesMut::new();
        frame.put_u8(GRPC_FLAG_UNCOMPRESSED);
        frame.put_u32(2); // message length: tag + 1 byte
        frame.put_u8(0x12); // wrong tag (field 2), not Hunk.data
        frame.put_u8(0x00);
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame);
        let error = decoder.next_hunk().expect_err("bad tag rejected");
        assert!(matches!(error, GrpcFramingError::UnexpectedTag(0x12)));
    }

    #[test]
    fn inner_length_mismatch_is_rejected() {
        let mut frame = BytesMut::new();
        frame.put_u8(GRPC_FLAG_UNCOMPRESSED);
        frame.put_u32(4); // message: tag + varint + (claims more than present)
        frame.put_u8(HUNK_DATA_TAG);
        frame.put_u8(99); // varint says 99 data bytes
        frame.put_u8(0x01);
        frame.put_u8(0x02); // only 2 actually present
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame);
        let error = decoder.next_hunk().expect_err("length mismatch rejected");
        assert!(matches!(error, GrpcFramingError::LengthMismatch { declared: 99, remaining: 2 }));
    }

    #[test]
    fn malformed_varint_is_rejected() {
        let mut frame = BytesMut::new();
        frame.put_u8(GRPC_FLAG_UNCOMPRESSED);
        frame.put_u32(6); // tag + 5 continuation bytes that never terminate
        frame.put_u8(HUNK_DATA_TAG);
        frame.put_slice(&[0x80, 0x80, 0x80, 0x80, 0x80]);
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame);
        let error = decoder.next_hunk().expect_err("malformed varint rejected");
        assert!(matches!(error, GrpcFramingError::MalformedVarint));
    }

    #[test]
    fn tun_path_normalizes_service_name() {
        assert_eq!(tun_path("proxy.v2ray.com.Service"), "/proxy.v2ray.com.Service/Tun");
        assert_eq!(tun_path("/GunService/"), "/GunService/Tun");
        assert_eq!(tun_path("  GunService  "), "/GunService/Tun");
        assert_eq!(tun_path(""), "/Tun");
    }

    #[test]
    fn transport_config_exposes_path_and_authority() {
        let config = GrpcTransportConfig::new("cdn.example", "GunService");
        assert_eq!(config.tun_path(), "/GunService/Tun");
        assert_eq!(config.effective_authority(), "cdn.example");

        let overridden = config.with_authority("origin.example");
        assert_eq!(overridden.effective_authority(), "origin.example");
    }

    #[tokio::test]
    async fn transport_encodes_written_bytes_as_hunks() {
        let config = GrpcTransportConfig::new("cdn.example", "GunService");
        let (mut transport, mut halves) = GrpcTransport::new(config);

        transport.write_all(b"payload-up").await.expect("write");
        transport.flush().await.expect("flush");
        drop(transport); // close the writer so the encoder task finishes

        let mut wire = Vec::new();
        halves.upstream.read_to_end(&mut wire).await.expect("read wire");

        let mut decoder = HunkDecoder::new();
        decoder.extend(&wire);
        let decoded = decoder.next_hunk().expect("decode ok").expect("one hunk");
        assert_eq!(&decoded[..], b"payload-up");
    }

    #[tokio::test]
    async fn transport_decodes_hunks_into_readable_bytes() {
        let config = GrpcTransportConfig::new("cdn.example", "GunService");
        let (mut transport, mut halves) = GrpcTransport::new(config);

        // Simulate the HTTP/2 response body: two Hunk frames.
        let mut wire = BytesMut::new();
        encode_hunk(b"down-", &mut wire);
        encode_hunk(b"stream", &mut wire);
        halves.downstream.write_all(&wire).await.expect("write wire");
        halves.downstream.shutdown().await.expect("shutdown wire");
        drop(halves);

        let mut received = Vec::new();
        transport.read_to_end(&mut received).await.expect("read transport");
        assert_eq!(&received, b"down-stream");
    }
}
