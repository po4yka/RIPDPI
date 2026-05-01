use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Send a SOCKS5 UDP ASSOCIATE request and return the relay `SocketAddr`.
///
/// The stream MUST have completed a successful [`handshake`](super::handshake)
/// before calling this function. Bind address `0.0.0.0:0` signals "any source"
/// per RFC 1928.
pub async fn associate<S>(stream: &mut S) -> io::Result<SocketAddr>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // CMD=3 (UDP ASSOCIATE), bind 0.0.0.0:0
    let req = [
        0x05u8, 0x03, 0x00, // VER CMD RSV
        0x01, // ATYP = IPv4
        0x00, 0x00, 0x00, 0x00, // 0.0.0.0
        0x00, 0x00, // port 0
    ];
    stream.write_all(&req).await?;

    // Read reply header: [VER, REP, RSV, ATYP]
    let mut hdr = [0u8; 4];
    stream.read_exact(&mut hdr).await?;

    if hdr[1] != 0x00 {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS5: UDP ASSOCIATE failed with REP={:#04x}", hdr[1]),
        ));
    }

    // Parse BND.ADDR : BND.PORT
    let relay = match hdr[3] {
        0x01 => {
            let mut addr = [0u8; 4];
            stream.read_exact(&mut addr).await?;
            let mut port = [0u8; 2];
            stream.read_exact(&mut port).await?;
            SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            stream.read_exact(&mut addr).await?;
            let mut port = [0u8; 2];
            stream.read_exact(&mut port).await?;
            SocketAddr::new(IpAddr::V6(Ipv6Addr::from(addr)), u16::from_be_bytes(port))
        }
        t => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS5: unknown ATYP={t} in ASSOCIATE reply"),
            ));
        }
    };

    Ok(relay)
}
