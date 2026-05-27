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

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use boring::x509::X509;
use sha2::{Digest, Sha224};
use tokio::io::{AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
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
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrojanClientConfig {
    pub server_host: String,
    pub server_port: u16,
    pub server_name: String,
    pub password: String,
    pub tls_fingerprint_profile: String,
    pub root_certificate_pem: Option<String>,
}

impl TrojanClient {
    /// Open a TCP connection to the Trojan server, complete TLS with certificate verification, then send a CONNECT request and optional first payload.
    pub async fn connect_tcp(
        config: &TrojanClientConfig,
        target_addr: &TrojanAddr,
        target_port: u16,
        first_payload: &[u8],
    ) -> Result<SslStream<TcpStream>, TrojanError> {
        let tcp = TcpStream::connect((config.server_host.as_str(), config.server_port)).await?;
        tcp.set_nodelay(true)?;

        let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
            .map_err(|error| TrojanError::TlsProfile(error.to_string()))?;
        if let Some(root_pem) = &config.root_certificate_pem {
            let cert =
                X509::from_pem(root_pem.as_bytes()).map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
            builder.cert_store_mut().add_cert(cert).map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
        }

        let connector = builder.build();
        let config_ssl = connector.configure().map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
        let mut stream = tokio_boring::connect(config_ssl, &config.server_name, tcp)
            .await
            .map_err(|error| TrojanError::TlsHandshake(error.to_string()))?;
        let frame =
            build_request_frame(&config.password, TrojanCommand::TcpConnect, target_addr, target_port, first_payload)?;
        stream.write_all(&frame).await?;
        Ok(stream)
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
