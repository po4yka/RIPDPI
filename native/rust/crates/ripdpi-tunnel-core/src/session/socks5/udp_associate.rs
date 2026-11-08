use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Send a SOCKS5 UDP ASSOCIATE request and return the relay `SocketAddr`.
///
/// The stream MUST have completed a successful [`handshake`](super::handshake)
/// before calling this function. Bind address `0.0.0.0:0` signals "any source"
/// per RFC 1928. Unspecified relay addresses and localhost aliases use the actual
/// control peer; other domain forms must be numeric IP literals because this
/// caller has no relay-name resolver policy.
///
/// # Cancel safety:
/// NOT cancel-safe: a partial request or reply leaves the stream mid-exchange.
/// The owner must drop the stream on cancellation or timeout.
pub async fn associate<S>(stream: &mut S, control_peer: IpAddr) -> io::Result<SocketAddr>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    request(stream, control_peer, None)
        .await?
        .ok_or_else(|| io::Error::new(io::ErrorKind::ConnectionRefused, "SOCKS5: UDP ASSOCIATE unsupported"))
}

/// Negotiate the owned local extension. `None` means an explicit REP 7 only.
///
/// # Cancel safety:
/// NOT cancel-safe: drop the stream after a partial exchange or unsupported reply.
pub(crate) async fn associate_feedback<S>(
    stream: &mut S,
    control_peer: IpAddr,
    source: SocketAddr,
) -> io::Result<Option<(SocketAddr, ripdpi_socks5_core::udp_feedback::Nonce)>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let Some(relay) = request(stream, control_peer, Some(source)).await? else { return Ok(None) };
    if !relay.ip().is_loopback() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: private relay is not loopback"));
    }
    let mut preface = [0; ripdpi_socks5_core::udp_feedback::PREFACE_LEN];
    stream.read_exact(&mut preface).await?;
    Ok(Some((relay, ripdpi_socks5_core::udp_feedback::decode_preface(&preface)?)))
}

/// # Cancel safety:
/// NOT cancel-safe: a request and its reply are one non-repeatable control exchange.
async fn request<S>(stream: &mut S, control_peer: IpAddr, source: Option<SocketAddr>) -> io::Result<Option<SocketAddr>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut req = vec![5, if source.is_some() { ripdpi_socks5_core::udp_feedback::COMMAND } else { 3 }, 0];
    let source = source.unwrap_or_else(|| SocketAddr::from(([0, 0, 0, 0], 0)));
    match source.ip() {
        IpAddr::V4(ip) => {
            req.push(1);
            req.extend_from_slice(&ip.octets());
        }
        IpAddr::V6(ip) => {
            req.push(4);
            req.extend_from_slice(&ip.octets());
        }
    }
    req.extend_from_slice(&source.port().to_be_bytes());
    stream.write_all(&req).await?;
    let mut hdr = [0; 4];
    stream.read_exact(&mut hdr).await?;
    if hdr[0] != 5 || hdr[2] != 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: invalid ASSOCIATE reply header"));
    }
    let unsupported = hdr[1] == 7;
    if hdr[1] != 0 && !unsupported {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS5: UDP ASSOCIATE failed with REP={:#04x}", hdr[1]),
        ));
    }

    // Parse BND.ADDR : BND.PORT
    let mut relay = match hdr[3] {
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
        0x03 => {
            let mut len = [0];
            stream.read_exact(&mut len).await?;
            if len[0] == 0 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: empty relay domain"));
            }
            let mut domain = vec![0; usize::from(len[0])];
            stream.read_exact(&mut domain).await?;
            let domain = std::str::from_utf8(&domain)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: invalid relay domain"))?;
            let mut port = [0; 2];
            stream.read_exact(&mut port).await?;
            let ip = if domain.eq_ignore_ascii_case("localhost") || domain.eq_ignore_ascii_case("localhost.") {
                control_peer
            } else {
                // The TUN client has no relay-name resolver policy; never fall back to system DNS.
                domain.parse::<IpAddr>().map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: relay domain requires a resolver policy")
                })?
            };
            SocketAddr::new(ip, u16::from_be_bytes(port))
        }
        t => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS5: unknown ATYP={t} in ASSOCIATE reply"),
            ));
        }
    };

    if unsupported {
        return Ok(None);
    }
    if relay.port() == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: relay port is zero"));
    }
    if relay.ip().is_unspecified() {
        relay.set_ip(control_peer);
    }
    Ok(Some(relay))
}
