use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

use crate::backend::RelayBackend;
use crate::socks::connect::handle_connect;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::{SocksSessionConfig, SocksTelemetry};
use crate::socks::udp::handle_udp_associate;

/// Negotiate the SOCKS5 method, parse the request, and dispatch the command.
///
/// # Cancel safety
///
/// Cancel-safe. `cancel` is the session's shutdown token (a child of the runtime
/// shutdown token), and this function owns every cancellation point — the caller
/// (`runtime/session.rs`) no longer wraps the call in a drop-on-cancel `select!`.
///
/// - The **pre-reply** phase (method negotiation, request header, target parse)
///   is raced against `cancel` and abandoned by drop on shutdown. `negotiate_no_auth`
///   is itself NOT cancel-safe in isolation (read greeting → write method choice),
///   but it runs only inside this `select!`: a cancel drops it before *or* after the
///   method reply with no protocol reply for the command yet on the wire, so the
///   client merely sees the connection close — never a torn command exchange.
/// - The **post-reply** phase is delegated to [`handle_connect`] /
///   [`handle_udp_associate`], each of which threads `cancel` through and keeps its
///   own success-reply→relay window atomic.
pub(crate) async fn handle_client<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    config: SocksSessionConfig,
    telemetry: &T,
    cancel: CancellationToken,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    // Pre-reply negotiation, raced against shutdown. Abandoning it by drop is
    // safe: no SOCKS5 command reply has been written, so the client just sees
    // the connection close — no false success, no torn command state.
    let negotiation = async {
        negotiate_no_auth(&mut client).await?;

        let mut request_header = [0u8; 4];
        client.read_exact(&mut request_header).await?;
        if request_header[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
        }

        let command = request_header[1];
        let target = read_target(&mut client, request_header[3]).await?;
        Ok::<_, io::Error>((command, target))
    };
    let (command, target) = tokio::select! {
        biased;
        () = cancel.cancelled() => return Ok(()),
        result = negotiation => result?,
    };
    telemetry.record_target(target.to_string());

    match command {
        0x01 => handle_connect(client, backend, target, telemetry, cancel).await,
        0x03 => handle_udp_associate(client, backend, config, telemetry, cancel).await,
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
///
/// # Cancel safety
///
/// NOT cancel-safe in isolation: it reads the greeting then writes the server's
/// method choice; a cancel between the read and the write leaves the client
/// without a method reply. The sole caller, [`handle_client`], drives it inside
/// a `select!` whose other arm is shutdown — abandoning it by drop is then safe
/// because no SOCKS5 *command* reply has been written, so the client simply sees
/// the connection close. Do not call it under a cancellation scope that expects
/// a clean handshake after a partial run.
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

/// Reads the SOCKS5 target address (IPv4 / domain / IPv6) following the request
/// header, per RFC 1928 §4.
///
/// # Cancel safety
///
/// Cancel-safe. Every `.await` is a pure `read_exact` into a local buffer; the
/// function writes nothing to the stream, so a cancel at any point only abandons
/// a partial read of bytes that are discarded with the dropped future. No
/// observable protocol state is left behind.
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
