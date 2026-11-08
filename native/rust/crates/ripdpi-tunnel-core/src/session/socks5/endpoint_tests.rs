use super::associate;
use tokio_test::io::Builder;

/// # Cancel safety:
/// cancel-safe: the helper owns and discards its stream.
async fn domain_relay(host: &[u8]) -> std::io::Result<std::net::SocketAddr> {
    let mut reply = vec![5, 0, 0, 3, host.len() as u8];
    reply.extend_from_slice(host);
    reply.extend_from_slice(&[0x14, 0xb0]);
    let mut stream = Builder::new().write(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).read(&reply).build();
    associate(&mut stream, std::net::Ipv4Addr::LOCALHOST.into()).await
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards each stream.
#[tokio::test]
async fn loc09_associate_accepts_local_domain_relay() {
    for host in [b"localhost".as_slice(), b"LOCALHOST.", b"127.0.0.1"] {
        assert_eq!(domain_relay(host).await.unwrap(), "127.0.0.1:5296".parse().unwrap());
    }
    assert_eq!(domain_relay(b"::1").await.unwrap(), "[::1]:5296".parse().unwrap());
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards its stream.
#[tokio::test]
async fn loc09_associate_rejects_zero_relay_port() {
    let mut stream =
        Builder::new().write(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).read(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).build();
    assert!(associate(&mut stream, std::net::Ipv4Addr::LOCALHOST.into()).await.is_err());
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards its stream.
#[tokio::test]
async fn loc09_associate_normalizes_unspecified_relay_address() {
    let mut stream =
        Builder::new().write(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).read(&[5, 0, 0, 1, 0, 0, 0, 0, 0x14, 0xb0]).build();
    assert_eq!(
        associate(&mut stream, std::net::Ipv4Addr::LOCALHOST.into()).await.unwrap(),
        "127.0.0.1:5296".parse().unwrap()
    );
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards each stream.
#[tokio::test]
async fn loc09_associate_rejects_unconfigured_relay_names() {
    for reply in [b"\x05\0\0\x03\x0drelay.example\x14\xb0".as_slice(), b"\x05\0\0\x03\0", b"\x05\0\0\x03\x01\xff"] {
        let mut stream = Builder::new().write(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).read(reply).build();
        assert!(associate(&mut stream, std::net::Ipv4Addr::LOCALHOST.into()).await.is_err());
    }
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards its stream.
#[tokio::test]
async fn loc09_localhost_relay_retains_control_peer_family() {
    let mut stream =
        Builder::new().write(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).read(b"\x05\0\0\x03\x09localhost\x14\xb0").build();
    let peer = "2001:db8::7".parse().unwrap();
    assert_eq!(associate(&mut stream, peer).await.unwrap(), "[2001:db8::7]:5296".parse().unwrap());
}
