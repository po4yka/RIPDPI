use std::io;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::TargetAddr;

/// Send a SOCKS5 CONNECT request and read the server reply.
///
/// The stream MUST have completed a successful [`handshake`](super::handshake)
/// before calling this function. On success the stream is in the
/// data-forwarding phase.
///
/// Wire formats sent:
/// - IPv4:   `[0x05, 0x01, 0x00, 0x01, a, b, c, d, ph, pl]`
/// - IPv6:   `[0x05, 0x01, 0x00, 0x04, <16 bytes>, ph, pl]`
/// - Domain: `[0x05, 0x01, 0x00, 0x03, len, <domain bytes>, ph, pl]`
///
/// # Cancel safety
///
/// NOT cancel-safe. It writes the CONNECT request then reads the reply header
/// and bound-address fields; dropping the future between the write and the full
/// read leaves the proxy stream mid-CONNECT (partially consumed reply), which a
/// subsequent reuse would mis-parse. The only caller, `TcpSession::run_with_proxy`,
/// awaits it to completion *before* entering the `cancel`-aware splice `select!`,
/// so it is never cancelled mid-exchange. Do not call it inside a `select!`/`timeout`.
pub async fn connect<S>(stream: &mut S, target: &TargetAddr) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // Build CONNECT request: VER=5, CMD=1(CONNECT), RSV=0, ATYP, addr, port
    let mut req = vec![0x05u8, 0x01, 0x00];

    match target {
        TargetAddr::Ip(addr) => match addr {
            std::net::SocketAddr::V4(v4) => {
                req.push(0x01);
                req.extend_from_slice(&v4.ip().octets());
                req.extend_from_slice(&v4.port().to_be_bytes());
            }
            std::net::SocketAddr::V6(v6) => {
                req.push(0x04);
                req.extend_from_slice(&v6.ip().octets());
                req.extend_from_slice(&v6.port().to_be_bytes());
            }
        },
        TargetAddr::Domain(domain, port) => {
            req.push(0x03);
            req.push(domain.len() as u8);
            req.extend_from_slice(domain.as_bytes());
            req.extend_from_slice(&port.to_be_bytes());
        }
    }

    stream.write_all(&req).await?;

    // Read reply header: [VER, REP, RSV, ATYP]
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;

    let rep = header[1];
    let atyp = header[3];

    // Consume bind address and port from reply
    match atyp {
        0x01 => {
            let mut buf = [0u8; 6]; // 4-byte IPv4 + 2-byte port
            stream.read_exact(&mut buf).await?;
        }
        0x04 => {
            let mut buf = [0u8; 18]; // 16-byte IPv6 + 2-byte port
            stream.read_exact(&mut buf).await?;
        }
        0x03 => {
            let mut len_buf = [0u8; 1];
            stream.read_exact(&mut len_buf).await?;
            let mut buf = vec![0u8; len_buf[0] as usize + 2]; // domain + 2-byte port
            stream.read_exact(&mut buf).await?;
        }
        _ => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: unknown address type in CONNECT reply"));
        }
    }

    if rep != 0x00 {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS5: CONNECT failed with REP={rep:#04x}"),
        ));
    }

    Ok(())
}
