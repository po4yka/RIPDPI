use std::io;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;

use bytes::{BufMut, BytesMut};
use quinn::SendStream;
use uuid::Uuid;

use crate::config::Config;

/// TUIC wire version.
///
/// EAimTY/tuic v5 only. Wrapping the byte in a typed enum mirrors the
/// `ProtocolVersion` pattern in `ripdpi-vless::wire` and the
/// `MtprotoTransportFamily` enum in `ripdpi-ws-tunnel::mtproto`, and
/// is the hook point for the future version-mismatch probe diagnostic
/// (completed task `introduce-protocol-version-enum-and-version-probe-diagnostic`; see git history).
/// v4 is intentionally unsupported per
/// `docs/architecture/tuic-v4-policy.md` (v5-only policy).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtocolVersion {
    /// Wire byte `0x05`.
    V5,
}

impl ProtocolVersion {
    /// All wire-version variants this client can encode.
    pub const SUPPORTED: &'static [Self] = &[Self::V5];

    /// On-wire byte representation.
    pub const fn wire_byte(self) -> u8 {
        match self {
            Self::V5 => 0x05,
        }
    }

    /// Decode a wire byte into a known variant, or `None` for unsupported.
    pub const fn from_wire_byte(byte: u8) -> Option<Self> {
        match byte {
            0x05 => Some(Self::V5),
            _ => None,
        }
    }
}

/// Backwards-compatible alias for the wire byte. Derived from the
/// enum so the two cannot drift.
pub(crate) const TUIC_VERSION: u8 = ProtocolVersion::V5.wire_byte();

/// Coarse classification of a failed TUIC handshake payload.
///
/// Downstream code maps `VersionUnsupported` to
/// `ripdpi-failure-classifier::FailureClass::TuicVersionUnsupported`
/// so the user-visible diagnostic recommends upgrading the server.
/// The kind is computed locally (no cross-crate dependency) so any
/// caller — relay engine, diagnostics probe, JNI bridge — can
/// classify a buffer without pulling in the failure-classifier crate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TuicFailureKind {
    /// Server replied with a non-v5 version byte (`0x04` is the only
    /// known prior variant; other bytes also classify here). See
    /// `docs/architecture/tuic-v4-policy.md`.
    VersionUnsupported,
    /// Failure shape that doesn't match a known version mismatch;
    /// pass through to the generic protocol-failure classifier.
    Other,
}

/// Classify a TUIC handshake-failure payload as version mismatch or
/// generic failure. The payload is whatever bytes were observed on
/// the wire when the connection failed — typically the first bytes
/// of the server's reject frame.
///
/// A payload starting with any byte that is not the v5 wire byte
/// classifies as `VersionUnsupported`; the v5 wire byte at offset 0
/// passes through as `Other` because a failure at v5-handshake time
/// is some other class (auth, network, etc.).
///
/// Empty payloads classify as `Other` — without bytes the kind
/// cannot be determined.
pub fn classify_failure_payload(payload: &[u8]) -> TuicFailureKind {
    match payload.first() {
        None => TuicFailureKind::Other,
        Some(&byte) if byte == TUIC_VERSION => TuicFailureKind::Other,
        Some(_) => TuicFailureKind::VersionUnsupported,
    }
}

/// A TUIC handshake failure that [`classify_failure_payload`] recognised as a
/// wire-version mismatch. Carried as the inner error of an [`io::Error`] so the
/// relay layer can downcast it (`error.get_ref().downcast_ref::<TuicHandshakeError>()`)
/// and map it to `ripdpi-failure-classifier::FailureClass::TuicVersionUnsupported`,
/// producing a user-actionable "upgrade your TUIC server to v5" diagnostic
/// instead of a generic protocol error. `ripdpi-tuic` deliberately does not
/// depend on the failure-classifier crate; the typed error is the seam.
///
/// See `docs/architecture/tuic-v4-policy.md`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TuicHandshakeError {
    kind: TuicFailureKind,
}

impl TuicHandshakeError {
    /// The server replied with a non-v5 wire version during the handshake.
    #[must_use]
    pub const fn version_unsupported() -> Self {
        Self { kind: TuicFailureKind::VersionUnsupported }
    }

    /// The coarse failure kind this error carries.
    #[must_use]
    pub const fn kind(&self) -> TuicFailureKind {
        self.kind
    }
}

impl std::fmt::Display for TuicHandshakeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self.kind {
            TuicFailureKind::VersionUnsupported => f.write_str(
                "TUIC server speaks an unsupported wire version (this client is v5-only); \
                 upgrade the server to TUIC v5 — see docs/architecture/tuic-v4-policy.md",
            ),
            TuicFailureKind::Other => f.write_str("TUIC handshake failed"),
        }
    }
}

impl std::error::Error for TuicHandshakeError {}

/// EAimTY/tuic v4 wire version byte — the only known wire version prior to v5
/// (`docs/architecture/tuic-v4-policy.md`). A deployed v4 server rejecting this
/// v5 client leads its reject frame with this byte.
const TUIC_LEGACY_V4_WIRE_BYTE: u8 = 0x04;

/// Decide whether an application-close reason observed on the TUIC
/// handshake-failure path indicates the peer speaks an unsupported wire version.
///
/// Pure and unit-testable. Returns `true` ONLY when the reason leads with a
/// recognised legacy TUIC version byte AND [`classify_failure_payload`] agrees
/// it is a version mismatch. Crucially it does NOT treat an arbitrary non-v5
/// close reason as a version mismatch: a correctly-deployed v5 server rejecting
/// bad credentials closes with a free-form reason (e.g. `"authentication
/// failed"`, leading byte `0x61`), which must surface as a generic error — not
/// a misleading "upgrade your server" diagnostic. Only a reason leading with the
/// v4 wire byte (`0x04`) is read as a version mismatch.
fn close_reason_indicates_version_mismatch(reason: &[u8]) -> bool {
    matches!(reason.first(), Some(&byte) if byte == TUIC_LEGACY_V4_WIRE_BYTE)
        && classify_failure_payload(reason) == TuicFailureKind::VersionUnsupported
}

/// Inspect a QUIC connection that errored during the TUIC handshake. If the peer
/// application-closed it with a reason that [`close_reason_indicates_version_mismatch`]
/// recognises as a legacy-version reject, return a typed [`TuicHandshakeError`]
/// wrapped in an [`io::Error`]. Otherwise return `fallback` unchanged.
///
/// This is the only runtime caller of [`classify_failure_payload`]. It runs only
/// on the handshake *failure* path (never on a successful relay), and the
/// legacy-version-byte gate keeps an ordinary v5-server close (auth rejection,
/// idle, graceful shutdown) from being misread as a version mismatch.
pub(crate) fn classify_handshake_failure(connection: &quinn::Connection, fallback: io::Error) -> io::Error {
    let Some(quinn::ConnectionError::ApplicationClosed(close)) = connection.close_reason() else {
        return fallback;
    };
    if close_reason_indicates_version_mismatch(close.reason.as_ref()) {
        io::Error::other(TuicHandshakeError::version_unsupported())
    } else {
        fallback
    }
}
pub(crate) const COMMAND_AUTHENTICATE: u8 = 0x00;
pub(crate) const COMMAND_CONNECT: u8 = 0x01;
pub(crate) const COMMAND_PACKET: u8 = 0x02;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum TuicAddress {
    None,
    Domain(String, u16),
    Socket(SocketAddr),
}

impl TuicAddress {
    pub(crate) fn from_authority(authority: &str) -> io::Result<Self> {
        if authority.trim().is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "TUIC authority must not be blank"));
        }
        if let Ok(socket) = SocketAddr::from_str(authority.trim()) {
            return Ok(Self::Socket(socket));
        }

        let (host, port) = split_authority(authority)?;
        let port = port.parse::<u16>().map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC authority port {port}: {error}"))
        })?;
        if let Ok(ip) = IpAddr::from_str(&host) {
            return Ok(Self::Socket(SocketAddr::new(ip, port)));
        }

        if host.len() > usize::from(u8::MAX) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "TUIC domain authorities are limited to 255 bytes",
            ));
        }

        Ok(Self::Domain(host, port))
    }

    pub(crate) fn to_authority(&self) -> io::Result<String> {
        match self {
            Self::None => {
                Err(io::Error::new(io::ErrorKind::InvalidData, "TUIC packet fragment is missing its address"))
            }
            Self::Domain(host, port) => Ok(format!("{host}:{port}")),
            Self::Socket(socket) => Ok(socket.to_string()),
        }
    }

    pub(crate) fn encoded_len(&self) -> usize {
        1 + match self {
            Self::None => 0,
            Self::Domain(host, _) => 1 + host.len() + 2,
            Self::Socket(SocketAddr::V4(_)) => 4 + 2,
            Self::Socket(SocketAddr::V6(_)) => 16 + 2,
        }
    }

    pub(crate) fn encode(&self, buffer: &mut BytesMut) {
        match self {
            Self::None => buffer.put_u8(0xff),
            Self::Domain(host, port) => {
                buffer.put_u8(0x00);
                buffer.put_u8(host.len() as u8);
                buffer.extend_from_slice(host.as_bytes());
                buffer.put_u16(*port);
            }
            Self::Socket(SocketAddr::V4(address)) => {
                buffer.put_u8(0x01);
                buffer.extend_from_slice(&address.ip().octets());
                buffer.put_u16(address.port());
            }
            Self::Socket(SocketAddr::V6(address)) => {
                buffer.put_u8(0x02);
                for segment in address.ip().segments() {
                    buffer.put_u16(segment);
                }
                buffer.put_u16(address.port());
            }
        }
    }

    pub(crate) fn decode(input: &mut &[u8]) -> io::Result<Self> {
        let Some((&kind, rest)) = input.split_first() else {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "missing TUIC address kind"));
        };
        *input = rest;

        match kind {
            0xff => Ok(Self::None),
            0x00 => {
                let length = read_u8(input)? as usize;
                let host_bytes = take_bytes(input, length)?;
                let port = read_u16(input)?;
                let host = String::from_utf8(host_bytes.to_vec())
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "TUIC domain name is not valid UTF-8"))?;
                Ok(Self::Domain(host, port))
            }
            0x01 => {
                let octets = take_bytes(input, 4)?;
                let port = read_u16(input)?;
                Ok(Self::Socket(SocketAddr::from(([octets[0], octets[1], octets[2], octets[3]], port))))
            }
            0x02 => {
                let octets = take_bytes(input, 16)?;
                let port = read_u16(input)?;
                let ip = [
                    u16::from_be_bytes([octets[0], octets[1]]),
                    u16::from_be_bytes([octets[2], octets[3]]),
                    u16::from_be_bytes([octets[4], octets[5]]),
                    u16::from_be_bytes([octets[6], octets[7]]),
                    u16::from_be_bytes([octets[8], octets[9]]),
                    u16::from_be_bytes([octets[10], octets[11]]),
                    u16::from_be_bytes([octets[12], octets[13]]),
                    u16::from_be_bytes([octets[14], octets[15]]),
                ];
                Ok(Self::Socket(SocketAddr::from((ip, port))))
            }
            _ => Err(io::Error::new(io::ErrorKind::InvalidData, format!("unsupported TUIC address kind {kind:#x}"))),
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct PacketHeader {
    pub(crate) assoc_id: u16,
    pub(crate) packet_id: u16,
    pub(crate) fragment_total: u8,
    pub(crate) fragment_id: u8,
    pub(crate) payload_len: u16,
    pub(crate) address: TuicAddress,
}

impl PacketHeader {
    pub(crate) fn encoded_len(&self) -> usize {
        2 + 2 + 1 + 1 + 2 + self.address.encoded_len()
    }

    pub(crate) fn encode(&self, buffer: &mut BytesMut) {
        buffer.put_u8(TUIC_VERSION);
        buffer.put_u8(COMMAND_PACKET);
        buffer.put_u16(self.assoc_id);
        buffer.put_u16(self.packet_id);
        buffer.put_u8(self.fragment_total);
        buffer.put_u8(self.fragment_id);
        buffer.put_u16(self.payload_len);
        self.address.encode(buffer);
    }

    pub(crate) fn decode(datagram: &[u8]) -> io::Result<(Self, &[u8])> {
        let mut input = datagram;
        if read_u8(&mut input)? != TUIC_VERSION {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TUIC packet version"));
        }
        if read_u8(&mut input)? != COMMAND_PACKET {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TUIC packet command"));
        }

        let header = Self {
            assoc_id: read_u16(&mut input)?,
            packet_id: read_u16(&mut input)?,
            fragment_total: read_u8(&mut input)?.max(1),
            fragment_id: read_u8(&mut input)?,
            payload_len: read_u16(&mut input)?,
            address: TuicAddress::decode(&mut input)?,
        };
        let payload = take_bytes(&mut input, usize::from(header.payload_len))?;
        Ok((header, payload))
    }
}

pub(crate) async fn authenticate_connection(connection: &quinn::Connection, config: &Config) -> io::Result<()> {
    let uuid = Uuid::parse_str(config.uuid.trim())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC UUID: {error}")))?;
    let mut token = [0u8; 32];
    connection
        .export_keying_material(&mut token, uuid.as_bytes(), config.password.as_bytes())
        .map_err(|error| io::Error::other(format!("TUIC exporter failed: {error:?}")))?;

    let mut payload = BytesMut::with_capacity(2 + 16 + 32);
    payload.put_u8(TUIC_VERSION);
    payload.put_u8(COMMAND_AUTHENTICATE);
    payload.extend_from_slice(uuid.as_bytes());
    payload.extend_from_slice(&token);

    let mut send = connection.open_uni().await?;
    write_auth_payload(&mut send, &payload).await?;
    send.finish()?;
    Ok(())
}

async fn write_auth_payload(send: &mut SendStream, payload: &[u8]) -> io::Result<()> {
    send.write_all(payload).await.map_err(io::Error::other)
}

fn split_authority(authority: &str) -> io::Result<(String, String)> {
    let trimmed = authority.trim();
    if trimmed.starts_with('[') {
        let end = trimmed
            .find(']')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid bracketed authority"))?;
        let host = trimmed[1..end].to_owned();
        let remainder = trimmed[end + 1..]
            .strip_prefix(':')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid bracketed authority port"))?;
        return Ok((host, remainder.to_owned()));
    }

    let (host, port) = trimmed
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "authority must include a port"))?;
    if host.contains(':') {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "unbracketed IPv6 authority is invalid"));
    }
    Ok((host.to_owned(), port.to_owned()))
}

fn read_u8(input: &mut &[u8]) -> io::Result<u8> {
    let Some((&value, rest)) = input.split_first() else {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of TUIC payload"));
    };
    *input = rest;
    Ok(value)
}

fn read_u16(input: &mut &[u8]) -> io::Result<u16> {
    let bytes = take_bytes(input, 2)?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn take_bytes<'a>(input: &mut &'a [u8], count: usize) -> io::Result<&'a [u8]> {
    if input.len() < count {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of TUIC payload"));
    }
    let (head, tail) = input.split_at(count);
    *input = tail;
    Ok(head)
}

#[cfg(test)]
mod protocol_version_tests {
    use super::*;

    #[test]
    fn protocol_version_wire_byte_roundtrip() {
        for &version in ProtocolVersion::SUPPORTED {
            assert_eq!(ProtocolVersion::from_wire_byte(version.wire_byte()), Some(version));
        }
    }

    #[test]
    fn protocol_version_from_wire_byte_rejects_unknown() {
        for byte in [0x00u8, 0x01, 0x04, 0x06, 0xff] {
            assert_eq!(ProtocolVersion::from_wire_byte(byte), None, "unexpected accept for {byte:#x}");
        }
    }

    #[test]
    fn tuic_version_const_equals_protocol_version_v5_wire_byte() {
        assert_eq!(TUIC_VERSION, ProtocolVersion::V5.wire_byte());
    }

    #[test]
    fn classify_failure_payload_returns_version_unsupported_for_v4_byte() {
        // A v4-server reject frame starts with the v4 wire byte (0x04).
        // The classifier must surface this as VersionUnsupported so the
        // downstream FailureClass::TuicVersionUnsupported is selected.
        assert_eq!(classify_failure_payload(&[0x04]), TuicFailureKind::VersionUnsupported);
        assert_eq!(classify_failure_payload(&[0x04, 0xff, 0xfe, 0xfd]), TuicFailureKind::VersionUnsupported);
    }

    #[test]
    fn classify_failure_payload_returns_other_for_v5_byte() {
        // v5 byte at offset 0 means the server speaks v5; failure is
        // some other class (auth, network, ...). Pass through.
        assert_eq!(classify_failure_payload(&[0x05]), TuicFailureKind::Other);
        assert_eq!(classify_failure_payload(&[TUIC_VERSION, 0x00, 0x01]), TuicFailureKind::Other);
    }

    #[test]
    fn classify_failure_payload_returns_other_for_empty_payload() {
        // No bytes -> cannot decide -> Other (caller will fall through
        // to generic protocol-failure classification).
        assert_eq!(classify_failure_payload(&[]), TuicFailureKind::Other);
    }

    #[test]
    fn classify_failure_payload_treats_arbitrary_non_v5_bytes_as_version_unsupported() {
        // Any non-v5 byte at offset 0 is a version mismatch signal.
        // Cover representative bytes including 0x00, 0x01, 0xff.
        for byte in [0x00u8, 0x01, 0x02, 0x03, 0x06, 0x7f, 0xff] {
            assert_eq!(
                classify_failure_payload(&[byte]),
                TuicFailureKind::VersionUnsupported,
                "byte {byte:#x} must classify as VersionUnsupported",
            );
        }
    }

    #[test]
    fn close_reason_with_v4_wire_byte_is_a_version_mismatch() {
        assert!(close_reason_indicates_version_mismatch(&[0x04]));
        assert!(close_reason_indicates_version_mismatch(&[0x04, 0xff, 0x00]));
    }

    #[test]
    fn close_reason_from_v5_auth_rejection_is_not_a_version_mismatch() {
        // A correctly-deployed v5 server rejecting bad credentials closes with a
        // free-form reason. It must NOT be misread as a version mismatch (the
        // bug a byte-0 "any non-v5" heuristic would introduce). Regression for
        // the C1 review finding.
        assert!(!close_reason_indicates_version_mismatch(b"authentication failed"));
        assert!(!close_reason_indicates_version_mismatch(b"auth error"));
        assert!(!close_reason_indicates_version_mismatch(b"shutdown"));
    }

    #[test]
    fn close_reason_with_unrecognised_non_v4_bytes_is_not_a_version_mismatch() {
        // Only the known legacy v4 byte (0x04) gates the diagnostic; other
        // arbitrary leading bytes are not treated as a recognised version.
        for byte in [0x00u8, 0x01, 0x02, 0x03, 0x06, 0x7f, 0xff] {
            assert!(!close_reason_indicates_version_mismatch(&[byte]), "byte {byte:#x} must not gate the diagnostic");
        }
    }

    #[test]
    fn close_reason_v5_byte_or_empty_is_not_a_version_mismatch() {
        assert!(!close_reason_indicates_version_mismatch(&[TUIC_VERSION]));
        assert!(!close_reason_indicates_version_mismatch(&[]));
    }

    #[test]
    fn tuic_handshake_error_display_is_actionable_and_carries_no_identifiers() {
        let message = TuicHandshakeError::version_unsupported().to_string();
        assert!(message.contains("v5"), "diagnostic must recommend the v5 upgrade: {message}");
        assert_eq!(TuicHandshakeError::version_unsupported().kind(), TuicFailureKind::VersionUnsupported);
    }
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::split_authority;
    use std::io;

    /// Regression test (audit H4 siblings): a bare IPv6 literal must be
    /// rejected instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) with a bogus port.
    #[test]
    fn split_authority_rejects_bare_ipv6_authority() {
        let error = split_authority("2001:db8::1").expect_err("bare IPv6 authority must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(split_authority("2001:db8::1:443").is_err(), "unbracketed IPv6 with port must be rejected");
    }

    #[test]
    fn split_authority_accepts_bracketed_ipv6_and_domain() {
        let bracketed = split_authority("[2001:db8::1]:443").expect("bracketed IPv6 authority parses");
        assert_eq!(bracketed, ("2001:db8::1".to_owned(), "443".to_owned()));
        let domain = split_authority("example.com:443").expect("domain authority parses");
        assert_eq!(domain, ("example.com".to_owned(), "443".to_owned()));
    }
}
