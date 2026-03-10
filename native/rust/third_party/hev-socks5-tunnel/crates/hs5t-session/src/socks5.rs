use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Destination address for a SOCKS5 CONNECT or UDP ASSOCIATE request.
#[derive(Debug, Clone)]
pub enum TargetAddr {
    /// IPv4 or IPv6 socket address.
    Ip(SocketAddr),
    /// Fully-qualified domain name and port.
    Domain(String, u16),
}

/// Send a SOCKS5 CONNECT request and read the server reply.
///
/// The stream MUST have completed a successful [`handshake`] before calling
/// this function.  On success the stream is in the data-forwarding phase.
///
/// Wire formats sent:
/// - IPv4:   `[0x05, 0x01, 0x00, 0x01, a, b, c, d, ph, pl]`
/// - IPv6:   `[0x05, 0x01, 0x00, 0x04, <16 bytes>, ph, pl]`
/// - Domain: `[0x05, 0x01, 0x00, 0x03, len, <domain bytes>, ph, pl]`
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
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "SOCKS5: unknown address type in CONNECT reply",
            ));
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

/// Authentication method for SOCKS5 handshake.
#[derive(Debug, Clone)]
pub enum Auth {
    NoAuth,
    UserPass { username: String, password: String },
}

fn auth_method(auth: &Auth) -> u8 {
    match auth {
        Auth::NoAuth => 0x00,
        Auth::UserPass { .. } => 0x02,
    }
}

fn checked_auth_field_len(field: &str, label: &str) -> io::Result<u8> {
    u8::try_from(field.len()).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("SOCKS5: {label} must be at most 255 bytes"),
        )
    })
}

/// Perform SOCKS5 handshake (method negotiation + optional auth).
///
/// On success the stream is ready for a CONNECT/ASSOCIATE request.
pub async fn handshake<S>(stream: &mut S, auth: &Auth) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let method = auth_method(auth);

    // Send greeting: VER=5, NMETHODS=1, METHOD
    stream.write_all(&[0x05, 0x01, method]).await?;

    // Read server method selection: [VER, METHOD]
    let mut resp = [0u8; 2];
    stream.read_exact(&mut resp).await?;

    if resp[0] != 0x05 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("SOCKS5: invalid handshake version {:#04x}", resp[0]),
        ));
    }

    if resp[1] == 0xFF {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "SOCKS5: no acceptable authentication method",
        ));
    }

    if resp[1] != method {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!(
                "SOCKS5: server selected method {:#04x}, expected {:#04x}",
                resp[1], method
            ),
        ));
    }

    // Sub-authentication for USERNAME_PASSWORD
    if let Auth::UserPass { username, password } = auth {
        let ulen = checked_auth_field_len(username, "username")?;
        let plen = checked_auth_field_len(password, "password")?;

        let mut req = Vec::with_capacity(3 + username.len() + password.len());
        req.push(0x01); // sub-negotiation version
        req.push(ulen);
        req.extend_from_slice(username.as_bytes());
        req.push(plen);
        req.extend_from_slice(password.as_bytes());
        stream.write_all(&req).await?;

        // Read sub-auth response: [VER, STATUS]
        let mut auth_resp = [0u8; 2];
        stream.read_exact(&mut auth_resp).await?;

        if auth_resp[1] != 0x00 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "SOCKS5: authentication rejected",
            ));
        }
    }

    Ok(())
}

/// Send a SOCKS5 UDP ASSOCIATE request and return the relay `SocketAddr`.
///
/// The stream MUST have completed a successful [`handshake`] before calling
/// this function.  Bind address `0.0.0.0:0` signals "any source" per RFC 1928.
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
            ))
        }
    };

    Ok(relay)
}

/// Encode a SOCKS5 UDP request frame (RFC 1928 §7).
///
/// ```text
/// +----+------+------+----------+----------+----------+
/// |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
/// +----+------+------+----------+----------+----------+
/// |  2 |   1  |   1  | variable |    2     | variable |
/// +----+------+------+----------+----------+----------+
/// ```
pub fn encode_udp_frame(dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]); // RSV RSV FRAG=0
    match dst {
        SocketAddr::V4(a) => {
            frame.push(0x01);
            frame.extend_from_slice(&a.ip().octets());
            frame.extend_from_slice(&a.port().to_be_bytes());
        }
        SocketAddr::V6(a) => {
            frame.push(0x04);
            frame.extend_from_slice(&a.ip().octets());
            frame.extend_from_slice(&a.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

/// Decode a SOCKS5 UDP response frame.
///
/// Returns `(from_addr, payload)` on success.
pub fn decode_udp_frame(frame: &[u8]) -> io::Result<(SocketAddr, &[u8])> {
    // Minimum: RSV(2) + FRAG(1) + ATYP(1) + IPv4(4) + PORT(2) = 10
    if frame.len() < 10 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "SOCKS5 UDP frame too short",
        ));
    }
    // frame[0..2] = RSV, frame[2] = FRAG (ignored), frame[3] = ATYP
    let (addr, data_start) = match frame[3] {
        0x01 => {
            let ip = Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7]);
            let port = u16::from_be_bytes([frame[8], frame[9]]);
            (SocketAddr::new(IpAddr::V4(ip), port), 10)
        }
        0x04 => {
            if frame.len() < 22 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "SOCKS5 UDP IPv6 frame too short",
                ));
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let ip = Ipv6Addr::from(raw);
            let port = u16::from_be_bytes([frame[20], frame[21]]);
            (SocketAddr::new(IpAddr::V6(ip), port), 22)
        }
        t => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS5 UDP: unknown ATYP={t}"),
            ))
        }
    };
    Ok((addr, &frame[data_start..]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio_test::io::Builder;

    // -------------------------------------------------------------------------
    // NoAuth
    // -------------------------------------------------------------------------

    /// Client MUST send [0x05, 0x01, 0x00] and accept [0x05, 0x00] from server.
    #[tokio::test]
    async fn noauth_handshake_sends_correct_greeting() {
        let mut mock = Builder::new()
            // Expect handshake() to write the greeting
            .write(&[0x05, 0x01, 0x00])
            // Server selects NO_AUTH (0x00)
            .read(&[0x05, 0x00])
            .build();

        handshake(&mut mock, &Auth::NoAuth).await.unwrap();
    }

    /// Server responding with 0xFF (no acceptable method) must yield an error.
    #[tokio::test]
    async fn noauth_handshake_rejects_no_acceptable_method() {
        let mut mock = Builder::new()
            .write(&[0x05, 0x01, 0x00])
            // Server rejects all methods
            .read(&[0x05, 0xFF])
            .build();

        let result = handshake(&mut mock, &Auth::NoAuth).await;
        assert!(result.is_err(), "expected error when server returns 0xFF");
    }

    #[tokio::test]
    async fn noauth_handshake_rejects_unexpected_method_selection() {
        let mut mock = Builder::new()
            .write(&[0x05, 0x01, 0x00])
            .read(&[0x05, 0x02])
            .build();

        let result = handshake(&mut mock, &Auth::NoAuth).await;
        assert!(
            result.is_err(),
            "expected error when server selects a method the client did not request"
        );
    }

    #[tokio::test]
    async fn noauth_handshake_rejects_invalid_server_version() {
        let mut mock = Builder::new()
            .write(&[0x05, 0x01, 0x00])
            .read(&[0x04, 0x00])
            .build();

        let result = handshake(&mut mock, &Auth::NoAuth).await;
        assert!(
            result.is_err(),
            "expected error when server responds with a non-SOCKS5 version"
        );
    }

    // -------------------------------------------------------------------------
    // UserPass
    // -------------------------------------------------------------------------

    /// Full UserPass flow: greeting → method selection → sub-auth.
    ///
    /// Wire format of sub-authentication request:
    ///   [0x01, len(user), ...user_bytes, len(pass), ...pass_bytes]
    /// Wire format of sub-authentication response:
    ///   [0x01, 0x00]  → success
    #[tokio::test]
    async fn userpass_handshake_sends_correct_bytes() {
        let user = b"alice";
        let pass = b"s3cr3t";

        let greeting_bytes = vec![0x05u8, 0x01, 0x02];
        let mut auth_bytes = vec![0x01u8, user.len() as u8];
        auth_bytes.extend_from_slice(user);
        auth_bytes.push(pass.len() as u8);
        auth_bytes.extend_from_slice(pass);

        let mut mock = Builder::new()
            // Step 1: greeting advertises UserPass (0x02)
            .write(&greeting_bytes)
            // Step 2: server selects UserPass
            .read(&[0x05, 0x02])
            // Step 3: client sends credentials
            .write(&auth_bytes)
            // Step 4: server accepts
            .read(&[0x01, 0x00])
            .build();

        let auth = Auth::UserPass {
            username: "alice".to_string(),
            password: "s3cr3t".to_string(),
        };
        handshake(&mut mock, &auth).await.unwrap();
    }

    /// Server returning a non-zero status byte in sub-auth response is an error.
    #[tokio::test]
    async fn userpass_handshake_fails_on_auth_rejection() {
        let user = b"alice";
        let pass = b"wrong";

        let greeting_bytes = vec![0x05u8, 0x01, 0x02];
        let mut auth_bytes = vec![0x01u8, user.len() as u8];
        auth_bytes.extend_from_slice(user);
        auth_bytes.push(pass.len() as u8);
        auth_bytes.extend_from_slice(pass);

        let mut mock = Builder::new()
            .write(&greeting_bytes)
            .read(&[0x05, 0x02])
            .write(&auth_bytes)
            // Server rejects credentials (non-zero status)
            .read(&[0x01, 0x01])
            .build();

        let auth = Auth::UserPass {
            username: "alice".to_string(),
            password: "wrong".to_string(),
        };
        let result = handshake(&mut mock, &auth).await;
        assert!(
            result.is_err(),
            "expected error when server rejects credentials"
        );
    }

    #[tokio::test]
    async fn userpass_handshake_rejects_server_selecting_noauth() {
        let mut mock = Builder::new()
            .write(&[0x05, 0x01, 0x02])
            .read(&[0x05, 0x00])
            .build();

        let auth = Auth::UserPass {
            username: "alice".to_string(),
            password: "secret".to_string(),
        };
        let result = handshake(&mut mock, &auth).await;
        assert!(
            result.is_err(),
            "expected error when the server does not select username/password auth"
        );
    }

    #[tokio::test]
    async fn userpass_handshake_rejects_oversized_username() {
        let auth = Auth::UserPass {
            username: "u".repeat(256),
            password: "secret".to_string(),
        };
        let mut mock = Builder::new()
            .write(&[0x05, 0x01, 0x02])
            .read(&[0x05, 0x02])
            .build();

        let result = handshake(&mut mock, &auth).await;
        assert!(
            result.is_err(),
            "expected error when the username exceeds the SOCKS5 one-byte length field"
        );
    }

    // -------------------------------------------------------------------------
    // CONNECT request format — RED tests (task-1773069665-4e59)
    // -------------------------------------------------------------------------

    /// Minimal server CONNECT reply: [VER=5, REP=0, RSV=0, ATYP=1, 0,0,0,0, 0,0]
    /// (IPv4 bind addr 0.0.0.0:0 — valid per RFC 1928 §6)
    const CONNECT_REPLY_IPV4: &[u8] = &[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];

    /// CONNECT to 127.0.0.1:8080 via IPv4.
    ///
    /// Expected wire bytes: [0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x1F, 0x90]
    ///   VER=5, CMD=1(CONNECT), RSV=0, ATYP=1(IPv4), addr, port=8080(big-endian)
    #[tokio::test]
    async fn connect_ipv4_sends_correct_bytes() {
        use std::net::{Ipv4Addr, SocketAddrV4};

        let port: u16 = 8080;
        let [ph, pl] = port.to_be_bytes();
        let expected = [0x05u8, 0x01, 0x00, 0x01, 127, 0, 0, 1, ph, pl];

        let mut mock = Builder::new()
            .write(&expected)
            .read(CONNECT_REPLY_IPV4)
            .build();

        let addr = TargetAddr::Ip(SocketAddr::V4(SocketAddrV4::new(
            Ipv4Addr::new(127, 0, 0, 1),
            port,
        )));
        connect(&mut mock, &addr).await.unwrap();
    }

    /// CONNECT to [::1]:443 via IPv6.
    ///
    /// Expected wire bytes: [0x05, 0x01, 0x00, 0x04, <16 bytes of ::1>, 0x01, 0xBB]
    #[tokio::test]
    async fn connect_ipv6_sends_correct_bytes() {
        use std::net::{Ipv6Addr, SocketAddrV6};

        let ip = Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 1); // ::1
        let port: u16 = 443;
        let [ph, pl] = port.to_be_bytes();

        let mut expected = vec![0x05u8, 0x01, 0x00, 0x04];
        expected.extend_from_slice(&ip.octets());
        expected.push(ph);
        expected.push(pl);

        let mut mock = Builder::new()
            .write(&expected)
            .read(CONNECT_REPLY_IPV4)
            .build();

        let addr = TargetAddr::Ip(SocketAddr::V6(SocketAddrV6::new(ip, port, 0, 0)));
        connect(&mut mock, &addr).await.unwrap();
    }

    /// CONNECT to "example.com":80 via domain name (ATYP=3).
    ///
    /// Expected wire bytes:
    ///   [0x05, 0x01, 0x00, 0x03, 11, b'e','x','a','m','p','l','e','.','c','o','m', 0x00, 0x50]
    #[tokio::test]
    async fn connect_domain_sends_correct_bytes() {
        let domain = "example.com";
        let port: u16 = 80;
        let [ph, pl] = port.to_be_bytes();

        let mut expected = vec![0x05u8, 0x01, 0x00, 0x03, domain.len() as u8];
        expected.extend_from_slice(domain.as_bytes());
        expected.push(ph);
        expected.push(pl);

        let mut mock = Builder::new()
            .write(&expected)
            .read(CONNECT_REPLY_IPV4)
            .build();

        let addr = TargetAddr::Domain(domain.to_string(), port);
        connect(&mut mock, &addr).await.unwrap();
    }

    /// Server replying with REP != 0x00 must return an error.
    ///
    /// REP=0x05 means "Connection refused".
    #[tokio::test]
    async fn connect_server_error_returns_err() {
        use std::net::{Ipv4Addr, SocketAddrV4};

        let port: u16 = 22;
        let [ph, pl] = port.to_be_bytes();
        let request = [0x05u8, 0x01, 0x00, 0x01, 10, 0, 0, 1, ph, pl];

        // Server returns REP=5 (connection refused) + minimal bind addr
        let reply = [0x05u8, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0];

        let mut mock = Builder::new().write(&request).read(&reply).build();

        let addr = TargetAddr::Ip(SocketAddr::V4(SocketAddrV4::new(
            Ipv4Addr::new(10, 0, 0, 1),
            port,
        )));
        let result = connect(&mut mock, &addr).await;
        assert!(result.is_err(), "expected error for non-zero REP");
    }

    // -------------------------------------------------------------------------
    // UDP ASSOCIATE
    // -------------------------------------------------------------------------

    /// Happy path: server returns relay addr 127.0.0.1:1080.
    #[tokio::test]
    async fn associate_returns_relay_addr() {
        let associate_req = [0x05u8, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
        // Reply: VER=5, REP=0, RSV=0, ATYP=1, 127.0.0.1, port=1080
        let associate_reply = [0x05u8, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x04, 0x38];

        let mut mock = Builder::new()
            .write(&associate_req)
            .read(&associate_reply)
            .build();

        let relay = associate(&mut mock).await.unwrap();
        assert_eq!(relay.ip().to_string(), "127.0.0.1");
        assert_eq!(relay.port(), 1080);
    }

    /// Non-zero REP byte must return an error.
    /// Only 4 header bytes are provided — we error before reading BND.ADDR.
    #[tokio::test]
    async fn associate_fails_on_server_error() {
        let associate_req = [0x05u8, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
        // REP=1 (general failure): only the 4-byte header, no BND.ADDR/PORT
        let associate_reply = [0x05u8, 0x01, 0x00, 0x01];

        let mut mock = Builder::new()
            .write(&associate_req)
            .read(&associate_reply)
            .build();

        assert!(associate(&mut mock).await.is_err());
    }

    // -------------------------------------------------------------------------
    // UDP framing
    // -------------------------------------------------------------------------

    #[test]
    fn encode_ipv4_frame_has_correct_header() {
        use std::net::{Ipv4Addr, SocketAddrV4};
        let dst = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(1, 2, 3, 4), 5000));
        let payload = b"hello";
        let frame = encode_udp_frame(dst, payload);
        // RSV RSV FRAG ATYP addr[4] port[2] data[5]
        assert_eq!(&frame[..3], &[0x00, 0x00, 0x00]); // RSV+FRAG
        assert_eq!(frame[3], 0x01); // ATYP = IPv4
        assert_eq!(&frame[4..8], &[1, 2, 3, 4]);
        assert_eq!(&frame[8..10], &[0x13, 0x88]); // 5000 big-endian
        assert_eq!(&frame[10..], b"hello");
    }

    #[test]
    fn decode_ipv4_frame_round_trips() {
        use std::net::{Ipv4Addr, SocketAddrV4};
        let dst = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(192, 168, 1, 1), 53));
        let payload = b"dns-query";
        let frame = encode_udp_frame(dst, payload);
        let (from, data) = decode_udp_frame(&frame).unwrap();
        assert_eq!(from, dst);
        assert_eq!(data, b"dns-query");
    }

    #[test]
    fn decode_frame_too_short_is_err() {
        assert!(decode_udp_frame(&[0u8; 5]).is_err());
    }
}
