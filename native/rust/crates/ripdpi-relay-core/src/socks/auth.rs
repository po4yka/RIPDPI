use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::backend::RelayBackend;
use crate::socks::connect::handle_connect;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::{SocksSessionConfig, SocksTelemetry};
use crate::socks::udp::handle_udp_associate;

pub(crate) async fn handle_client<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    config: SocksSessionConfig,
    telemetry: &T,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    // NOT cancel-safe: reads the method greeting, writes the method selection,
    // then drives the full SOCKS5 command exchange; cancellation mid-write
    // leaves the client in an undefined protocol state.
    negotiate_no_auth(&mut client).await?;

    let mut request_header = [0u8; 4];
    client.read_exact(&mut request_header).await?;
    if request_header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
    }

    let command = request_header[1];
    let target = read_target(&mut client, request_header[3]).await?;
    telemetry.record_target(target.to_string());

    match command {
        0x01 => handle_connect(client, backend, target, telemetry).await,
        0x03 => handle_udp_associate(client, backend, config, telemetry).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, format!("SOCKS5 command {command:#x} is not supported")))
        }
    }
}

/// Reads the SOCKS5 method-selection greeting and performs RFC 1928 method
/// negotiation. Replies `[0x05, 0x00]` (NO AUTH) when the client offers
/// method `0x00`; replies `[0x05, 0xFF]` (NO ACCEPTABLE METHODS) and returns
/// an error otherwise, as required by RFC 1928 §3.
// NOT cancel-safe: reads the greeting then writes the server's method choice;
// cancellation between the read and write leaves the client without a reply.
async fn negotiate_no_auth<S>(stream: &mut S) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 version"));
    }

    let method_count = usize::from(greeting[1]);
    let mut methods = vec![0u8; method_count];
    stream.read_exact(&mut methods).await?;

    if methods.contains(&0x00) {
        stream.write_all(&[0x05, 0x00]).await?;
        Ok(())
    } else {
        // RFC 1928 §3: if no offered method is acceptable the server replies
        // with 0xFF and MUST close the connection.
        stream.write_all(&[0x05, 0xFF]).await?;
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS5 client offered no acceptable auth method"))
    }
}

pub(crate) async fn read_target<S>(stream: &mut S, address_type: u8) -> io::Result<RelayTargetAddr>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let target = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            stream.read_exact(&mut octets).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), u16::from_be_bytes(port_bytes)))
        }
        0x03 => {
            let len = stream.read_u8().await?;
            let mut host = vec![0u8; usize::from(len)];
            stream.read_exact(&mut host).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            let host = String::from_utf8(host)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 domain target"))?;
            RelayTargetAddr::Domain(host, u16::from_be_bytes(port_bytes))
        }
        0x04 => {
            let mut octets = [0u8; 16];
            stream.read_exact(&mut octets).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), u16::from_be_bytes(port_bytes)))
        }
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported SOCKS5 address type {address_type:#x}"),
            ));
        }
    };
    Ok(target)
}

#[cfg(test)]
mod tests {
    use tokio::io::duplex;

    use super::negotiate_no_auth;

    #[tokio::test]
    async fn socks5_accepts_noauth_offer() {
        // Client offers only NO-AUTH (0x00); server must reply [0x05, 0x00].
        let (mut client_end, mut server_end) = duplex(64);

        // Write greeting: VER=5, NMETHODS=1, METHOD=0x00
        tokio::io::AsyncWriteExt::write_all(&mut client_end, &[0x05, 0x01, 0x00]).await.expect("write greeting");

        negotiate_no_auth(&mut server_end).await.expect("NO-AUTH must be accepted");

        let mut reply = [0u8; 2];
        tokio::io::AsyncReadExt::read_exact(&mut client_end, &mut reply).await.expect("read reply");
        assert_eq!(reply, [0x05, 0x00], "server must reply with method 0x00 (NO AUTH)");
    }

    #[tokio::test]
    async fn socks5_rejects_greeting_without_noauth() {
        // Client offers only user/pass (0x02); server must reply [0x05, 0xFF] and error.
        let (mut client_end, mut server_end) = duplex(64);

        // Write greeting: VER=5, NMETHODS=1, METHOD=0x02 (username/password)
        tokio::io::AsyncWriteExt::write_all(&mut client_end, &[0x05, 0x01, 0x02]).await.expect("write greeting");

        let result = negotiate_no_auth(&mut server_end).await;
        assert!(result.is_err(), "server must reject greeting without NO-AUTH");

        let mut reply = [0u8; 2];
        tokio::io::AsyncReadExt::read_exact(&mut client_end, &mut reply).await.expect("read reply");
        assert_eq!(reply, [0x05, 0xFF], "server must reply with 0xFF (NO ACCEPTABLE METHODS)");
    }
}
