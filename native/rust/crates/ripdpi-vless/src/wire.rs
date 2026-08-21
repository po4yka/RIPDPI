use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::pin::Pin;
use std::task::{Context, Poll, ready};

use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, ReadBuf};

use crate::vision::{XtlsDirectRead, XtlsDirectWrite};

const RESPONSE_VERSION: u8 = 0x00;

/// VLESS wire version.
///
/// xray-core has only ever defined VLESS request version `0x00`. Wrapping
/// the value in a typed enum lets future variants slot in without
/// re-validating every literal `0x00` in the codebase. The
/// `ProtocolVersion::SUPPORTED` slice is the source of truth for
/// "what does this client speak" and is the hook point for the future
/// version-mismatch probe diagnostic
/// (completed task `introduce-protocol-version-enum-and-version-probe-diagnostic`; see git history).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtocolVersion {
    /// Wire byte `0x00`. The only revision xray-core has shipped.
    V0,
}

/// VLESS request command byte.
///
/// XUDP uses [`Self::Mux`]: unlike TCP and direct UDP requests, the Mux
/// command carries no destination bytes in the VLESS request header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VlessCommand {
    Tcp,
    Udp,
    Mux,
}

impl VlessCommand {
    pub const fn wire_byte(self) -> u8 {
        match self {
            Self::Tcp => 0x01,
            Self::Udp => 0x02,
            Self::Mux => 0x03,
        }
    }

    pub const fn from_wire_byte(byte: u8) -> Option<Self> {
        match byte {
            0x01 => Some(Self::Tcp),
            0x02 => Some(Self::Udp),
            0x03 => Some(Self::Mux),
            _ => None,
        }
    }

    const fn carries_target(self) -> bool {
        matches!(self, Self::Tcp | Self::Udp)
    }
}

impl ProtocolVersion {
    /// All wire-version variants this client can encode.
    pub const SUPPORTED: &'static [Self] = &[Self::V0];

    /// On-wire byte representation.
    pub const fn wire_byte(self) -> u8 {
        match self {
            Self::V0 => 0x00,
        }
    }

    /// Decode a wire byte into a known variant, or `None` for unsupported.
    pub const fn from_wire_byte(byte: u8) -> Option<Self> {
        match byte {
            0x00 => Some(Self::V0),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedRequestHeader {
    pub uuid: [u8; 16],
    pub command: VlessCommand,
    pub target: String,
    pub consumed_len: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum EncodeError {
    #[error("VLESS addons are too long: {len} bytes exceeds {max}")]
    AddonsTooLong { len: usize, max: usize },
    #[error("VLESS domain is too long: {len} bytes exceeds {max}")]
    DomainTooLong { len: usize, max: usize },
    #[error("invalid VLESS target {target:?}")]
    InvalidTarget { target: String },
    #[error("invalid VLESS target port {port:?}")]
    InvalidPort { port: String },
}

impl From<EncodeError> for io::Error {
    fn from(error: EncodeError) -> Self {
        Self::new(io::ErrorKind::InvalidInput, error)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum DecodeError {
    #[error("VLESS header needs more data")]
    NeedMoreData,
    #[error("unsupported VLESS request version {0}")]
    UnsupportedRequestVersion(u8),
    #[error("unsupported VLESS response version {0}")]
    UnsupportedResponseVersion(u8),
    #[error("unsupported VLESS command {0}")]
    UnsupportedCommand(u8),
    #[error("unsupported VLESS address type {0}")]
    UnsupportedAddressType(u8),
    #[error("VLESS domain target is not valid UTF-8 at byte {valid_up_to}")]
    InvalidDomainUtf8 { valid_up_to: usize },
    #[error("VLESS header length overflow")]
    LengthOverflow,
}

pub type ParseRequestError = DecodeError;

#[derive(Debug, Error)]
pub enum ReadResponseError {
    #[error("read VLESS response: {0}")]
    Io(#[from] io::Error),
    #[error(transparent)]
    Decode(#[from] DecodeError),
}

impl From<ReadResponseError> for io::Error {
    fn from(error: ReadResponseError) -> Self {
        match error {
            ReadResponseError::Io(error) => error,
            ReadResponseError::Decode(error) => Self::new(io::ErrorKind::InvalidData, error),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ResponseHeaderState {
    Header { bytes: [u8; 2], filled: usize },
    Addons { remaining: usize },
    Payload,
    Failed,
}

/// Removes the VLESS response header lazily from the first downlink read.
///
/// xray-core buffers its response header and flushes it with the first
/// outbound payload. Waiting for the header before exposing a writable stream
/// therefore deadlocks request/response protocols: the client cannot send the
/// request that makes the server flush its header. Writes pass through
/// immediately; reads validate and consume `Version | AddonsLen | Addons`
/// before yielding payload bytes.
pub struct ResponseHeaderStream<S> {
    inner: S,
    state: ResponseHeaderState,
    trace_response: bool,
    stage_started: Option<std::time::Instant>,
}

impl<S> Drop for ResponseHeaderStream<S> {
    fn drop(&mut self) {
        if let Some(started) = self.stage_started.take() {
            crate::emit_stage("vless_response", "cancelled", started, None, None);
        }
    }
}

impl<S> ResponseHeaderStream<S> {
    pub fn new(inner: S) -> Self {
        Self {
            inner,
            state: ResponseHeaderState::Header { bytes: [0; 2], filled: 0 },
            trace_response: false,
            stage_started: None,
        }
    }

    pub(crate) fn new_traced(inner: S) -> Self {
        Self {
            inner,
            state: ResponseHeaderState::Header { bytes: [0; 2], filled: 0 },
            trace_response: true,
            stage_started: None,
        }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for ResponseHeaderStream<S> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, output: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if output.remaining() == 0 {
            return Pin::new(&mut this.inner).poll_read(cx, output);
        }
        if this.trace_response && this.stage_started.is_none() && !matches!(this.state, ResponseHeaderState::Payload) {
            let started = std::time::Instant::now();
            crate::emit_stage("vless_response", "started", started, None, None);
            this.stage_started = Some(started);
        }
        loop {
            match &mut this.state {
                ResponseHeaderState::Header { bytes, filled } => {
                    let mut header = ReadBuf::new(&mut bytes[*filled..]);
                    if let Err(error) = ready!(Pin::new(&mut this.inner).poll_read(cx, &mut header)) {
                        return Poll::Ready(Err(this.fail_response(error, None)));
                    }
                    let read = header.filled().len();
                    if read == 0 {
                        let error =
                            io::Error::new(io::ErrorKind::UnexpectedEof, "VLESS response ended before its header");
                        return Poll::Ready(Err(this.fail_response(error, Some("before_vless_response_header"))));
                    }
                    *filled += read;
                    if *filled == bytes.len() {
                        if let Err(error) = validate_response_version(bytes[0]) {
                            let error = io::Error::new(io::ErrorKind::InvalidData, error);
                            return Poll::Ready(Err(this.fail_response(error, Some("during_vless_response_header"))));
                        }
                        this.state = ResponseHeaderState::Addons { remaining: usize::from(bytes[1]) };
                    }
                }
                ResponseHeaderState::Addons { remaining } if *remaining > 0 => {
                    let mut scratch = [0_u8; u8::MAX as usize];
                    let mut addons = ReadBuf::new(&mut scratch[..*remaining]);
                    if let Err(error) = ready!(Pin::new(&mut this.inner).poll_read(cx, &mut addons)) {
                        return Poll::Ready(Err(this.fail_response(error, None)));
                    }
                    let read = addons.filled().len();
                    if read == 0 {
                        let error =
                            io::Error::new(io::ErrorKind::UnexpectedEof, "VLESS response ended before its addons");
                        return Poll::Ready(Err(this.fail_response(error, Some("during_vless_response_addons"))));
                    }
                    *remaining -= read;
                }
                ResponseHeaderState::Addons { remaining: 0 } => {
                    this.state = ResponseHeaderState::Payload;
                    this.trace_response = false;
                    if let Some(started) = this.stage_started.take() {
                        crate::emit_stage("vless_response", "succeeded", started, None, None);
                    }
                }
                ResponseHeaderState::Payload => return Pin::new(&mut this.inner).poll_read(cx, output),
                ResponseHeaderState::Failed => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::BrokenPipe,
                        "VLESS response validation already failed",
                    )));
                }
                ResponseHeaderState::Addons { .. } => unreachable!("guard handles non-empty VLESS addons"),
            }
        }
    }
}

impl<S> ResponseHeaderStream<S> {
    fn fail_response(&mut self, error: io::Error, peer_close_phase: Option<&'static str>) -> io::Error {
        self.state = ResponseHeaderState::Failed;
        self.trace_response = false;
        if let Some(started) = self.stage_started.take() {
            crate::emit_stage("vless_response", "failed", started, Some(&error), peer_close_phase);
        }
        error
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for ResponseHeaderStream<S> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, input: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write(cx, input)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

impl<S: XtlsDirectRead> XtlsDirectRead for ResponseHeaderStream<S> {
    fn poll_read_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, output: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.state != ResponseHeaderState::Payload {
            return Poll::Ready(Err(io::Error::other("direct read before VLESS response validation")));
        }
        Pin::new(&mut this.inner).poll_read_direct(cx, output)
    }
}

impl<S: XtlsDirectWrite> XtlsDirectWrite for ResponseHeaderStream<S> {
    fn poll_write_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, input: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write_direct(cx, input)
    }

    fn poll_flush_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush_direct(cx)
    }

    fn poll_shutdown_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown_direct(cx)
    }
}

/// Encode a VLESS request header.
///
/// Wire format (from the VLESS spec):
/// ```text
/// Version(1B = 0x00) | UUID(16B) | AddonsLen(1B) | Addons(var)
/// | Cmd(1B) | Port(2B BE) | AddrType(1B) | Addr(var)
/// ```
///
/// `target` is `"host:port"` — the destination the VLESS server should proxy to.
pub fn encode_request(uuid: &[u8; 16], addons: &[u8], target: &str) -> Result<Vec<u8>, EncodeError> {
    encode_command_request(uuid, addons, VlessCommand::Tcp, Some(target))
}

/// Encode a VLESS request for a typed command.
///
/// TCP and direct UDP commands require a target. Mux intentionally omits it,
/// matching xray-core's `EncodeRequestHeader` behavior for `v1.mux.cool`.
pub fn encode_command_request(
    uuid: &[u8; 16],
    addons: &[u8],
    command: VlessCommand,
    target: Option<&str>,
) -> Result<Vec<u8>, EncodeError> {
    let addons_len = u8::try_from(addons.len())
        .map_err(|_| EncodeError::AddonsTooLong { len: addons.len(), max: usize::from(u8::MAX) })?;

    let parsed_target = if command.carries_target() {
        let target = target.ok_or_else(|| EncodeError::InvalidTarget { target: String::new() })?;
        Some(parse_target(target)?)
    } else {
        None
    };

    let target_len = parsed_target.as_ref().map_or(0, |(host, _)| 2 + 1 + host.len() + 2);
    let mut buf = Vec::with_capacity(1 + 16 + 1 + addons.len() + 1 + target_len);

    // Version
    buf.push(ProtocolVersion::V0.wire_byte());
    // UUID
    buf.extend_from_slice(uuid);
    // Addons length + addons
    buf.push(addons_len);
    buf.extend_from_slice(addons);
    buf.push(command.wire_byte());

    let Some((host, port)) = parsed_target else {
        return Ok(buf);
    };

    buf.extend_from_slice(&port.to_be_bytes());

    // Address
    if let Ok(v4) = host.parse::<Ipv4Addr>() {
        buf.push(0x01); // IPv4
        buf.extend_from_slice(&v4.octets());
    } else if let Ok(v6) = host.parse::<Ipv6Addr>() {
        buf.push(0x03); // IPv6
        buf.extend_from_slice(&v6.octets());
    } else {
        // Domain
        let domain_bytes = host.as_bytes();
        let domain_len = u8::try_from(domain_bytes.len())
            .map_err(|_| EncodeError::DomainTooLong { len: domain_bytes.len(), max: usize::from(u8::MAX) })?;
        buf.push(0x02); // Domain
        buf.push(domain_len);
        buf.extend_from_slice(domain_bytes);
    }

    Ok(buf)
}

/// Encode the VLESS response header.
///
/// Response format: `Version(1B) | AddonsLen(1B) | Addons(var)`.
pub fn encode_response(addons: &[u8]) -> Result<Vec<u8>, EncodeError> {
    let addons_len = u8::try_from(addons.len())
        .map_err(|_| EncodeError::AddonsTooLong { len: addons.len(), max: usize::from(u8::MAX) })?;
    let mut buf = Vec::with_capacity(2 + addons.len());
    buf.push(RESPONSE_VERSION);
    buf.push(addons_len);
    buf.extend_from_slice(addons);
    Ok(buf)
}

/// Parse a VLESS request header from an in-memory buffer.
pub fn parse_request_header(bytes: &[u8]) -> Result<DecodedRequestHeader, ParseRequestError> {
    const VERSION_AND_UUID_LEN: usize = 17;
    if bytes.len() < VERSION_AND_UUID_LEN + 1 {
        return Err(ParseRequestError::NeedMoreData);
    }
    if ProtocolVersion::from_wire_byte(bytes[0]).is_none() {
        return Err(DecodeError::UnsupportedRequestVersion(bytes[0]));
    }
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes[1..17]);

    let addons_len = usize::from(bytes[17]);
    let mut cursor = VERSION_AND_UUID_LEN + 1;
    if bytes.len() < cursor + addons_len + 1 {
        return Err(ParseRequestError::NeedMoreData);
    }
    cursor += addons_len;

    let command_byte = bytes[cursor];
    cursor += 1;
    let command = VlessCommand::from_wire_byte(command_byte).ok_or(DecodeError::UnsupportedCommand(command_byte))?;
    if command == VlessCommand::Mux {
        return Ok(DecodedRequestHeader { uuid, command, target: String::new(), consumed_len: cursor });
    }

    // Target-bearing commands still need port (2) and address type (1).
    if bytes.len() < cursor + 3 {
        return Err(ParseRequestError::NeedMoreData);
    }

    let port = u16::from_be_bytes([bytes[cursor], bytes[cursor + 1]]);
    cursor += 2;

    let address_type = bytes[cursor];
    cursor += 1;
    let host = match address_type {
        0x01 => {
            if bytes.len() < cursor + 4 {
                return Err(ParseRequestError::NeedMoreData);
            }
            let host = Ipv4Addr::new(bytes[cursor], bytes[cursor + 1], bytes[cursor + 2], bytes[cursor + 3]);
            cursor += 4;
            host.to_string()
        }

        0x02 => {
            if bytes.len() < cursor + 1 {
                return Err(ParseRequestError::NeedMoreData);
            }
            let domain_len = usize::from(bytes[cursor]);
            cursor += 1;
            if bytes.len() < cursor + domain_len {
                return Err(ParseRequestError::NeedMoreData);
            }
            let host = std::str::from_utf8(&bytes[cursor..cursor + domain_len])
                .map_err(|error| DecodeError::InvalidDomainUtf8 { valid_up_to: error.valid_up_to() })?;
            cursor += domain_len;
            host.to_owned()
        }

        0x03 => {
            if bytes.len() < cursor + 16 {
                return Err(ParseRequestError::NeedMoreData);
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&bytes[cursor..cursor + 16]);
            cursor += 16;
            Ipv6Addr::from(raw).to_string()
        }

        other => {
            return Err(DecodeError::UnsupportedAddressType(other));
        }
    };

    Ok(DecodedRequestHeader { uuid, command, target: format_target(&host, port), consumed_len: cursor })
}

/// Read and consume the VLESS response header from the stream.
///
/// Response format: `Version(1B) | AddonsLen(1B) | Addons(var)`
///
/// After this call returns successfully, the stream carries raw bidirectional data.
pub async fn read_response(stream: &mut (impl AsyncRead + Unpin)) -> Result<(), ReadResponseError> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).await?;
    validate_response_version(header[0])?;
    let addons_len = usize::from(header[1]);
    if addons_len > 0 {
        let mut addons = vec![0u8; addons_len];
        stream.read_exact(&mut addons).await?;
    }
    Ok(())
}

/// Sync parse of the VLESS response header from an in-memory buffer.
///
/// Mirrors [`read_response`] but operates on a byte slice so fuzz
/// harnesses can drive the parser without an async runtime. Returns
/// the number of bytes consumed (`2 + addons_len`) on success, or
/// `ParseRequestError::NeedMoreData` if the buffer is too short.
pub fn parse_response_header(bytes: &[u8]) -> Result<usize, ParseRequestError> {
    if bytes.len() < 2 {
        return Err(ParseRequestError::NeedMoreData);
    }
    validate_response_version(bytes[0])?;
    let addons_len = usize::from(bytes[1]);
    let total = 2usize.checked_add(addons_len).ok_or(DecodeError::LengthOverflow)?;
    if bytes.len() < total {
        return Err(ParseRequestError::NeedMoreData);
    }
    Ok(total)
}

/// Split `"host:port"` into `(host, port)`.
fn validate_response_version(version: u8) -> Result<(), DecodeError> {
    if version == RESPONSE_VERSION { Ok(()) } else { Err(DecodeError::UnsupportedResponseVersion(version)) }
}

pub(crate) fn parse_target(target: &str) -> Result<(String, u16), EncodeError> {
    // Handle IPv6 bracket notation: [::1]:443
    if target.starts_with('[') {
        let Some(bracket_end) = target.rfind("]:") else {
            return Err(EncodeError::InvalidTarget { target: target.to_owned() });
        };
        let host = target[1..bracket_end].to_owned();
        if host.is_empty() {
            return Err(EncodeError::InvalidTarget { target: target.to_owned() });
        }
        let port_text = &target[bracket_end + 2..];
        let port = port_text.parse().map_err(|_| EncodeError::InvalidPort { port: port_text.to_owned() })?;
        return Ok((host, port));
    }
    if let Some(colon) = target.rfind(':') {
        let host = target[..colon].to_owned();
        if host.is_empty() || host.contains(':') {
            return Err(EncodeError::InvalidTarget { target: target.to_owned() });
        }
        let port_text = &target[colon + 1..];
        let port = port_text.parse().map_err(|_| EncodeError::InvalidPort { port: port_text.to_owned() })?;
        Ok((host, port))
    } else {
        Err(EncodeError::InvalidTarget { target: target.to_owned() })
    }
}

fn format_target(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') { format!("[{host}]:{port}") } else { format!("{host}:{port}") }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;
    use android_support::{EventRingBuffers, EventRingLayer};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tracing::{Instrument as _, instrument::WithSubscriber as _};
    use tracing_subscriber::prelude::*;

    #[test]
    fn xray_vless_request_version_is_zero() {
        let encoded = encode_request(&[0x42; 16], &[], "example.com:443").expect("encode request");

        assert_eq!(encoded[0], 0x00, "xray-core accepts only VLESS request version zero");
    }

    #[test]
    fn encode_request_domain() {
        let uuid = [0x01u8; 16];
        let addons = &[0x0a, 0x10, b'x'];
        let buf = encode_request(&uuid, addons, "example.com:443").expect("encode request");

        assert_eq!(buf[0], 0x00); // version
        assert_eq!(&buf[1..17], &[0x01; 16]); // UUID
        assert_eq!(buf[17], 3); // addons len
        assert_eq!(&buf[18..21], addons); // addons
        assert_eq!(buf[21], 0x01); // cmd = TCP
        assert_eq!(u16::from_be_bytes([buf[22], buf[23]]), 443); // port
        assert_eq!(buf[24], 0x02); // addr type = domain
        assert_eq!(buf[25], 11); // domain length
        assert_eq!(&buf[26..37], b"example.com");
    }

    #[test]
    fn encode_mux_request_omits_destination() {
        let uuid = [0x24; 16];
        let addons = &[0x0a, 0x10, b'x'];
        let encoded = encode_command_request(&uuid, addons, VlessCommand::Mux, None).expect("encode Mux request");

        assert_eq!(encoded.len(), 1 + 16 + 1 + addons.len() + 1);
        assert_eq!(encoded.last(), Some(&VlessCommand::Mux.wire_byte()));
        let decoded = parse_request_header(&encoded).expect("parse Mux request");
        assert_eq!(decoded.command, VlessCommand::Mux);
        assert!(decoded.target.is_empty());
        assert_eq!(decoded.consumed_len, encoded.len());
    }

    #[test]
    fn encode_request_ipv4() {
        let uuid = [0xAA; 16];
        let buf = encode_request(&uuid, &[], "1.2.3.4:80").expect("encode request");

        // version(1) + uuid(16) + addonslen(1) + addons(0 bytes) + cmd(1) + port(2)
        let base = 1 + 16 + 1 + 1 + 2;
        assert_eq!(buf[base], 0x01); // IPv4
        assert_eq!(&buf[base + 1..base + 5], &[1, 2, 3, 4]);
    }

    #[test]
    fn encode_request_ipv6() {
        let uuid = [0xBB; 16];
        let buf = encode_request(&uuid, &[], "[::1]:8080").expect("encode request");

        // version(1) + uuid(16) + addonslen(1) + addons(0 bytes) + cmd(1) + port(2)
        let base = 1 + 16 + 1 + 1 + 2;
        assert_eq!(buf[base], 0x03); // IPv6
        // ::1 = 15 zeros + 0x01
        assert_eq!(buf[base + 16], 0x01);
    }

    #[test]
    fn encode_response_writes_default_success_header() {
        assert_eq!(encode_response(&[]).expect("encode response"), vec![0x00, 0x00]);
    }

    #[test]
    fn parse_request_header_round_trips_domain_target() {
        let uuid = [0x44; 16];
        let encoded = encode_request(&uuid, &[0x0a], "example.com:443").expect("encode request");

        let decoded = parse_request_header(&encoded).expect("domain request");

        assert_eq!(uuid, decoded.uuid);
        assert_eq!(decoded.command, VlessCommand::Tcp);
        assert_eq!("example.com:443", decoded.target);
        assert_eq!(encoded.len(), decoded.consumed_len);
    }

    #[test]
    fn parse_request_header_round_trips_ipv6_target() {
        let uuid = [0x55; 16];
        let encoded = encode_request(&uuid, &[], "[2001:db8::1]:8443").expect("encode request");

        let decoded = parse_request_header(&encoded).expect("ipv6 request");

        assert_eq!("[2001:db8::1]:8443", decoded.target);
        assert_eq!(encoded.len(), decoded.consumed_len);
    }

    #[test]
    fn parse_request_header_reports_partial_buffers() {
        let uuid = [0x66; 16];
        let encoded = encode_request(&uuid, &[], "example.com:443").expect("encode request");

        assert_eq!(Err(ParseRequestError::NeedMoreData), parse_request_header(&encoded[..8]));
    }

    #[test]
    fn parse_request_header_reports_missing_address_type() {
        let encoded = encode_request(&[0x66; 16], &[], "example.com:443").expect("encode request");

        // Version + UUID + addons length + command + port. The next byte,
        // the address type, is not yet available.
        assert_eq!(Err(ParseRequestError::NeedMoreData), parse_request_header(&encoded[..21]));
    }

    #[test]
    fn parse_target_rejects_portless_targets_instead_of_defaulting() {
        for target in ["example.com", "127.0.0.1", ""] {
            assert!(
                matches!(parse_target(target), Err(EncodeError::InvalidTarget { .. })),
                "portless target {target:?} must be a typed error, not an implicit :443"
            );
        }
    }

    #[test]
    fn parse_request_header_rejects_unknown_command() {
        let mut encoded = encode_request(&[0x77; 16], &[], "example.com:443").expect("encode request");
        encoded[18] = 0xff;

        assert_eq!(Err(DecodeError::UnsupportedCommand(0xff)), parse_request_header(&encoded),);
    }

    #[test]
    fn protocol_version_wire_byte_roundtrip() {
        for &version in ProtocolVersion::SUPPORTED {
            let byte = version.wire_byte();
            assert_eq!(ProtocolVersion::from_wire_byte(byte), Some(version));
        }
    }

    #[test]
    fn protocol_version_from_wire_byte_rejects_unknown() {
        // Anything outside SUPPORTED must return None so the parser can
        // emit a typed "unsupported version" error rather than silently
        // accepting an alien byte.
        for byte in [0x01u8, 0x02, 0x05, 0xff] {
            assert_eq!(ProtocolVersion::from_wire_byte(byte), None, "unexpected accept for {byte:#x}");
        }
    }

    #[test]
    fn encoded_request_uses_protocol_version_wire_byte() {
        let encoded = encode_request(&[0x77; 16], &[], "example.com:443").expect("encode request");
        assert_eq!(encoded[0], ProtocolVersion::V0.wire_byte());
    }

    #[test]
    fn parse_response_header_reports_short_input_as_need_more_data() {
        assert_eq!(parse_response_header(&[]), Err(ParseRequestError::NeedMoreData));
        assert_eq!(parse_response_header(&[0x00]), Err(ParseRequestError::NeedMoreData));
        // Two bytes header says 5 addons follow but buffer ends.
        assert_eq!(parse_response_header(&[0x00, 0x05]), Err(ParseRequestError::NeedMoreData));
        assert_eq!(parse_response_header(&[0x00, 0x05, 0x01, 0x02]), Err(ParseRequestError::NeedMoreData));
    }

    #[test]
    fn parse_response_header_returns_consumed_length_for_zero_addons() {
        assert_eq!(parse_response_header(&[0x00, 0x00]), Ok(2));
    }

    #[test]
    fn parse_response_header_rejects_unknown_version() {
        assert_eq!(parse_response_header(&[0x01, 0x00]), Err(DecodeError::UnsupportedResponseVersion(0x01)));
    }

    #[tokio::test]
    async fn read_response_rejects_unknown_version() {
        let mut input = &b"\x01\x00"[..];
        let error = read_response(&mut input).await.expect_err("unknown response version must fail");
        assert!(matches!(error, ReadResponseError::Decode(DecodeError::UnsupportedResponseVersion(0x01))));
    }

    #[tokio::test]
    async fn lazy_response_header_allows_uplink_before_xray_flushes_downlink() {
        let (client, mut server) = tokio::io::duplex(64);
        let peer = tokio::spawn(async move {
            let mut request = [0_u8; 5];
            server.read_exact(&mut request).await.expect("read first uplink payload");
            assert_eq!(request, *b"hello");

            server
                .write_all(&[RESPONSE_VERSION, 3, 0xaa, 0xbb, 0xcc])
                .await
                .expect("write buffered VLESS response header");
            server.write_all(b"world").await.expect("write response payload");
        });

        let mut stream = ResponseHeaderStream::new(client);
        stream.write_all(b"hello").await.expect("uplink must not wait for the response header");
        let mut response = [0_u8; 5];
        stream.read_exact(&mut response).await.expect("strip response header and read payload");

        assert_eq!(response, *b"world");
        peer.await.expect("xray-like peer task");
    }

    #[tokio::test]
    async fn lazy_response_header_rejects_unknown_version_on_first_read() {
        let mut stream = ResponseHeaderStream::new(&b"\x01\x00payload"[..]);
        let mut payload = [0_u8; 7];

        let error = stream.read_exact(&mut payload).await.expect_err("unknown response version must fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("unsupported VLESS response version 1"));
    }

    #[tokio::test]
    async fn traced_lazy_response_drop_before_read_emits_no_response_stage() {
        let buffers = EventRingBuffers::default();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        let dispatch = tracing::Dispatch::new(subscriber);
        let attempt = tracing::dispatcher::with_default(&dispatch, || relay_attempt_span("lazy-unread"));

        async move {
            let (client, _server) = tokio::io::duplex(8);
            drop(ResponseHeaderStream::new_traced(client));
        }
        .instrument(attempt)
        .with_subscriber(dispatch)
        .await;

        assert!(buffers.drain_relay_for_runtime("lazy-unread").events.is_empty());
    }

    #[tokio::test]
    async fn traced_lazy_response_resumes_partial_header_and_emits_one_success() {
        let buffers = EventRingBuffers::default();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        let dispatch = tracing::Dispatch::new(subscriber);
        let attempt = tracing::dispatcher::with_default(&dispatch, || relay_attempt_span("lazy-resume"));

        async move {
            let (client, mut server) = tokio::io::duplex(16);
            server.write_all(&[RESPONSE_VERSION]).await.expect("write partial response header");
            let mut stream = ResponseHeaderStream::new_traced(client);
            let mut payload = [0_u8; 5];
            assert!(tokio::time::timeout(Duration::from_millis(5), stream.read(&mut payload)).await.is_err());
            server.write_all(&[0]).await.expect("finish response header");
            server.write_all(b"hello").await.expect("write response payload");
            stream.read_exact(&mut payload).await.expect("resume after cancelled read");
            assert_eq!(&payload, b"hello");
        }
        .instrument(attempt)
        .with_subscriber(dispatch)
        .await;

        let outcomes = buffers
            .drain_relay_for_runtime("lazy-resume")
            .events
            .into_iter()
            .filter(|event| event.stage.as_deref() == Some("vless_response"))
            .map(|event| event.outcome)
            .collect::<Vec<_>>();
        assert_eq!(outcomes, [Some("started".to_string()), Some("succeeded".to_string())]);
    }

    #[tokio::test]
    async fn traced_lazy_response_drop_while_pending_emits_one_cancelled() {
        let buffers = EventRingBuffers::default();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        let dispatch = tracing::Dispatch::new(subscriber);
        let attempt = tracing::dispatcher::with_default(&dispatch, || relay_attempt_span("lazy-cancelled"));

        async move {
            let (client, _server) = tokio::io::duplex(8);
            let mut stream = ResponseHeaderStream::new_traced(client);
            let mut payload = [0_u8; 1];
            assert!(tokio::time::timeout(Duration::from_millis(5), stream.read(&mut payload)).await.is_err());
            drop(stream);
        }
        .instrument(attempt)
        .with_subscriber(dispatch)
        .await;

        let outcomes = buffers
            .drain_relay_for_runtime("lazy-cancelled")
            .events
            .into_iter()
            .filter(|event| event.stage.as_deref() == Some("vless_response"))
            .map(|event| event.outcome)
            .collect::<Vec<_>>();
        assert_eq!(outcomes, [Some("started".to_string()), Some("cancelled".to_string())]);
    }

    fn relay_attempt_span(runtime_id: &'static str) -> tracing::Span {
        tracing::info_span!(
            "relay_attempt",
            ring = "relay",
            source = "ripdpi-vless",
            subsystem = "relay",
            runtime_id,
            attempt_id = 1_u64,
        )
    }

    #[test]
    fn encode_request_returns_typed_errors_for_oversized_fields_and_bad_target() {
        assert_eq!(
            encode_request(&[0; 16], &vec![0; 256], "example.com:443"),
            Err(EncodeError::AddonsTooLong { len: 256, max: 255 })
        );
        let domain = "a".repeat(256);
        assert_eq!(
            encode_request(&[0; 16], &[], &format!("{domain}:443")),
            Err(EncodeError::DomainTooLong { len: 256, max: 255 })
        );
        assert_eq!(
            encode_request(&[0; 16], &[], "example.com:not-a-port"),
            Err(EncodeError::InvalidPort { port: "not-a-port".to_string() })
        );
    }

    #[test]
    fn parse_response_header_returns_consumed_length_for_addons() {
        assert_eq!(parse_response_header(&[0x00, 0x03, 0xaa, 0xbb, 0xcc]), Ok(5));
        // Extra bytes beyond the consumed window are fine.
        assert_eq!(parse_response_header(&[0x00, 0x03, 0xaa, 0xbb, 0xcc, 0xdd, 0xee]), Ok(5));
    }

    #[test]
    fn parse_response_header_accepts_max_addons() {
        let mut input = vec![0x00, 0xff];
        input.extend(std::iter::repeat_n(0x00, 255));
        assert_eq!(parse_response_header(&input), Ok(2 + 255));
    }
}
