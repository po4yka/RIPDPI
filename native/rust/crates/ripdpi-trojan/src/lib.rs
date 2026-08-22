//! Trojan outbound client and wire-format framing.
//!
//! # Wire format
//! ```text
//! SHA-224(password) as 56 lowercase hex chars
//! CRLF
//! command byte  (0x01 = TCP CONNECT, 0x03 = UDP ASSOCIATE)
//! target ATYP   (0x01 IPv4 | 0x03 Domain | 0x04 IPv6)
//! target ADDR   (4 bytes | 1-byte-len + N bytes | 16 bytes)
//! target PORT   (2 bytes big-endian)
//! CRLF
//! [payload follows]
//! ```

#![forbid(unsafe_code)]

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;

use boring::x509::X509;
use sha2::{Digest, Sha224};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio_boring::SslStream;

/// Trojan command byte.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrojanCommand {
    /// TCP CONNECT (0x01)
    TcpConnect,
    /// UDP ASSOCIATE (0x03)
    UdpAssociate,
}

impl TrojanCommand {
    fn byte(self) -> u8 {
        match self {
            Self::TcpConnect => 0x01,
            Self::UdpAssociate => 0x03,
        }
    }
}

/// Target address — mirrors SOCKS5 address types.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrojanAddr {
    Ipv4(Ipv4Addr),
    Ipv6(Ipv6Addr),
    Domain(String),
}

/// Error type for Trojan framing operations.
#[derive(Debug, thiserror::Error)]
pub enum TrojanError {
    #[error("domain name too long ({0} bytes, max 255)")]
    DomainTooLong(usize),
    #[error("UDP payload too long ({0} bytes, max 65535)")]
    PayloadTooLong(usize),
    #[error("invalid address type byte 0x{0:02x}")]
    InvalidAddressType(u8),
    #[error("invalid CRLF delimiter")]
    InvalidCrlf,
    #[error("truncated Trojan UDP packet while reading {0}")]
    Truncated(&'static str),
    #[error("Trojan UDP packet has {0} trailing bytes after payload")]
    TrailingBytes(usize),
    #[error("I/O error writing Trojan request: {0}")]
    Io(#[from] std::io::Error),
    #[error("TLS profile error: {0}")]
    TlsProfile(String),
    #[error("TLS configuration error: {0}")]
    TlsConfig(String),
    #[error("TLS handshake error: {0}")]
    TlsHandshake(String),
}

/// Decoded Trojan UDP ASSOCIATE packet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrojanUdpPacket {
    pub addr: TrojanAddr,
    pub port: u16,
    pub payload: Vec<u8>,
}

/// Hash a password with SHA-224 and return the 56-character hex string.
///
/// This is the password token that appears at the start of every Trojan request.
pub fn hash_password(password: &str) -> String {
    let mut hasher = Sha224::new();
    hasher.update(password.as_bytes());
    hex::encode(hasher.finalize())
}

/// Encode a [`TrojanAddr`] + port into the SOCKS5-style byte representation.
///
/// Layout: `ATYP | ADDR | PORT(2 BE)`
pub fn encode_addr(addr: &TrojanAddr, port: u16) -> Result<Vec<u8>, TrojanError> {
    let mut buf = Vec::new();
    match addr {
        TrojanAddr::Ipv4(ip) => {
            buf.push(0x01);
            buf.extend_from_slice(&ip.octets());
        }
        TrojanAddr::Ipv6(ip) => {
            buf.push(0x04);
            buf.extend_from_slice(&ip.octets());
        }
        TrojanAddr::Domain(name) => {
            let bytes = name.as_bytes();
            if bytes.len() > 255 {
                return Err(TrojanError::DomainTooLong(bytes.len()));
            }
            buf.push(0x03);
            buf.push(bytes.len() as u8);
            buf.extend_from_slice(bytes);
        }
    }
    buf.extend_from_slice(&port.to_be_bytes());
    Ok(buf)
}

/// Build the complete Trojan request header as a byte vector (without payload).
///
/// Layout: `password_hex CRLF command addr_block CRLF`
pub fn build_request(
    password: &str,
    command: TrojanCommand,
    addr: &TrojanAddr,
    port: u16,
) -> Result<Vec<u8>, TrojanError> {
    build_request_frame(password, command, addr, port, &[])
}

/// Build a Trojan request frame with an optional first payload.
///
/// Layout: `password_hex CRLF command addr_block CRLF payload`
pub fn build_request_frame(
    password: &str,
    command: TrojanCommand,
    addr: &TrojanAddr,
    port: u16,
    payload: &[u8],
) -> Result<Vec<u8>, TrojanError> {
    let hex_pw = hash_password(password);
    let addr_bytes = encode_addr(addr, port)?;

    let mut buf = Vec::with_capacity(56 + 2 + 1 + addr_bytes.len() + 2 + payload.len());
    buf.extend_from_slice(hex_pw.as_bytes());
    buf.extend_from_slice(b"\r\n");
    buf.push(command.byte());
    buf.extend_from_slice(&addr_bytes);
    buf.extend_from_slice(b"\r\n");
    buf.extend_from_slice(payload);
    Ok(buf)
}

/// Encode one Trojan UDP ASSOCIATE packet.
///
/// Layout: `ATYP ADDR PORT Length CRLF Payload`
pub fn encode_udp_packet(addr: &TrojanAddr, port: u16, payload: &[u8]) -> Result<Vec<u8>, TrojanError> {
    let payload_len = u16::try_from(payload.len()).map_err(|_| TrojanError::PayloadTooLong(payload.len()))?;
    let addr_bytes = encode_addr(addr, port)?;
    let mut buf = Vec::with_capacity(addr_bytes.len() + 2 + 2 + payload.len());
    buf.extend_from_slice(&addr_bytes);
    buf.extend_from_slice(&payload_len.to_be_bytes());
    buf.extend_from_slice(b"\r\n");
    buf.extend_from_slice(payload);
    Ok(buf)
}

/// Decode one complete Trojan UDP ASSOCIATE packet.
///
/// Layout: `ATYP ADDR PORT Length CRLF Payload`
pub fn decode_udp_packet(packet: &[u8]) -> Result<TrojanUdpPacket, TrojanError> {
    let mut cursor = 0;
    let addr = decode_addr(packet, &mut cursor)?;
    let port = read_u16(packet, &mut cursor, "port")?;
    let payload_len = usize::from(read_u16(packet, &mut cursor, "payload length")?);
    let crlf = read_exact(packet, &mut cursor, 2, "CRLF")?;
    if crlf != b"\r\n" {
        return Err(TrojanError::InvalidCrlf);
    }
    let payload = read_exact(packet, &mut cursor, payload_len, "payload")?.to_vec();
    if cursor != packet.len() {
        return Err(TrojanError::TrailingBytes(packet.len() - cursor));
    }
    Ok(TrojanUdpPacket { addr, port, payload })
}

fn decode_addr(packet: &[u8], cursor: &mut usize) -> Result<TrojanAddr, TrojanError> {
    let atyp = read_u8(packet, cursor, "address type")?;
    match atyp {
        0x01 => {
            let bytes = read_exact(packet, cursor, 4, "IPv4 address")?;
            Ok(TrojanAddr::Ipv4(Ipv4Addr::new(bytes[0], bytes[1], bytes[2], bytes[3])))
        }
        0x03 => {
            let len = usize::from(read_u8(packet, cursor, "domain length")?);
            let bytes = read_exact(packet, cursor, len, "domain address")?;
            let domain = String::from_utf8_lossy(bytes).into_owned();
            Ok(TrojanAddr::Domain(domain))
        }
        0x04 => {
            let bytes = read_exact(packet, cursor, 16, "IPv6 address")?;
            let mut octets = [0_u8; 16];
            octets.copy_from_slice(bytes);
            Ok(TrojanAddr::Ipv6(Ipv6Addr::from(octets)))
        }
        other => Err(TrojanError::InvalidAddressType(other)),
    }
}

fn read_u8(packet: &[u8], cursor: &mut usize, field: &'static str) -> Result<u8, TrojanError> {
    Ok(read_exact(packet, cursor, 1, field)?[0])
}

fn read_u16(packet: &[u8], cursor: &mut usize, field: &'static str) -> Result<u16, TrojanError> {
    let bytes = read_exact(packet, cursor, 2, field)?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn read_exact<'a>(
    packet: &'a [u8],
    cursor: &mut usize,
    len: usize,
    field: &'static str,
) -> Result<&'a [u8], TrojanError> {
    let end = cursor.checked_add(len).ok_or(TrojanError::Truncated(field))?;
    let bytes = packet.get(*cursor..end).ok_or(TrojanError::Truncated(field))?;
    *cursor = end;
    Ok(bytes)
}

/// Stateless Trojan client helper.
///
/// Accepts an already-connected TLS stream and writes the Trojan request header.
/// The caller must then pipe payload bytes directly through the same stream.
pub struct TrojanClient;

/// TLS CONNECT client configuration.
#[derive(Clone, PartialEq, Eq)]
pub struct TrojanClientConfig {
    pub server_host: String,
    pub server_port: u16,
    pub server_name: String,
    pub password: String,
    pub tls_fingerprint_profile: String,
    pub root_certificate_pem: Option<String>,
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    /// Local address the carrier socket is bound to before connect (interface
    /// pinning). `None` binds to an unspecified address. The bind IP family must
    /// match the resolved server address family or the connect fails closed.
    pub outbound_bind_ip: Option<IpAddr>,
}

impl std::fmt::Debug for TrojanClientConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("TrojanClientConfig")
            .field("server_host", &self.server_host)
            .field("server_port", &self.server_port)
            .field("server_name", &self.server_name)
            .field("password", &"<redacted>")
            .field("tls_fingerprint_profile", &self.tls_fingerprint_profile)
            .field("root_certificate_pem", &self.root_certificate_pem.as_ref().map(|_| "<redacted>"))
            .field("socket_protection", &self.socket_protection)
            .field("outbound_bind_ip", &self.outbound_bind_ip)
            .finish()
    }
}

/// Open a protected TCP connection to the Trojan server.
///
/// Builds the socket explicitly and protects its fd via the in-process
/// `VpnService.protect()` registry BEFORE connect, so the non-loopback carrier
/// socket bypasses the app's own TUN route. Loopback-skip and fail-closed are
/// handled by [`protect_outbound_socket`]. REL-1.
async fn connect_server_tcp(config: &TrojanClientConfig) -> io::Result<TcpStream> {
    let bind_ip = config.outbound_bind_ip;
    let addrs = config.socket_protection.resolve_host(&config.server_host, config.server_port).await?;
    let server_addr = match bind_ip {
        Some(ip) => addrs.into_iter().find(|addr| addr.is_ipv4() == ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no server address matches outbound bind IP family")
        })?,
        None => addrs
            .into_iter()
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no address resolved for trojan server"))?,
    };
    let socket = match server_addr {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    protect_outbound_socket(&socket, server_addr, config.socket_protection)?;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    let tcp = socket.connect(server_addr).await?;
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

/// Protect a freshly created outbound socket via the registered
/// `VpnService.protect()` callback before it connects to a non-loopback peer.
///
/// No-op for loopback. Fails closed for a non-loopback target when no callback
/// is registered: under a live TUN there is no other per-socket mechanism to
/// keep the socket out of the tunnel, so refusing is safer than dialing
/// unprotected. (Own-UID TUN exclusion via `computeAppRoutingPlan` remains the
/// second layer.) Mirrors the `ripdpi-vless` gold-standard helper.
fn protect_outbound_socket<T: AsRawFd>(
    socket: &T,
    target: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect_non_loopback(socket.as_raw_fd(), target)
        .map_err(|error| io::Error::new(error.kind(), format!("protect Trojan outbound socket: {error}")))
}

impl TrojanClient {
    /// Open a TCP connection to the Trojan server, complete TLS with certificate verification, then send a CONNECT request and optional first payload.
    pub async fn connect_tcp(
        config: &TrojanClientConfig,
        target_addr: &TrojanAddr,
        target_port: u16,
        first_payload: &[u8],
    ) -> Result<SslStream<TcpStream>, TrojanError> {
        let tcp = connect_server_tcp(config).await?;
        connect_with_request_over(config, tcp, TrojanCommand::TcpConnect, target_addr, target_port, first_payload).await
    }

    /// Complete TLS and send a TCP CONNECT request over an existing transport to the Trojan server.
    pub async fn connect_tcp_over<S>(
        config: &TrojanClientConfig,
        transport: S,
        target_addr: &TrojanAddr,
        target_port: u16,
        first_payload: &[u8],
    ) -> Result<SslStream<S>, TrojanError>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        connect_with_request_over(config, transport, TrojanCommand::TcpConnect, target_addr, target_port, first_payload)
            .await
    }

    /// Open a TCP connection to the Trojan server, complete TLS with certificate verification, then send a UDP ASSOCIATE request.
    pub async fn connect_udp_associate(config: &TrojanClientConfig) -> Result<SslStream<TcpStream>, TrojanError> {
        let tcp = connect_server_tcp(config).await?;
        connect_with_request_over(
            config,
            tcp,
            TrojanCommand::UdpAssociate,
            &TrojanAddr::Ipv4(Ipv4Addr::UNSPECIFIED),
            0,
            &[],
        )
        .await
    }

    /// Write a Trojan request header onto `stream`.
    ///
    /// `password` is the plaintext password; it is SHA-224-hashed in memory
    /// and never written to disk or logs.
    pub async fn write_request<S>(
        stream: &mut S,
        password: &str,
        command: TrojanCommand,
        addr: &TrojanAddr,
        port: u16,
    ) -> Result<(), TrojanError>
    where
        S: AsyncWrite + Unpin,
    {
        let header = build_request(password, command, addr, port)?;
        stream.write_all(&header).await?;
        Ok(())
    }
}

async fn connect_with_request_over<S>(
    config: &TrojanClientConfig,
    transport: S,
    command: TrojanCommand,
    target_addr: &TrojanAddr,
    target_port: u16,
    first_payload: &[u8],
) -> Result<SslStream<S>, TrojanError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| TrojanError::TlsProfile(error.to_string()))?;
    if let Some(root_pem) = &config.root_certificate_pem {
        let cert = X509::from_pem(root_pem.as_bytes()).map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
        builder.cert_store_mut().add_cert(cert).map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
    }

    let connector = builder.build();
    let config_ssl = connector.configure().map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
    let mut stream = tokio_boring::connect(config_ssl, &config.server_name, transport)
        .await
        .map_err(|error| TrojanError::TlsHandshake(error.to_string()))?;
    let frame = build_request_frame(&config.password, command, target_addr, target_port, first_payload)?;
    stream.write_all(&frame).await?;
    Ok(stream)
}

/// Read one Trojan UDP ASSOCIATE packet from a stream.
pub async fn read_udp_packet<S>(stream: &mut S) -> Result<TrojanUdpPacket, TrojanError>
where
    S: AsyncRead + Unpin,
{
    let atyp = read_stream_u8(stream).await?;
    let addr = match atyp {
        0x01 => {
            let mut octets = [0_u8; 4];
            stream.read_exact(&mut octets).await?;
            TrojanAddr::Ipv4(Ipv4Addr::from(octets))
        }
        0x03 => {
            let len = usize::from(read_stream_u8(stream).await?);
            let mut bytes = vec![0_u8; len];
            stream.read_exact(&mut bytes).await?;
            TrojanAddr::Domain(String::from_utf8_lossy(&bytes).into_owned())
        }
        0x04 => {
            let mut octets = [0_u8; 16];
            stream.read_exact(&mut octets).await?;
            TrojanAddr::Ipv6(Ipv6Addr::from(octets))
        }
        other => return Err(TrojanError::InvalidAddressType(other)),
    };
    let mut port = [0_u8; 2];
    stream.read_exact(&mut port).await?;
    let mut len = [0_u8; 2];
    stream.read_exact(&mut len).await?;
    let payload_len = usize::from(u16::from_be_bytes(len));
    let mut crlf = [0_u8; 2];
    stream.read_exact(&mut crlf).await?;
    if crlf != *b"\r\n" {
        return Err(TrojanError::InvalidCrlf);
    }
    let mut payload = vec![0_u8; payload_len];
    stream.read_exact(&mut payload).await?;
    Ok(TrojanUdpPacket { addr, port: u16::from_be_bytes(port), payload })
}

async fn read_stream_u8<S>(stream: &mut S) -> Result<u8, TrojanError>
where
    S: AsyncRead + Unpin,
{
    let mut byte = [0_u8; 1];
    stream.read_exact(&mut byte).await?;
    Ok(byte[0])
}

/// Helper: parse a raw IP address string into a [`TrojanAddr`].
impl From<IpAddr> for TrojanAddr {
    fn from(ip: IpAddr) -> Self {
        match ip {
            IpAddr::V4(v4) => Self::Ipv4(v4),
            IpAddr::V6(v6) => Self::Ipv6(v6),
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};
    use tokio::io::duplex;

    const SPEC_PASSWORD: &str = "123456789";
    const SPEC_PASSWORD_SHA224_HEX: &str = "9b3e61bf29f17c75572fae2e86e17809a4513d07c8a18152acf34521";

    #[test]
    fn password_hash_matches_spec_vector() {
        assert_eq!(hash_password(SPEC_PASSWORD), SPEC_PASSWORD_SHA224_HEX);
    }

    #[test]
    fn password_hash_spec_vector_is_56_ascii_hex_chars() {
        let hash = hash_password(SPEC_PASSWORD);
        assert_eq!(hash.len(), 56);
        assert!(hash.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()));
    }

    #[test]
    fn client_config_debug_redacts_password_and_root_certificate() {
        let config = TrojanClientConfig {
            server_host: "relay.example".to_string(),
            server_port: 443,
            server_name: "cover.example".to_string(),
            password: "debug-trojan-secret".to_string(),
            tls_fingerprint_profile: "chrome".to_string(),
            root_certificate_pem: Some("debug-root-certificate-secret".to_string()),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        };

        let rendered = format!("{config:?}");

        assert!(!rendered.contains("debug-trojan-secret"));
        assert!(!rendered.contains("debug-root-certificate-secret"));
        assert!(rendered.contains("<redacted>"));
    }

    #[test]
    fn connect_ipv4_request_frame_matches_spec_golden() {
        let frame = build_request_frame(
            SPEC_PASSWORD,
            TrojanCommand::TcpConnect,
            &TrojanAddr::Ipv4(Ipv4Addr::new(1, 2, 3, 4)),
            443,
            b"GET / HTTP/1.1\r\n",
        )
        .unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(SPEC_PASSWORD_SHA224_HEX.as_bytes());
        expected.extend_from_slice(b"\r\n");
        expected.extend_from_slice(&[0x01, 0x01, 1, 2, 3, 4, 0x01, 0xbb]);
        expected.extend_from_slice(b"\r\nGET / HTTP/1.1\r\n");
        assert_eq!(frame, expected);
    }

    #[test]
    fn connect_domain_request_frame_matches_spec_golden() {
        let frame = build_request_frame(
            SPEC_PASSWORD,
            TrojanCommand::TcpConnect,
            &TrojanAddr::Domain("example.com".to_owned()),
            8443,
            b"",
        )
        .unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(SPEC_PASSWORD_SHA224_HEX.as_bytes());
        expected.extend_from_slice(b"\r\n");
        expected.extend_from_slice(&[0x01, 0x03, 11]);
        expected.extend_from_slice(b"example.com");
        expected.extend_from_slice(&[0x20, 0xfb]);
        expected.extend_from_slice(b"\r\n");
        assert_eq!(frame, expected);
    }

    #[test]
    fn udp_associate_ipv6_request_frame_matches_spec_golden() {
        let frame = build_request_frame(
            SPEC_PASSWORD,
            TrojanCommand::UdpAssociate,
            &TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST),
            53,
            b"",
        )
        .unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(SPEC_PASSWORD_SHA224_HEX.as_bytes());
        expected.extend_from_slice(b"\r\n");
        expected.extend_from_slice(&[0x03, 0x04]);
        expected.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
        expected.extend_from_slice(&[0x00, 0x35]);
        expected.extend_from_slice(b"\r\n");
        assert_eq!(frame, expected);
    }

    #[test]
    fn udp_ipv4_packet_matches_spec_golden() {
        let packet = encode_udp_packet(&TrojanAddr::Ipv4(Ipv4Addr::new(8, 8, 8, 8)), 53, &[0xde, 0xad]).unwrap();

        assert_eq!(packet, vec![0x01, 8, 8, 8, 8, 0x00, 0x35, 0x00, 0x02, 0x0d, 0x0a, 0xde, 0xad]);
    }

    #[test]
    fn udp_domain_packet_matches_spec_golden() {
        let packet = encode_udp_packet(&TrojanAddr::Domain("dns.example".to_owned()), 5353, b"dns").unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(&[0x03, 11]);
        expected.extend_from_slice(b"dns.example");
        expected.extend_from_slice(&[0x14, 0xe9, 0x00, 0x03, 0x0d, 0x0a]);
        expected.extend_from_slice(b"dns");
        assert_eq!(packet, expected);
    }

    #[test]
    fn udp_ipv6_packet_round_trips() {
        let packet = encode_udp_packet(&TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST), 443, b"payload").unwrap();

        let decoded = decode_udp_packet(&packet).unwrap();

        assert_eq!(decoded.addr, TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST));
        assert_eq!(decoded.port, 443);
        assert_eq!(decoded.payload, b"payload");
    }

    #[test]
    fn udp_packet_rejects_invalid_crlf() {
        let packet = vec![0x01, 1, 1, 1, 1, 0x00, 0x35, 0x00, 0x01, 0x0a, 0x0d, 0xff];

        let error = decode_udp_packet(&packet).unwrap_err();

        assert!(matches!(error, TrojanError::InvalidCrlf));
    }

    #[test]
    fn udp_packet_rejects_payloads_over_u16_length() {
        let payload = vec![0; usize::from(u16::MAX) + 1];

        let error = encode_udp_packet(&TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST), 53, &payload).unwrap_err();

        assert!(matches!(error, TrojanError::PayloadTooLong(65536)));
    }

    #[test]
    fn encode_ipv4_target() {
        let addr = TrojanAddr::Ipv4(Ipv4Addr::new(1, 2, 3, 4));
        let bytes = encode_addr(&addr, 443).unwrap();
        assert_eq!(bytes[0], 0x01, "ATYP must be 0x01 for IPv4");
        assert_eq!(&bytes[1..5], &[1, 2, 3, 4]);
        assert_eq!(&bytes[5..7], &[0x01, 0xBB]); // port 443 big-endian
        assert_eq!(bytes.len(), 7);
    }

    #[test]
    fn encode_ipv6_target() {
        let addr = TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST);
        let bytes = encode_addr(&addr, 80).unwrap();
        assert_eq!(bytes[0], 0x04, "ATYP must be 0x04 for IPv6");
        assert_eq!(bytes.len(), 19); // 1 + 16 + 2
        assert_eq!(&bytes[17..19], &[0x00, 0x50]); // port 80
    }

    #[test]
    fn encode_domain_target() {
        let addr = TrojanAddr::Domain("example.com".to_owned());
        let bytes = encode_addr(&addr, 8080).unwrap();
        assert_eq!(bytes[0], 0x03, "ATYP must be 0x03 for domain");
        assert_eq!(bytes[1], 11, "length prefix must be 11 for 'example.com'");
        assert_eq!(&bytes[2..13], b"example.com");
        assert_eq!(&bytes[13..15], &[0x1F, 0x90]); // port 8080
    }

    #[test]
    fn build_tcp_connect_request_ipv4() {
        let req = build_request(
            "trojan-fixture-pw",
            TrojanCommand::TcpConnect,
            &TrojanAddr::Ipv4(Ipv4Addr::new(127, 0, 0, 1)),
            443,
        )
        .unwrap();

        // password hex (56) + CRLF (2) + cmd (1) + ATYP+addr+port (7) + CRLF (2) = 68
        assert_eq!(req.len(), 68);
        assert_eq!(&req[56..58], b"\r\n");
        assert_eq!(req[58], 0x01); // TCP CONNECT
        assert_eq!(req[59], 0x01); // ATYP IPv4
        assert_eq!(&req[60..64], &[127, 0, 0, 1]);
        assert_eq!(&req[64..66], &[0x01, 0xBB]); // port 443
        assert_eq!(&req[66..68], b"\r\n");
    }

    #[test]
    fn build_udp_associate_request() {
        let req = build_request(
            "trojan-fixture-pw",
            TrojanCommand::UdpAssociate,
            &TrojanAddr::Ipv4(Ipv4Addr::new(10, 0, 0, 1)),
            53,
        )
        .unwrap();
        assert_eq!(req[58], 0x03); // UDP ASSOCIATE
    }

    #[test]
    fn build_domain_request_correct_length() {
        let domain = "example.com";
        let req =
            build_request("trojan-fixture-pw", TrojanCommand::TcpConnect, &TrojanAddr::Domain(domain.to_owned()), 443)
                .unwrap();
        // 56 + 2 + 1 + (1 + 1 + 11 + 2) + 2 = 76
        assert_eq!(req.len(), 76);
        assert_eq!(req[58], 0x01); // TCP CONNECT
        assert_eq!(req[59], 0x03); // ATYP domain
        assert_eq!(req[60], 11); // domain length
    }

    #[tokio::test]
    async fn full_handshake_duplex_ipv4() {
        let (mut client_side, mut server_side) = duplex(4096);

        TrojanClient::write_request(
            &mut client_side,
            "trojan-fixture-pw",
            TrojanCommand::TcpConnect,
            &TrojanAddr::Ipv4(Ipv4Addr::new(93, 184, 216, 34)),
            443,
        )
        .await
        .unwrap();

        // Drop writer so reader sees EOF
        drop(client_side);

        let mut received = Vec::new();
        tokio::io::AsyncReadExt::read_to_end(&mut server_side, &mut received).await.unwrap();

        assert_eq!(received.len(), 68);
        // Verify password hex prefix
        let expected_hex = hash_password("trojan-fixture-pw");
        assert_eq!(&received[..56], expected_hex.as_bytes());
        assert_eq!(&received[56..58], b"\r\n");
        assert_eq!(received[58], 0x01); // TCP CONNECT
    }

    #[tokio::test]
    async fn full_handshake_duplex_domain() {
        let (mut client_side, mut server_side) = duplex(4096);

        TrojanClient::write_request(
            &mut client_side,
            "trojan-fixture-pw",
            TrojanCommand::TcpConnect,
            &TrojanAddr::Domain("example.com".to_owned()),
            443,
        )
        .await
        .unwrap();

        drop(client_side);

        let mut received = Vec::new();
        tokio::io::AsyncReadExt::read_to_end(&mut server_side, &mut received).await.unwrap();

        assert_eq!(received.len(), 76);
        assert_eq!(&received[56..58], b"\r\n");
        assert_eq!(received[58], 0x01);
        assert_eq!(received[59], 0x03); // domain ATYP
    }

    #[test]
    fn domain_too_long_returns_error() {
        let long_domain = "a".repeat(256);
        let result = encode_addr(&TrojanAddr::Domain(long_domain), 80);
        assert!(matches!(result, Err(TrojanError::DomainTooLong(256))));
    }

    #[test]
    fn from_ipaddr_v4() {
        let ip: IpAddr = "1.2.3.4".parse().unwrap();
        let addr = TrojanAddr::from(ip);
        assert_eq!(addr, TrojanAddr::Ipv4(Ipv4Addr::new(1, 2, 3, 4)));
    }

    #[test]
    fn from_ipaddr_v6() {
        let ip: IpAddr = "::1".parse().unwrap();
        let addr = TrojanAddr::from(ip);
        assert_eq!(addr, TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST));
    }
}

#[cfg(test)]
mod protect_tests {
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::{io, protect_outbound_socket};

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let err = protect_outbound_socket(
            &listener,
            non_loopback(),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect_err("must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn non_loopback_is_protected_via_callback() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd());
        unregister_protect_callback();
    }

    #[test]
    fn loopback_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1);
        unregister_protect_callback();
    }
}

// ---------------------------------------------------------------------------
// ClientHello fingerprint-parity tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod client_hello_parity_tests {
    //! Prove the Trojan outbound ClientHello carries the selected
    //! `ripdpi-tls-profiles` browser fingerprint. Trojan's whole cover is that a
    //! server forwards auth-failing connections to a real fallback site
    //! (`https://trojan-gfw.github.io/trojan/protocol.html`), so the outbound
    //! ClientHello must look like the configured browser or the connection
    //! sticks out as not-a-browser and the cover is void.
    //!
    //! The oracle is the `chrome_stable` profile spec (`chrome::CHROME_LATEST`):
    //! the `X25519MLKEM768:X25519:P-256:P-384` groups, the `h2`/`http/1.1` ALPN,
    //! and `grease_enabled = true`. None of these are emitted by a stock
    //! BoringSSL ClientHello — the negative control at the end of the test
    //! proves the assertions discriminate a real browser profile from the
    //! default and are therefore not tautological.

    use super::{TrojanAddr, TrojanClient, TrojanClientConfig};
    use std::io::Read;
    use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    const PROFILE: &str = "chrome_stable";
    // chrome_stable supported groups, GREASE-stripped (CHROME_LATEST.curves =
    // "X25519MLKEM768:X25519:P-256:P-384").
    const X25519MLKEM768: u16 = 0x11ec;
    const X25519: u16 = 0x001d;
    const SECP256R1: u16 = 0x0017;
    const SECP384R1: u16 = 0x0018;

    #[derive(Debug)]
    struct ParsedHello {
        ciphers: Vec<u16>,
        ext_types: Vec<u16>,
        groups: Vec<u16>,
        alpn: Vec<Vec<u8>>,
    }

    // A GREASE value has both bytes equal and the low nibble == 0x0a (RFC 8701).
    fn is_grease(value: u16) -> bool {
        let [hi, lo] = value.to_be_bytes();
        hi == lo && (hi & 0x0f) == 0x0a
    }

    fn strip_grease(values: &[u16]) -> Vec<u16> {
        values.iter().copied().filter(|value| !is_grease(*value)).collect()
    }

    fn sorted_no_grease(values: &[u16]) -> Vec<u16> {
        let mut out = strip_grease(values);
        out.sort_unstable();
        out
    }

    fn be16(bytes: &[u8], index: usize) -> u16 {
        u16::from_be_bytes([bytes[index], bytes[index + 1]])
    }

    /// Minimal, bounds-checked ClientHello parser that reads only the
    /// JA3-relevant fields: cipher suites, extension types, offered supported
    /// groups, and offered ALPN. Panics (test failure) on malformed input.
    fn parse_client_hello(record: &[u8]) -> ParsedHello {
        assert_eq!(record.first().copied(), Some(0x16), "expected a TLS handshake record");
        let payload = &record[5..];
        assert_eq!(payload.first().copied(), Some(0x01), "expected a ClientHello handshake message");
        let hs_len = (usize::from(payload[1]) << 16) | (usize::from(payload[2]) << 8) | usize::from(payload[3]);
        let body = &payload[4..4 + hs_len];

        // legacy_version(2) + random(32)
        let mut cursor = 34_usize;
        let session_id_len = usize::from(body[cursor]);
        cursor += 1 + session_id_len;

        let cipher_bytes = usize::from(be16(body, cursor));
        cursor += 2;
        let cipher_end = cursor + cipher_bytes;
        let mut ciphers = Vec::new();
        while cursor + 1 < cipher_end {
            ciphers.push(be16(body, cursor));
            cursor += 2;
        }
        cursor = cipher_end;

        let compression_len = usize::from(body[cursor]);
        cursor += 1 + compression_len;

        let ext_total = usize::from(be16(body, cursor));
        cursor += 2;
        let ext_end = cursor + ext_total;
        let mut ext_types = Vec::new();
        let mut groups = Vec::new();
        let mut alpn = Vec::new();
        while cursor + 4 <= ext_end {
            let ext_type = be16(body, cursor);
            let ext_len = usize::from(be16(body, cursor + 2));
            let data = &body[cursor + 4..cursor + 4 + ext_len];
            ext_types.push(ext_type);
            match ext_type {
                0x000a => groups = parse_u16_list(data), // supported_groups
                0x0010 => alpn = parse_alpn(data),       // application_layer_protocol_negotiation
                _ => {}
            }
            cursor += 4 + ext_len;
        }

        ParsedHello { ciphers, ext_types, groups, alpn }
    }

    fn parse_u16_list(data: &[u8]) -> Vec<u16> {
        if data.len() < 2 {
            return Vec::new();
        }
        let list_len = usize::from(be16(data, 0));
        let end = 2 + list_len.min(data.len() - 2);
        let mut out = Vec::new();
        let mut index = 2;
        while index + 1 < end {
            out.push(be16(data, index));
            index += 2;
        }
        out
    }

    fn parse_alpn(data: &[u8]) -> Vec<Vec<u8>> {
        if data.len() < 2 {
            return Vec::new();
        }
        let list_len = usize::from(be16(data, 0));
        let end = 2 + list_len.min(data.len() - 2);
        let mut out = Vec::new();
        let mut index = 2;
        while index < end {
            let proto_len = usize::from(data[index]);
            index += 1;
            if index + proto_len > end {
                break;
            }
            out.push(data[index..index + proto_len].to_vec());
            index += proto_len;
        }
        out
    }

    /// Bind a loopback listener and capture the first TLS record an inbound
    /// connection sends. The caller initiates the connection toward the returned
    /// address; the handshake is expected to fail once the listener drops the
    /// socket — only the ClientHello is needed.
    fn spawn_capture() -> (SocketAddr, thread::JoinHandle<Vec<u8>>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind capture listener");
        let addr = listener.local_addr().expect("capture listener addr");
        let handle = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept capture connection");
            socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set capture read timeout");
            let mut header = [0_u8; 5];
            socket.read_exact(&mut header).expect("read TLS record header");
            let payload_len = usize::from(u16::from_be_bytes([header[3], header[4]]));
            let mut payload = vec![0_u8; payload_len];
            socket.read_exact(&mut payload).expect("read TLS record payload");
            let _ = socket.shutdown(Shutdown::Both);
            [header.to_vec(), payload].concat()
        });
        (addr, handle)
    }

    fn capture_sync(connect: impl FnOnce(SocketAddr)) -> ParsedHello {
        let (addr, handle) = spawn_capture();
        connect(addr);
        parse_client_hello(&handle.join().expect("capture thread join"))
    }

    async fn capture_trojan_client_hello() -> ParsedHello {
        let (addr, handle) = spawn_capture();
        let config = TrojanClientConfig {
            server_host: Ipv4Addr::LOCALHOST.to_string(),
            server_port: addr.port(),
            server_name: "trojan-parity.test".to_owned(),
            password: "parity-password".to_owned(),
            tls_fingerprint_profile: PROFILE.to_owned(),
            root_certificate_pem: None,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        };
        // connect_tcp opens the TCP socket, builds the BoringSSL connector from
        // the profile, and writes the ClientHello before the handshake fails on
        // the listener's shutdown. We discard the resulting error.
        let _ = TrojanClient::connect_tcp(&config, &TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST), 443, &[]).await;
        parse_client_hello(&handle.join().expect("capture thread join"))
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn client_hello_carries_selected_browser_profile_fingerprint() {
        let trojan = capture_trojan_client_hello().await;
        let profile = capture_sync(|addr| {
            let connector = ripdpi_tls_profiles::build_connector(PROFILE, false).expect("build profile connector");
            let stream = TcpStream::connect(addr).expect("connect profile capture");
            let _ = connector.connect("trojan-parity.test", stream);
        });
        let stock = capture_sync(|addr| {
            let connector = boring::ssl::SslConnector::builder(boring::ssl::SslMethod::tls())
                .expect("stock connector builder")
                .build();
            let stream = TcpStream::connect(addr).expect("connect stock capture");
            let _ = connector.connect("trojan-parity.test", stream);
        });

        // (1) Independent spec oracle: chrome_stable offers exactly these groups
        // (CHROME_LATEST.curves). A stock BoringSSL ClientHello never offers
        // X25519MLKEM768.
        assert_eq!(
            strip_grease(&trojan.groups),
            vec![X25519MLKEM768, X25519, SECP256R1, SECP384R1],
            "Trojan ClientHello must offer the chrome_stable supported groups"
        );
        // (2) chrome_stable offers h2 then http/1.1 (CHROME_LATEST.alpn).
        assert_eq!(
            trojan.alpn,
            vec![b"h2".to_vec(), b"http/1.1".to_vec()],
            "Trojan ClientHello must offer the chrome_stable ALPN list"
        );
        // (3) chrome_stable sets grease_enabled = true.
        assert!(trojan.ciphers.iter().any(|cipher| is_grease(*cipher)), "expected a GREASE cipher suite");
        assert!(trojan.ext_types.iter().any(|ext| is_grease(*ext)), "expected a GREASE extension");

        // (4) Matches the SELECTED profile precisely. Cipher order is stable (not
        // permuted) so it must equal the profile connector's; extensions are
        // permuted (permute_extensions = true) so compare as a GREASE-folded set.
        assert_eq!(
            strip_grease(&trojan.ciphers),
            strip_grease(&profile.ciphers),
            "Trojan cipher suites must match the chrome_stable profile connector"
        );
        assert_eq!(
            sorted_no_grease(&trojan.ext_types),
            sorted_no_grease(&profile.ext_types),
            "Trojan extension set must match the chrome_stable profile connector"
        );
        assert_eq!(
            strip_grease(&trojan.groups),
            strip_grease(&profile.groups),
            "Trojan supported groups must match the chrome_stable profile connector"
        );

        // (5) Negative control: a stock BoringSSL ClientHello is a different
        // fingerprint, proving the assertions above discriminate a browser
        // profile from the default rather than passing vacuously.
        assert_ne!(
            sorted_no_grease(&stock.groups),
            sorted_no_grease(&trojan.groups),
            "stock BoringSSL groups must differ from the chrome_stable profile"
        );
    }
}
