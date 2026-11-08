use super::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// # Cancel safety:
/// NOT cancel-safe: test authentication consumes a complete control exchange.
async fn authenticate(stream: &mut TcpStream) {
    let mut greeting = [0; 3];
    stream.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting, [5, 1, 2]);
    stream.write_all(&[5, 2]).await.unwrap();
    let mut credentials = [0; 5];
    stream.read_exact(&mut credentials).await.unwrap();
    assert_eq!(credentials, [1, 1, b'u', 1, b'p']);
    stream.write_all(&[1, 0]).await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation can leave the spawned test peer alive until runtime teardown.
#[tokio::test]
async fn authenticated_loopback_negotiates_feedback_and_pins_udp_source() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, peer) = listener.accept().await.unwrap();
        authenticate(&mut stream).await;
        let mut request = [0; 10];
        stream.read_exact(&mut request).await.unwrap();
        assert_eq!(&request[..4], &[5, 0x80, 0, 1]);
        assert_eq!(&request[4..8], &[127, 0, 0, 1]);
        assert_ne!(&request[8..], &[0, 0]);
        let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = relay.local_addr().unwrap().port().to_be_bytes();
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        stream.write_all(b"RUF1abcdefghijklmnop").await.unwrap();
        let mut bytes = [0; 100];
        let (len, sender) = relay.recv_from(&mut bytes).await.unwrap();
        assert_eq!(sender.ip(), peer.ip());
        assert_eq!(sender.port(), u16::from_be_bytes(request[8..10].try_into().unwrap()));
        assert_eq!(&bytes[..4], b"RUF1");
        assert!(len > 32);
        // Keep the control open until the owning client is dropped.
        let _ = stream.read(&mut [0]).await;
    });
    let auth = Auth::UserPass { username: "u".into(), password: "p".into() };
    let session =
        tokio::time::timeout(Duration::from_secs(2), UdpSession::connect(proxy, auth, None)).await.unwrap().unwrap();
    let too_large = session.send_to("127.0.0.1:9".parse().unwrap(), &[0; 65_507]).await.unwrap_err();
    assert_eq!(too_large.raw_os_error(), Some(libc::EMSGSIZE));
    session.send_to("127.0.0.1:9".parse().unwrap(), b"request").await.unwrap();
    drop(session);
    server.await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a complete authenticated exchange.
#[tokio::test]
async fn unsupported_private_command_reauthenticates_on_a_fresh_connection() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut first, _) = listener.accept().await.unwrap();
        authenticate(&mut first).await;
        let mut req = [0; 10];
        first.read_exact(&mut req).await.unwrap();
        assert_eq!(req[1], 0x80);
        first.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
        assert_eq!(first.read(&mut [0]).await.unwrap(), 0, "rejected control must close");
        let (mut second, _) = listener.accept().await.unwrap();
        authenticate(&mut second).await;
        second.read_exact(&mut req).await.unwrap();
        assert_eq!(req[1], 3);
        let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = relay.local_addr().unwrap().port().to_be_bytes();
        second.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        let mut wire = [0; 100];
        let (len, _) = relay.recv_from(&mut wire).await.unwrap();
        assert_eq!(decode_udp_frame(&wire[..len]).unwrap().1, b"standard");
        let _ = second.read(&mut [0]).await;
    });
    let auth = Auth::UserPass { username: "u".into(), password: "p".into() };
    let session = UdpSession::connect(proxy, auth, None).await.unwrap();
    assert!(!session.has_feedback());
    session.send_to("127.0.0.1:9".parse().unwrap(), b"standard").await.unwrap();
    drop(session);
    server.await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: the test completes a malformed control reply and joins its server.
#[tokio::test]
async fn malformed_unsupported_reply_never_downgrades() {
    for reply in [vec![5, 7, 0, 255], vec![5, 7, 0, 1]] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            authenticate(&mut stream).await;
            stream.read_exact(&mut [0; 10]).await.unwrap();
            stream.write_all(&reply).await.unwrap();
            drop(stream);
            assert!(tokio::time::timeout(Duration::from_millis(100), listener.accept()).await.is_err());
        });
        let auth = Auth::UserPass { username: "u".into(), password: "p".into() };
        assert!(UdpSession::connect(proxy, auth, None).await.is_err());
        server.await.unwrap();
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins its authenticated server after the receive checks.
#[tokio::test]
async fn private_receive_drops_forged_frames_and_keeps_errors_separate_from_data() {
    use ripdpi_socks5_core::udp_feedback as wire;
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let target: SocketAddr = "203.0.113.9:443".parse().unwrap();
    let error = wire::RemoteError { source: "192.0.2.1".parse().unwrap(), target, icmp_type: 3, icmp_code: 3, info: 0 };
    let server = tokio::spawn(async move {
        let (mut control, _) = listener.accept().await.unwrap();
        authenticate(&mut control).await;
        control.read_exact(&mut [0; 10]).await.unwrap();
        let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = relay.local_addr().unwrap().port().to_be_bytes();
        control.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        control.write_all(&wire::encode_preface(&[7; 16])).await.unwrap();
        let mut bytes = [0; 2048];
        let (_, peer) = relay.recv_from(&mut bytes).await.unwrap();
        relay.send_to(&wire::encode_error(&[8; 16], 1, &error).unwrap(), peer).await.unwrap();
        relay.send_to(&wire::encode_request(&[7; 16], 2, b"wrong direction").unwrap(), peer).await.unwrap();
        relay.send_to(&wire::encode_error(&[7; 16], 1, &error).unwrap(), peer).await.unwrap();
        let response = encode_udp_frame(target, b"ordinary data");
        relay.send_to(&wire::encode_response(&[7; 16], &response).unwrap(), peer).await.unwrap();
        let _ = control.read(&mut [0]).await;
    });
    let auth = Auth::UserPass { username: "u".into(), password: "p".into() };
    let session = UdpSession::connect(proxy, auth, None).await.unwrap();
    session.send_to(target, b"request").await.unwrap();
    let event = session.recv_event(CancellationToken::new()).await.unwrap().unwrap();
    assert!(matches!(event, UdpSessionEvent::RemoteError { id: 1, error: actual } if actual == error));
    let event = session.recv_event(CancellationToken::new()).await.unwrap().unwrap();
    assert!(
        matches!(event, UdpSessionEvent::Datagram { payload, target: TargetAddr::Ip(actual) } if actual == target && payload == b"ordinary data")
    );
    drop(session);
    server.await.unwrap();
}
