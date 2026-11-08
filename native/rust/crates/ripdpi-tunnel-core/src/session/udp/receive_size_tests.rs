use super::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// # Cancel safety:
/// cancel-safe: pending binds own their sockets; after spawning the peer there is
/// no await before its cleanup handle is returned to the caller.
async fn scripted_proxy(replies: Vec<Vec<u8>>) -> (SocketAddr, CancellationToken, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("proxy listener");
    let proxy = listener.local_addr().expect("proxy address");
    let relay = UdpSocket::bind("127.0.0.1:0").await.expect("UDP relay");
    let port = relay.local_addr().expect("relay address").port().to_be_bytes();
    let cancel = CancellationToken::new();
    let stopped = cancel.clone();
    let peer = tokio::spawn(async move {
        let (mut control, _) = listener.accept().await.expect("accept control");
        let mut greeting = [0; 3];
        control.read_exact(&mut greeting).await.expect("greeting");
        assert_eq!(greeting, [5, 1, 0]);
        control.write_all(&[5, 0]).await.expect("accept no auth");
        let mut request = [0; 10];
        control.read_exact(&mut request).await.expect("associate");
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        control.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.expect("relay endpoint");
        let mut request = [0; 64];
        let (_, source) = relay.recv_from(&mut request).await.expect("client payload");
        for reply in replies {
            relay.send_to(&reply, source).await.expect("scripted reply");
        }
        stopped.cancelled().await;
    });
    (proxy, cancel, peer)
}

fn literal_frame(payload: &[u8]) -> Vec<u8> {
    let mut frame = vec![0, 0, 0, 1, 203, 0, 113, 9, 1, 187];
    frame.extend_from_slice(payload);
    frame
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before explicit cleanup can detach peer or worker tasks;
/// the test runtime aborts those tasks at teardown.
#[tokio::test]
async fn complete_exact_fit_frame_is_delivered() {
    let (proxy, cancel, peer) = scripted_proxy(vec![literal_frame(b"ok")]).await;
    let budget = UdpMemoryBudget::new(12, 1, DEFAULT_QUEUED_UDP_BYTES);
    let session = UdpSession::connect_with_memory_budget(proxy, Auth::NoAuth, None, budget)
        .await
        .expect("session")
        .with_recv_timeout(Duration::from_secs(2));
    session.send_to("203.0.113.9:443".parse().expect("target"), b"request").await.expect("send");
    let result = session.recv_from(CancellationToken::new()).await.expect("receive");
    cancel.cancel();
    peer.await.expect("peer task");
    assert_eq!(result.map(|(payload, _)| payload), Some(b"ok".to_vec()));
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before explicit cleanup can detach peer or worker tasks;
/// the test runtime aborts those tasks at teardown.
#[tokio::test]
async fn production_receive_budget_preserves_a_reply_larger_than_tun_mtu() {
    let expected = vec![0x5a; 2000];
    let (proxy, cancel, peer) = scripted_proxy(vec![literal_frame(&expected)]).await;
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let session = UdpSession::connect_with_memory_budget(proxy, Auth::NoAuth, None, budget)
        .await
        .expect("session")
        .with_recv_timeout(Duration::from_secs(2));
    session.send_to("203.0.113.9:443".parse().expect("target"), b"request").await.expect("send");
    let result = session.recv_from(CancellationToken::new()).await.expect("receive");
    cancel.cancel();
    peer.await.expect("peer task");
    assert_eq!(result.map(|(payload, _)| payload), Some(expected));
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before explicit cleanup can detach peer or worker tasks;
/// the test runtime aborts those tasks at teardown.
#[tokio::test]
async fn oversized_frame_is_isolated_from_exact_fit_and_empty_replies() {
    let replies = vec![literal_frame(b"too large"), literal_frame(b"ok"), literal_frame(b"")];
    let (proxy, cancel, peer) = scripted_proxy(replies).await;
    let budget = UdpMemoryBudget::new(12, 1, DEFAULT_QUEUED_UDP_BYTES);
    let session = UdpSession::connect_with_memory_budget(proxy, Auth::NoAuth, None, budget)
        .await
        .expect("session")
        .with_recv_timeout(Duration::from_secs(2));
    session.send_to("203.0.113.9:443".parse().expect("target"), b"request").await.expect("send");
    let first = session.recv_from(CancellationToken::new()).await.expect("first receive");
    let second = session.recv_from(CancellationToken::new()).await.expect("second receive");
    cancel.cancel();
    peer.await.expect("peer task");
    assert_eq!(first.map(|(payload, _)| payload), Some(b"ok".to_vec()));
    assert_eq!(second.map(|(payload, _)| payload), Some(Vec::new()));
}
