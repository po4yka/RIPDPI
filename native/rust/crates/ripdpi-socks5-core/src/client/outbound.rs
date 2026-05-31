/// SOCKS5 outbound client — performs the full client-side handshake over any
/// `AsyncRead + AsyncWrite` stream and returns the stream ready for data.
///
/// # Protocol flow
///
/// 1. **Greeting** — send `[VER, NMETHODS, METHOD…]`.
/// 2. **Method selection** — read `[VER, METHOD]`; branch on chosen method.
/// 3. **Sub-negotiation** — if `0x02` (username/password), send RFC 1929
///    sub-negotiation frame and read the server reply.
/// 4. **CONNECT request** — send `[VER, CMD=0x01, RSV=0x00, ATYP, ADDR, PORT]`.
/// 5. **Reply** — read `[VER, REP, RSV, ATYP, BND.ADDR, BND.PORT]`; return
///    stream on `REP == 0x00`.
///
/// UDP ASSOCIATE is supported via [`associate`] — single-hop only (one upstream
/// SOCKS5 server). Multi-hop UDP over TCP-tunneled SOCKS hops is not expressible
/// and is intentionally unsupported by design.
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::{consts, ReplyError, SocksError};

/// Optional credentials for username/password authentication.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Credentials {
    pub username: String,
    pub password: String,
}

/// Target address for the CONNECT request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OutboundTarget {
    Ipv4(Ipv4Addr, u16),
    Ipv6(Ipv6Addr, u16),
    Domain(String, u16),
}

/// Errors that can occur during the outbound SOCKS5 handshake.
#[derive(Debug, thiserror::Error)]
pub enum OutboundError {
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),
    #[error("server requires authentication but no credentials were provided")]
    AuthRequired,
    #[error("server rejected credentials")]
    AuthRejected,
    #[error("server chose unacceptable auth method 0x{0:02x}")]
    UnacceptableMethod(u8),
    #[error("server returned unexpected SOCKS version 0x{0:02x}")]
    BadVersion(u8),
    #[error("CONNECT failed: {0}")]
    ConnectFailed(ReplyError),
    #[error("UDP ASSOCIATE failed: {0}")]
    AssociateFailed(ReplyError),
    #[error("server returned unsupported BND address type 0x{0:02x} in reply")]
    UnsupportedBndAddrType(u8),
    #[error("domain name too long ({0} bytes, max 255)")]
    DomainTooLong(usize),
    #[error("SOCKS5 credential length {0} exceeds 255 bytes")]
    CredentialTooLong(usize),
}

impl From<SocksError> for OutboundError {
    fn from(e: SocksError) -> Self {
        match e {
            SocksError::Io(io) => OutboundError::Io(io),
            other => OutboundError::Io(std::io::Error::other(other.to_string())),
        }
    }
}

/// Perform the full SOCKS5 client handshake over `stream` and return the
/// stream ready for payload data.
///
/// - If `credentials` is `None`, only the no-auth method (`0x00`) is
///   advertised.
/// - If `credentials` is `Some`, both username/password (`0x02`) and no-auth
///   (`0x00`) are advertised, in that order of preference.
pub async fn connect<S>(
    stream: &mut S,
    target: OutboundTarget,
    credentials: Option<Credentials>,
) -> Result<(), OutboundError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // ── 1–3. Greeting + method selection + sub-negotiation ────────────────────
    greet_and_authenticate(stream, credentials.as_ref()).await?;

    // ── 4. CONNECT request ────────────────────────────────────────────────────
    let request = build_connect_request(&target)?;
    stream.write_all(&request).await?;

    // ── 5. Reply ──────────────────────────────────────────────────────────────
    read_connect_reply(stream).await
}

/// Perform the SOCKS5 greeting + authentication over `stream`, identical to the
/// first half of [`connect`]. Shared by [`connect`] and [`associate`].
async fn greet_and_authenticate<S>(stream: &mut S, credentials: Option<&Credentials>) -> Result<(), OutboundError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // ── 1. Greeting ──────────────────────────────────────────────────────────
    let methods: &[u8] = if credentials.is_some() {
        &[consts::SOCKS5_AUTH_METHOD_PASSWORD, consts::SOCKS5_AUTH_METHOD_NONE]
    } else {
        &[consts::SOCKS5_AUTH_METHOD_NONE]
    };

    let mut greeting = Vec::with_capacity(2 + methods.len());
    greeting.push(consts::SOCKS5_VERSION);
    greeting.push(methods.len() as u8);
    greeting.extend_from_slice(methods);
    stream.write_all(&greeting).await?;

    // ── 2. Method selection ───────────────────────────────────────────────────
    let mut sel = [0u8; 2];
    stream.read_exact(&mut sel).await?;
    let [ver, chosen] = sel;
    if ver != consts::SOCKS5_VERSION {
        return Err(OutboundError::BadVersion(ver));
    }

    // ── 3. Sub-negotiation ────────────────────────────────────────────────────
    match chosen {
        consts::SOCKS5_AUTH_METHOD_NONE => Ok(()),
        consts::SOCKS5_AUTH_METHOD_PASSWORD => {
            let creds = credentials.ok_or(OutboundError::AuthRequired)?;
            negotiate_password(stream, creds).await
        }
        other => Err(OutboundError::UnacceptableMethod(other)),
    }
}

/// Send a SOCKS5 UDP ASSOCIATE request and return the upstream relay endpoint
/// (`BND.ADDR:BND.PORT`) the caller must forward datagrams to.
///
/// The greeting and authentication are identical to [`connect`]; only the
/// request command differs (`CMD = 0x03`). Per RFC 1928 the client MAY send
/// `0.0.0.0:0` as `DST.ADDR/DST.PORT` when its own UDP source endpoint is not
/// yet known — that is what this function sends.
///
/// **Single-hop only by design.** A UDP ASSOCIATE relay endpoint cannot be
/// tunneled over a further TCP-tunneled SOCKS hop, so this is valid for exactly
/// one upstream SOCKS5 server; multi-hop UDP is not supported.
///
/// The returned `SocketAddr` is the relay the caller binds a (protected) UDP
/// socket to; the TCP `stream` MUST be kept alive for the lifetime of the
/// association.
pub async fn associate<S>(stream: &mut S, credentials: Option<Credentials>) -> Result<SocketAddr, OutboundError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    greet_and_authenticate(stream, credentials.as_ref()).await?;

    // ── UDP ASSOCIATE request: VER CMD RSV ATYP=IPv4 0.0.0.0 :0 ───────────────
    let request = [
        consts::SOCKS5_VERSION,
        consts::SOCKS5_CMD_UDP_ASSOCIATE,
        0x00,
        consts::SOCKS5_ADDR_TYPE_IPV4,
        0,
        0,
        0,
        0,
        0,
        0,
    ];
    stream.write_all(&request).await?;

    read_associate_reply(stream).await
}

/// Read the UDP ASSOCIATE reply and return the relay `BND.ADDR:BND.PORT`.
async fn read_associate_reply<S>(stream: &mut S) -> Result<SocketAddr, OutboundError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let [ver, rep, _rsv, atyp] = header;

    if ver != consts::SOCKS5_VERSION {
        return Err(OutboundError::BadVersion(ver));
    }
    if rep != consts::SOCKS5_REPLY_SUCCEEDED {
        return Err(OutboundError::AssociateFailed(ReplyError::from_u8(rep)));
    }

    match atyp {
        consts::SOCKS5_ADDR_TYPE_IPV4 => {
            let mut buf = [0u8; 4 + 2];
            stream.read_exact(&mut buf).await?;
            let ip = Ipv4Addr::new(buf[0], buf[1], buf[2], buf[3]);
            let port = u16::from_be_bytes([buf[4], buf[5]]);
            Ok(SocketAddr::new(IpAddr::V4(ip), port))
        }
        consts::SOCKS5_ADDR_TYPE_IPV6 => {
            let mut buf = [0u8; 16 + 2];
            stream.read_exact(&mut buf).await?;
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&buf[..16]);
            let port = u16::from_be_bytes([buf[16], buf[17]]);
            Ok(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port))
        }
        other => Err(OutboundError::UnsupportedBndAddrType(other)),
    }
}

async fn negotiate_password<S>(stream: &mut S, creds: &Credentials) -> Result<(), OutboundError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let user = creds.username.as_bytes();
    let pass = creds.password.as_bytes();

    if user.len() > 255 || pass.len() > 255 {
        return Err(OutboundError::CredentialTooLong(user.len().max(pass.len())));
    }

    let mut frame = Vec::with_capacity(3 + user.len() + pass.len());
    frame.push(0x01); // sub-negotiation version
    frame.push(user.len() as u8);
    frame.extend_from_slice(user);
    frame.push(pass.len() as u8);
    frame.extend_from_slice(pass);
    stream.write_all(&frame).await?;

    let mut reply = [0u8; 2];
    stream.read_exact(&mut reply).await?;
    // reply[0] is the sub-negotiation version (we accept any); reply[1] is the status.
    if reply[1] != consts::SOCKS5_REPLY_SUCCEEDED {
        return Err(OutboundError::AuthRejected);
    }
    Ok(())
}

fn build_connect_request(target: &OutboundTarget) -> Result<Vec<u8>, OutboundError> {
    // Header: VER CMD RSV ATYP
    let mut req = vec![consts::SOCKS5_VERSION, consts::SOCKS5_CMD_TCP_CONNECT, 0x00];

    match target {
        OutboundTarget::Ipv4(ip, port) => {
            req.push(consts::SOCKS5_ADDR_TYPE_IPV4);
            req.extend_from_slice(&ip.octets());
            req.extend_from_slice(&port.to_be_bytes());
        }
        OutboundTarget::Ipv6(ip, port) => {
            req.push(consts::SOCKS5_ADDR_TYPE_IPV6);
            req.extend_from_slice(&ip.octets());
            req.extend_from_slice(&port.to_be_bytes());
        }
        OutboundTarget::Domain(name, port) => {
            if name.len() > 255 {
                return Err(OutboundError::DomainTooLong(name.len()));
            }
            req.push(consts::SOCKS5_ADDR_TYPE_DOMAIN_NAME);
            req.push(name.len() as u8);
            req.extend_from_slice(name.as_bytes());
            req.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(req)
}

/// Read the CONNECT reply and drain the BND address/port fields.
async fn read_connect_reply<S>(stream: &mut S) -> Result<(), OutboundError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let [ver, rep, _rsv, atyp] = header;

    if ver != consts::SOCKS5_VERSION {
        return Err(OutboundError::BadVersion(ver));
    }
    if rep != consts::SOCKS5_REPLY_SUCCEEDED {
        return Err(OutboundError::ConnectFailed(ReplyError::from_u8(rep)));
    }

    // Drain BND address + port so the stream is positioned at payload start.
    match atyp {
        consts::SOCKS5_ADDR_TYPE_IPV4 => {
            let mut buf = [0u8; 4 + 2];
            stream.read_exact(&mut buf).await?;
        }
        consts::SOCKS5_ADDR_TYPE_IPV6 => {
            let mut buf = [0u8; 16 + 2];
            stream.read_exact(&mut buf).await?;
        }
        consts::SOCKS5_ADDR_TYPE_DOMAIN_NAME => {
            let mut len_buf = [0u8; 1];
            stream.read_exact(&mut len_buf).await?;
            let name_len = len_buf[0] as usize;
            let mut buf = vec![0u8; name_len + 2];
            stream.read_exact(&mut buf).await?;
        }
        other => {
            return Err(OutboundError::Io(std::io::Error::other(format!(
                "unsupported BND address type 0x{other:02x}"
            ))));
        }
    }

    Ok(())
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, Ipv6Addr};

    use tokio::io::{duplex, AsyncReadExt, AsyncWriteExt};

    use super::*;
    use crate::consts;

    // ── helpers ───────────────────────────────────────────────────────────────

    /// Simulate a SOCKS5 server that accepts `no-auth`, replies SUCCEEDED for
    /// the CONNECT, and binds to 0.0.0.0:0 (IPv4 zeroes).
    async fn server_no_auth_succeed(mut srv: impl AsyncRead + AsyncWrite + Unpin) {
        // Read greeting
        let mut buf = [0u8; 3];
        srv.read_exact(&mut buf).await.unwrap();
        // Reply: choose no-auth
        srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NONE]).await.unwrap();

        // Read CONNECT (variable length; read the fixed 4-byte header then drain)
        let mut hdr = [0u8; 4];
        srv.read_exact(&mut hdr).await.unwrap();
        let atyp = hdr[3];
        drain_request_addr(atyp, &mut srv).await;

        // Reply SUCCEEDED + BND = 0.0.0.0:0
        srv.write_all(&[
            consts::SOCKS5_VERSION,
            consts::SOCKS5_REPLY_SUCCEEDED,
            0x00,
            consts::SOCKS5_ADDR_TYPE_IPV4,
            0,
            0,
            0,
            0,
            0,
            0,
        ])
        .await
        .unwrap();
    }

    /// Simulate a server that demands password auth, accepts any credentials.
    async fn server_password_auth_succeed(mut srv: impl AsyncRead + AsyncWrite + Unpin) {
        // Read greeting (variable; just drain 2 bytes then N method bytes)
        let mut g = [0u8; 2];
        srv.read_exact(&mut g).await.unwrap();
        let nmethods = g[1] as usize;
        let mut mbuf = vec![0u8; nmethods];
        srv.read_exact(&mut mbuf).await.unwrap();

        // Choose password auth
        srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_PASSWORD]).await.unwrap();

        // Read RFC 1929 sub-negotiation frame
        let mut sub = [0u8; 2];
        srv.read_exact(&mut sub).await.unwrap(); // version + ulen
        let ulen = sub[1] as usize;
        let mut ubuf = vec![0u8; ulen];
        srv.read_exact(&mut ubuf).await.unwrap();
        let mut plen_buf = [0u8; 1];
        srv.read_exact(&mut plen_buf).await.unwrap();
        let plen = plen_buf[0] as usize;
        let mut pbuf = vec![0u8; plen];
        srv.read_exact(&mut pbuf).await.unwrap();

        // Accept
        srv.write_all(&[0x01, consts::SOCKS5_REPLY_SUCCEEDED]).await.unwrap();

        // Read CONNECT
        let mut hdr = [0u8; 4];
        srv.read_exact(&mut hdr).await.unwrap();
        drain_request_addr(hdr[3], &mut srv).await;

        // Reply SUCCEEDED
        srv.write_all(&[
            consts::SOCKS5_VERSION,
            consts::SOCKS5_REPLY_SUCCEEDED,
            0x00,
            consts::SOCKS5_ADDR_TYPE_IPV4,
            0,
            0,
            0,
            0,
            0,
            0,
        ])
        .await
        .unwrap();
    }

    /// Simulate a server that demands password auth but rejects credentials.
    async fn server_password_auth_reject(mut srv: impl AsyncRead + AsyncWrite + Unpin) {
        let mut g = [0u8; 2];
        srv.read_exact(&mut g).await.unwrap();
        let nmethods = g[1] as usize;
        let mut mbuf = vec![0u8; nmethods];
        srv.read_exact(&mut mbuf).await.unwrap();

        srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_PASSWORD]).await.unwrap();

        let mut sub = [0u8; 2];
        srv.read_exact(&mut sub).await.unwrap();
        let ulen = sub[1] as usize;
        let mut ubuf = vec![0u8; ulen];
        srv.read_exact(&mut ubuf).await.unwrap();
        let mut plen_buf = [0u8; 1];
        srv.read_exact(&mut plen_buf).await.unwrap();
        let plen = plen_buf[0] as usize;
        let mut pbuf = vec![0u8; plen];
        srv.read_exact(&mut pbuf).await.unwrap();

        // Reject
        srv.write_all(&[0x01, 0x01]).await.unwrap();
        // don't send anything else; client should abort
    }

    async fn server_reply_error(mut srv: impl AsyncRead + AsyncWrite + Unpin, reply_code: u8) {
        let mut buf = [0u8; 3];
        srv.read_exact(&mut buf).await.unwrap();
        srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NONE]).await.unwrap();

        let mut hdr = [0u8; 4];
        srv.read_exact(&mut hdr).await.unwrap();
        drain_request_addr(hdr[3], &mut srv).await;

        srv.write_all(&[consts::SOCKS5_VERSION, reply_code, 0x00, consts::SOCKS5_ADDR_TYPE_IPV4, 0, 0, 0, 0, 0, 0])
            .await
            .unwrap();
    }

    async fn drain_request_addr(atyp: u8, s: &mut (impl AsyncRead + Unpin)) {
        match atyp {
            consts::SOCKS5_ADDR_TYPE_IPV4 => {
                let mut b = [0u8; 6];
                s.read_exact(&mut b).await.unwrap();
            }
            consts::SOCKS5_ADDR_TYPE_IPV6 => {
                let mut b = [0u8; 18];
                s.read_exact(&mut b).await.unwrap();
            }
            consts::SOCKS5_ADDR_TYPE_DOMAIN_NAME => {
                let mut lb = [0u8; 1];
                s.read_exact(&mut lb).await.unwrap();
                let n = lb[0] as usize;
                let mut b = vec![0u8; n + 2];
                s.read_exact(&mut b).await.unwrap();
            }
            _ => panic!("unexpected atyp 0x{atyp:02x}"),
        }
    }

    // ── greeting / method selection ───────────────────────────────────────────

    #[tokio::test]
    async fn greeting_no_auth_method_selected() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_no_auth_succeed(server));
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 80), None).await;
        assert!(result.is_ok(), "expected Ok, got {result:?}");
    }

    #[tokio::test]
    async fn greeting_password_method_selected_and_accepted() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_password_auth_succeed(server));
        let creds = Some(Credentials { username: "user".into(), password: "pass".into() });
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 443), creds).await;
        assert!(result.is_ok(), "expected Ok, got {result:?}");
    }

    #[tokio::test]
    async fn auth_rejected_returns_error() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_password_auth_reject(server));
        let creds = Some(Credentials { username: "bad".into(), password: "wrong".into() });
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 443), creds).await;
        assert!(matches!(result, Err(OutboundError::AuthRejected)), "got {result:?}");
    }

    // ── CONNECT request encoding ──────────────────────────────────────────────

    #[tokio::test]
    async fn connect_request_ipv4_encoded_correctly() {
        let ip = Ipv4Addr::new(1, 2, 3, 4);
        let req = build_connect_request(&OutboundTarget::Ipv4(ip, 8080)).unwrap();
        // VER CMD RSV ATYP [4 octets] [2 port]
        assert_eq!(req[0], consts::SOCKS5_VERSION);
        assert_eq!(req[1], consts::SOCKS5_CMD_TCP_CONNECT);
        assert_eq!(req[2], 0x00);
        assert_eq!(req[3], consts::SOCKS5_ADDR_TYPE_IPV4);
        assert_eq!(&req[4..8], &[1, 2, 3, 4]);
        assert_eq!(&req[8..10], &8080u16.to_be_bytes());
    }

    #[tokio::test]
    async fn connect_request_ipv6_encoded_correctly() {
        let ip = Ipv6Addr::new(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1);
        let req = build_connect_request(&OutboundTarget::Ipv6(ip, 443)).unwrap();
        assert_eq!(req[3], consts::SOCKS5_ADDR_TYPE_IPV6);
        assert_eq!(&req[4..20], &ip.octets());
        assert_eq!(&req[20..22], &443u16.to_be_bytes());
    }

    #[tokio::test]
    async fn connect_request_domain_encoded_correctly() {
        let domain = "example.com".to_string();
        let req = build_connect_request(&OutboundTarget::Domain(domain.clone(), 80)).unwrap();
        assert_eq!(req[3], consts::SOCKS5_ADDR_TYPE_DOMAIN_NAME);
        assert_eq!(req[4] as usize, domain.len());
        assert_eq!(&req[5..5 + domain.len()], domain.as_bytes());
        let port_slice = &req[5 + domain.len()..5 + domain.len() + 2];
        assert_eq!(port_slice, &80u16.to_be_bytes());
    }

    #[tokio::test]
    async fn connect_target_ipv6_roundtrip() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_no_auth_succeed(server));
        let ip = Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 1);
        let result = connect(&mut client, OutboundTarget::Ipv6(ip, 443), None).await;
        assert!(result.is_ok(), "expected Ok, got {result:?}");
    }

    #[tokio::test]
    async fn connect_target_domain_roundtrip() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_no_auth_succeed(server));
        let result = connect(&mut client, OutboundTarget::Domain("example.com".into(), 80), None).await;
        assert!(result.is_ok(), "expected Ok, got {result:?}");
    }

    // ── reply parsing ─────────────────────────────────────────────────────────

    #[tokio::test]
    async fn reply_connection_refused_maps_to_error() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_reply_error(server, consts::SOCKS5_REPLY_CONNECTION_REFUSED));
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 9999), None).await;
        assert!(matches!(result, Err(OutboundError::ConnectFailed(ReplyError::ConnectionRefused))), "got {result:?}");
    }

    #[tokio::test]
    async fn reply_host_unreachable_maps_to_error() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_reply_error(server, consts::SOCKS5_REPLY_HOST_UNREACHABLE));
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 9998), None).await;
        assert!(matches!(result, Err(OutboundError::ConnectFailed(ReplyError::HostUnreachable))), "got {result:?}");
    }

    #[tokio::test]
    async fn reply_general_failure_maps_to_error() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_reply_error(server, consts::SOCKS5_REPLY_GENERAL_FAILURE));
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 9997), None).await;
        assert!(matches!(result, Err(OutboundError::ConnectFailed(ReplyError::GeneralFailure))), "got {result:?}");
    }

    #[tokio::test]
    async fn domain_too_long_returns_error() {
        let long = "a".repeat(256);
        let result = build_connect_request(&OutboundTarget::Domain(long.clone(), 80));
        assert!(matches!(result, Err(OutboundError::DomainTooLong(256))), "got {result:?}");
    }

    // ── UDP ASSOCIATE ─────────────────────────────────────────────────────────

    /// Simulate a SOCKS5 server that accepts `no-auth` and replies to a UDP
    /// ASSOCIATE with the given relay `BND.ADDR:BND.PORT` (IPv4).
    async fn server_associate_no_auth(mut srv: impl AsyncRead + AsyncWrite + Unpin, relay: [u8; 4], port: u16) {
        let mut buf = [0u8; 3];
        srv.read_exact(&mut buf).await.unwrap();
        srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NONE]).await.unwrap();

        // ASSOCIATE request is fixed 10 bytes (CMD=3, ATYP=IPv4, 0.0.0.0:0).
        let mut hdr = [0u8; 4];
        srv.read_exact(&mut hdr).await.unwrap();
        assert_eq!(hdr[1], consts::SOCKS5_CMD_UDP_ASSOCIATE);
        drain_request_addr(hdr[3], &mut srv).await;

        let pb = port.to_be_bytes();
        srv.write_all(&[
            consts::SOCKS5_VERSION,
            consts::SOCKS5_REPLY_SUCCEEDED,
            0x00,
            consts::SOCKS5_ADDR_TYPE_IPV4,
            relay[0],
            relay[1],
            relay[2],
            relay[3],
            pb[0],
            pb[1],
        ])
        .await
        .unwrap();
    }

    #[tokio::test]
    async fn associate_no_auth_returns_relay_endpoint() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_associate_no_auth(server, [127, 0, 0, 1], 5300));
        let relay = associate(&mut client, None).await.expect("associate ok");
        assert_eq!(relay, SocketAddr::from(([127, 0, 0, 1], 5300)));
    }

    #[tokio::test]
    async fn associate_parses_ipv6_relay_endpoint() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(async move {
            let mut srv = server;
            let mut buf = [0u8; 3];
            srv.read_exact(&mut buf).await.unwrap();
            srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NONE]).await.unwrap();
            let mut hdr = [0u8; 4];
            srv.read_exact(&mut hdr).await.unwrap();
            drain_request_addr(hdr[3], &mut srv).await;
            let mut reply =
                vec![consts::SOCKS5_VERSION, consts::SOCKS5_REPLY_SUCCEEDED, 0x00, consts::SOCKS5_ADDR_TYPE_IPV6];
            reply.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
            reply.extend_from_slice(&5301u16.to_be_bytes());
            srv.write_all(&reply).await.unwrap();
        });
        let relay = associate(&mut client, None).await.expect("associate ok");
        assert_eq!(relay, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 5301));
    }

    #[tokio::test]
    async fn associate_password_auth_returns_relay_endpoint() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(async move {
            let mut srv = server;
            let mut g = [0u8; 2];
            srv.read_exact(&mut g).await.unwrap();
            let nmethods = g[1] as usize;
            let mut mbuf = vec![0u8; nmethods];
            srv.read_exact(&mut mbuf).await.unwrap();
            srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_PASSWORD]).await.unwrap();
            // RFC 1929 sub-negotiation
            let mut sub = [0u8; 2];
            srv.read_exact(&mut sub).await.unwrap();
            let ulen = sub[1] as usize;
            let mut ubuf = vec![0u8; ulen];
            srv.read_exact(&mut ubuf).await.unwrap();
            let mut plen = [0u8; 1];
            srv.read_exact(&mut plen).await.unwrap();
            let mut pbuf = vec![0u8; plen[0] as usize];
            srv.read_exact(&mut pbuf).await.unwrap();
            srv.write_all(&[0x01, consts::SOCKS5_REPLY_SUCCEEDED]).await.unwrap();
            // ASSOCIATE
            let mut hdr = [0u8; 4];
            srv.read_exact(&mut hdr).await.unwrap();
            drain_request_addr(hdr[3], &mut srv).await;
            srv.write_all(&[
                consts::SOCKS5_VERSION,
                consts::SOCKS5_REPLY_SUCCEEDED,
                0x00,
                consts::SOCKS5_ADDR_TYPE_IPV4,
                10,
                0,
                0,
                7,
                0x14,
                0xb0,
            ])
            .await
            .unwrap();
        });
        let creds = Some(Credentials { username: "user".into(), password: "pass".into() });
        let relay = associate(&mut client, creds).await.expect("associate ok");
        assert_eq!(relay, SocketAddr::from(([10, 0, 0, 7], 5296)));
    }

    #[tokio::test]
    async fn associate_command_not_supported_maps_to_error() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(async move {
            let mut srv = server;
            let mut buf = [0u8; 3];
            srv.read_exact(&mut buf).await.unwrap();
            srv.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NONE]).await.unwrap();
            let mut hdr = [0u8; 4];
            srv.read_exact(&mut hdr).await.unwrap();
            drain_request_addr(hdr[3], &mut srv).await;
            srv.write_all(&[
                consts::SOCKS5_VERSION,
                consts::SOCKS5_REPLY_COMMAND_NOT_SUPPORTED,
                0x00,
                consts::SOCKS5_ADDR_TYPE_IPV4,
                0,
                0,
                0,
                0,
                0,
                0,
            ])
            .await
            .unwrap();
        });
        let result = associate(&mut client, None).await;
        assert!(
            matches!(result, Err(OutboundError::AssociateFailed(ReplyError::CommandNotSupported))),
            "got {result:?}"
        );
    }

    #[tokio::test]
    async fn credential_too_long_returns_error() {
        let (mut client, server) = duplex(4096);
        tokio::spawn(server_password_auth_succeed(server));
        let long_username = "u".repeat(256);
        let creds = Some(Credentials { username: long_username, password: "pass".into() });
        let result = connect(&mut client, OutboundTarget::Ipv4(Ipv4Addr::LOCALHOST, 443), creds).await;
        assert!(matches!(result, Err(OutboundError::CredentialTooLong(256))), "got {result:?}");
    }
}
