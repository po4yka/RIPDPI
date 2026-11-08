use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use super::{negotiate_socks5, test_deadline};

fn authenticate_loopback(
    bind_ip: IpAddr,
    connect_ip: IpAddr,
    frame: &[u8],
    expected_status: [u8; 2],
) -> io::Result<Option<AttemptCorrelationId>> {
    let domain = if bind_ip.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).expect("create auth listener");
    if bind_ip.is_ipv6() {
        socket.set_only_v6(false).expect("accept mapped IPv4 peers");
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0))).expect("bind auth listener");
    socket.listen(1).expect("listen for auth client");
    let listener: TcpListener = socket.into();
    let port = listener.local_addr().expect("auth listener address").port();
    let mut client = TcpStream::connect(SocketAddr::new(connect_ip, port)).expect("connect loopback auth client");
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("bound auth reply");
    let (mut server, peer) = listener.accept().expect("accept auth client");
    assert!(peer.ip().to_canonical().is_loopback(), "fixture must stay on loopback");
    if bind_ip == IpAddr::V6(Ipv6Addr::UNSPECIFIED) {
        assert!(peer.is_ipv6(), "fixture must present a mapped peer");
    }
    // The dispatch layer already consumed the SOCKS5 version byte.
    client.write_all(&[1, 2]).expect("offer USERPASS");
    client.write_all(frame).expect("write literal RFC 1929 frame");
    let result = negotiate_socks5(&mut server, Some("secret"), test_deadline());
    let mut reply = [0; 4];
    client.read_exact(&mut reply).expect("read method and auth reply");
    assert_eq!(&reply[..2], &[5, 2]);
    assert_eq!(&reply[2..], &expected_status);
    result
}

#[test]
fn authenticated_loopback_representations_preserve_the_attempt_id() {
    let cases = [
        (IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V4(Ipv4Addr::LOCALHOST)),
        (IpAddr::V6(Ipv6Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)),
        (IpAddr::V6(Ipv6Addr::UNSPECIFIED), IpAddr::V4(Ipv4Addr::LOCALHOST)),
    ];
    for (bind_ip, connect_ip) in cases {
        let id = authenticate_loopback(bind_ip, connect_ip, b"\x01\x07attempt\x06secret", [1, 0])
            .expect("authenticate valid token")
            .expect("all loopback representations retain correlation");
        assert_eq!(id.as_opaque_str(), "attempt");
    }
}

#[test]
fn mapped_loopback_still_requires_the_token_and_a_valid_utf8_id() {
    let bind_ip = IpAddr::V6(Ipv6Addr::UNSPECIFIED);
    let connect_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
    let err = authenticate_loopback(bind_ip, connect_ip, b"\x01\x07attempt\x06wrong!", [1, 1])
        .expect_err("mapped loopback does not bypass token authentication");
    assert_eq!(err.kind(), io::ErrorKind::PermissionDenied);
    let id = authenticate_loopback(bind_ip, connect_ip, b"\x01\x01\xff\x06secret", [1, 0])
        .expect("username encoding does not change valid password authentication");
    assert!(id.is_none(), "invalid UTF-8 never becomes an attempt ID");
}
