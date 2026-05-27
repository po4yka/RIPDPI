//! Trojan outbound client — wire-format framing only.
//!
//! The caller is responsible for establishing the TLS stream before calling
//! [`TrojanClient::write_request`]. This crate never creates its own TLS context.
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

use sha2::{Digest, Sha224};
use tokio::io::{AsyncWrite, AsyncWriteExt};

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
    #[error("I/O error writing Trojan request: {0}")]
    Io(#[from] std::io::Error),
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

/// Stateless Trojan client helper.
///
/// Accepts an already-connected TLS stream and writes the Trojan request header.
/// The caller must then pipe payload bytes directly through the same stream.
pub struct TrojanClient;

impl TrojanClient {
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
