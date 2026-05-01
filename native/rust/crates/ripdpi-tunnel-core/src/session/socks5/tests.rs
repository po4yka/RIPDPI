use std::net::SocketAddr;

use tokio_test::io::Builder;

use super::*;

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
    let mut mock = Builder::new().write(&[0x05, 0x01, 0x00]).read(&[0x05, 0x02]).build();

    let result = handshake(&mut mock, &Auth::NoAuth).await;
    assert!(result.is_err(), "expected error when server selects a method the client did not request");
}

#[tokio::test]
async fn noauth_handshake_rejects_invalid_server_version() {
    let mut mock = Builder::new().write(&[0x05, 0x01, 0x00]).read(&[0x04, 0x00]).build();

    let result = handshake(&mut mock, &Auth::NoAuth).await;
    assert!(result.is_err(), "expected error when server responds with a non-SOCKS5 version");
}

// -------------------------------------------------------------------------
// UserPass
// -------------------------------------------------------------------------

/// Full UserPass flow: greeting -> method selection -> sub-auth.
///
/// Wire format of sub-authentication request:
///   [0x01, len(user), ...user_bytes, len(pass), ...pass_bytes]
/// Wire format of sub-authentication response:
///   [0x01, 0x00]  -> success
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

    let auth = Auth::UserPass { username: "alice".to_string(), password: "s3cr3t".to_string() };
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

    let auth = Auth::UserPass { username: "alice".to_string(), password: "wrong".to_string() };
    let result = handshake(&mut mock, &auth).await;
    assert!(result.is_err(), "expected error when server rejects credentials");
}

#[tokio::test]
async fn userpass_handshake_rejects_server_selecting_noauth() {
    let mut mock = Builder::new().write(&[0x05, 0x01, 0x02]).read(&[0x05, 0x00]).build();

    let auth = Auth::UserPass { username: "alice".to_string(), password: "secret".to_string() };
    let result = handshake(&mut mock, &auth).await;
    assert!(result.is_err(), "expected error when the server does not select username/password auth");
}

#[tokio::test]
async fn userpass_handshake_rejects_oversized_username() {
    let auth = Auth::UserPass { username: "u".repeat(256), password: "secret".to_string() };
    let mut mock = Builder::new().write(&[0x05, 0x01, 0x02]).read(&[0x05, 0x02]).build();

    let result = handshake(&mut mock, &auth).await;
    assert!(result.is_err(), "expected error when the username exceeds the SOCKS5 one-byte length field");
}

// -------------------------------------------------------------------------
// CONNECT request format - RED tests (task-1773069665-4e59)
// -------------------------------------------------------------------------

/// Minimal server CONNECT reply: [VER=5, REP=0, RSV=0, ATYP=1, 0,0,0,0, 0,0]
/// (IPv4 bind addr 0.0.0.0:0 - valid per RFC 1928 section 6)
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

    let mut mock = Builder::new().write(&expected).read(CONNECT_REPLY_IPV4).build();

    let addr = TargetAddr::Ip(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(127, 0, 0, 1), port)));
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

    let mut mock = Builder::new().write(&expected).read(CONNECT_REPLY_IPV4).build();

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

    let mut mock = Builder::new().write(&expected).read(CONNECT_REPLY_IPV4).build();

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

    let addr = TargetAddr::Ip(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 1), port)));
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

    let mut mock = Builder::new().write(&associate_req).read(&associate_reply).build();

    let relay = associate(&mut mock).await.unwrap();
    assert_eq!(relay.ip().to_string(), "127.0.0.1");
    assert_eq!(relay.port(), 1080);
}

/// Non-zero REP byte must return an error.
/// Only 4 header bytes are provided - we error before reading BND.ADDR.
#[tokio::test]
async fn associate_fails_on_server_error() {
    let associate_req = [0x05u8, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
    // REP=1 (general failure): only the 4-byte header, no BND.ADDR/PORT
    let associate_reply = [0x05u8, 0x01, 0x00, 0x01];

    let mut mock = Builder::new().write(&associate_req).read(&associate_reply).build();

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
