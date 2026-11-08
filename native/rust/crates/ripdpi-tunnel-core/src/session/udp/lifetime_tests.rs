use super::{Auth, UdpSession};
use std::io;
use std::net::SocketAddr;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio_util::sync::CancellationToken;

/// # Cancel safety:
/// NOT cancel-safe: dropping setup detaches its handshake task until test runtime teardown.
async fn association() -> (UdpSession, TcpStream, UdpSocket) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let port = relay.local_addr().unwrap().port().to_be_bytes();
    let peer = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let mut greeting = [0; 3];
        stream.read_exact(&mut greeting).await.unwrap();
        assert_eq!(greeting, [5, 1, 0]);
        stream.write_all(&[5, 0]).await.unwrap();
        let mut request = [0; 10];
        stream.read_exact(&mut request).await.unwrap();
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        stream
    });
    let session = UdpSession::connect(proxy, Auth::NoAuth, None).await.unwrap();
    (session, peer.await.unwrap(), relay)
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation during setup can detach its handshake task until test runtime teardown.
#[tokio::test]
async fn loc09_control_fin_stops_udp_send_and_waiting_receive() {
    let _guard = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;
    for reset in [false, true] {
        let (session, control, relay) = association().await;
        let control = control.into_std().unwrap();
        if reset {
            socket2::SockRef::from(&control).set_linger(Some(Duration::ZERO)).unwrap();
        }
        drop(control);
        tokio::time::sleep(Duration::from_millis(20)).await;
        let target: SocketAddr = "127.0.0.1:53".parse().unwrap();
        assert!(session.send_to(target, b"stale").await.is_err());
        assert!(matches!(relay.try_recv_from(&mut [0; 64]), Err(error) if error.kind() == io::ErrorKind::WouldBlock));
        let received = tokio::time::timeout(Duration::from_secs(1), session.recv_from(CancellationToken::new()))
            .await
            .expect("control closure must wake receive");
        assert!(received.is_err());
    }
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation during setup can detach its handshake task until test runtime teardown.
#[tokio::test]
async fn loc09_control_fin_rejects_queued_udp_reply() {
    let _guard = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;
    let (session, control, relay) = association().await;
    session.send_to("127.0.0.1:53".parse().unwrap(), b"request").await.unwrap();
    let mut packet = [0; 64];
    let (len, peer) = relay.recv_from(&mut packet).await.unwrap();
    assert_eq!(&packet[..len], b"\0\0\0\x01\x7f\0\0\x01\0\x35request");
    drop(control);
    tokio::time::sleep(Duration::from_millis(20)).await;
    relay.send_to(b"\0\0\0\x01\x7f\0\0\x01\0\x35stale", peer).await.unwrap();
    assert!(session.recv_from(CancellationToken::new()).await.is_err());
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation during setup can detach its handshake task until test runtime teardown.
#[tokio::test]
async fn loc09_unexpected_control_data_invalidates_all_clones() {
    let _guard = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;
    let (session, mut control, _) = association().await;
    let other = session.clone();
    control.write_all(&[0x7f]).await.unwrap();
    tokio::time::sleep(Duration::from_millis(20)).await;
    let target = "127.0.0.1:53".parse().unwrap();
    assert!(session.send_to(target, b"first").await.is_err());
    assert!(other.send_to(target, b"second").await.is_err());
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation can detach handshake or closer tasks until test runtime teardown.
#[tokio::test]
async fn loc09_control_fin_wakes_a_receiver_without_udp_traffic() {
    let _guard = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;
    let (session, control, _) = association().await;
    let close = tokio::spawn(async move {
        tokio::task::yield_now().await;
        drop(control);
    });
    let result = tokio::time::timeout(Duration::from_secs(1), session.recv_from(CancellationToken::new()))
        .await
        .expect("control close must wake receive");
    assert!(result.is_err());
    close.await.unwrap();
}
