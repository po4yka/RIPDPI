// SPDX-License-Identifier: MIT
//
//! Loopback round-trip for the WireGuard-over-WebSocket carrier.
//!
//! Spins a WebSocket echo relay on `127.0.0.1` (protect-exempt loopback),
//! connects a client carrier through it, and pushes a synthetic WireGuard
//! handshake datagram both directions, asserting byte-identity. No real
//! internet, no TLS, no protected socket — the carrier is transport-agnostic
//! and the test exercises it over a plain TCP `WebSocketStream`.

use ripdpi_wireguard_ws::{JunkPrefix, WsCarrier};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{accept_async, client_async};

/// A 148-byte synthetic WireGuard handshake initiation: type byte `0x01`
/// followed by a deterministic ramp so any byte-level corruption is visible.
fn synthetic_wg_initiation() -> Vec<u8> {
    let mut datagram = vec![0u8; 148];
    datagram[0] = 0x01;
    for (i, b) in datagram.iter_mut().enumerate().skip(1) {
        *b = (i % 251) as u8;
    }
    datagram
}

#[tokio::test]
async fn wg_datagram_round_trips_through_ws_carrier() {
    // 1. Bind a loopback WS echo relay. 127.0.0.1 is protect-exempt.
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind loopback listener");
    let addr = listener.local_addr().expect("local addr");

    // The relay accepts one WS connection and echoes every binary frame back.
    let relay = tokio::spawn(async move {
        let (tcp, _peer) = listener.accept().await.expect("accept tcp");
        let ws = accept_async(tcp).await.expect("ws server handshake");
        let mut server = WsCarrier::new(ws);
        // Echo datagrams until the client closes the stream.
        while let Ok(datagram) = server.recv_datagram().await {
            server.send_datagram(&datagram).await.expect("echo datagram");
        }
    });

    // 2. Connect a client carrier through the relay.
    let tcp = TcpStream::connect(addr).await.expect("connect loopback tcp");
    let url = format!("ws://{addr}/wg");
    let (ws, _resp) = client_async(url, tcp).await.expect("ws client handshake");
    let mut client = WsCarrier::new(ws);

    // 3. AmneziaWG junk prefix ahead of the real handshake (forward only;
    //    the relay echoes them, which we drain below to keep the stream in
    //    sync with the real datagram).
    let junk = JunkPrefix::new(3, 16, 48).expect("valid junk envelope");
    client.send_junk_prefix(&junk).await.expect("send junk prefix");

    // 4. Push the real WG datagram through and read the echo back.
    let datagram = synthetic_wg_initiation();
    client.send_datagram(&datagram).await.expect("send wg datagram");

    // Drain the echoed junk frames (Jc of them) before the real echo.
    for _ in 0..junk.count() {
        let echoed_junk = client.recv_datagram().await.expect("recv echoed junk");
        assert!((16..=48).contains(&echoed_junk.len()), "echoed junk length {} outside envelope", echoed_junk.len());
    }

    let echoed = client.recv_datagram().await.expect("recv echoed wg datagram");

    // 5. The round trip must be byte-identical.
    assert_eq!(echoed, datagram, "WG datagram must survive the WS round trip byte-for-byte");
    assert_eq!(echoed[0], 0x01, "type byte preserved");

    // 6. Close the client; the relay loop ends when recv returns Closed.
    {
        use futures::SinkExt;
        let mut ws = client.into_inner();
        ws.send(Message::Close(None)).await.expect("send close");
    }
    relay.await.expect("relay task joins");
}

/// # Cancel safety:
/// cancel-safe: the test owns and joins the receive task and owns both stream ends.
#[tokio::test]
async fn split_carrier_replies_to_peer_close_before_returning_closed() {
    assert_split_peer_close_reply().await;
}

/// # Cancel safety:
/// cancel-safe: the helper owns the stream ends and joins the receive task.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn split_carrier_replies_to_peer_close_on_multithread_runtime() {
    assert_split_peer_close_reply().await;
}

/// # Cancel safety:
/// cancel-safe: the receive task and stream ends belong to this helper.
async fn assert_split_peer_close_reply() {
    use ripdpi_wireguard_ws::WsCarrierError;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::{WebSocketStream, tungstenite::protocol::Role};
    let (local, mut peer) = tokio::io::duplex(64);
    let ws = WebSocketStream::from_raw_socket(local, Role::Client, None).await;
    let (_sender, mut receiver) = WsCarrier::new(ws).split();
    let receiving = tokio::spawn(async move { receiver.recv_datagram().await });
    peer.write_all(&[0x88, 2, 0x03, 0xe8]).await.expect("peer Close 1000");
    let mut reply = [0; 8];
    tokio::time::timeout(std::time::Duration::from_millis(250), peer.read_exact(&mut reply))
        .await
        .expect("Close reply")
        .expect("Close bytes");
    assert_eq!(&reply[..2], &[0x88, 0x82]);
    assert_eq!([reply[6] ^ reply[2], reply[7] ^ reply[3]], [0x03, 0xe8]);
    peer.shutdown().await.expect("server closes after handshake");
    assert!(matches!(receiving.await.expect("receive task"), Err(WsCarrierError::Closed)));
}

/// # Cancel safety:
/// cancel-safe: the test owns both stream ends and joins the writer task.
#[tokio::test(start_paused = true)]
async fn cancelled_split_close_read_keeps_the_original_deadline() {
    use ripdpi_wireguard_ws::WsCarrierError;
    use std::time::Duration;
    use tokio::io::AsyncWriteExt;
    use tokio_tungstenite::{WebSocketStream, tungstenite::protocol::Role};
    let (local, mut peer) = tokio::io::duplex(1);
    let ws = WebSocketStream::from_raw_socket(local, Role::Client, None).await;
    let (_sender, mut receiver) = WsCarrier::new(ws).split();
    let writer = tokio::spawn(async move {
        peer.write_all(&[0x88, 2, 0x03, 0xe8]).await.expect("peer Close");
        peer
    });
    let started = tokio::time::Instant::now();
    assert!(tokio::time::timeout(Duration::from_secs(4), receiver.recv_datagram()).await.is_err());
    let _peer = writer.await.expect("Close writer");
    let error = receiver.recv_datagram().await.expect_err("blocked close timeout");
    assert!(matches!(error, WsCarrierError::Transport(tokio_tungstenite::tungstenite::Error::Io(ref e))
        if e.kind() == std::io::ErrorKind::TimedOut));
    assert_eq!(started.elapsed(), Duration::from_secs(5));
    assert!(matches!(receiver.recv_datagram().await, Err(WsCarrierError::Closed)));
}

/// # Cancel safety:
/// cancel-safe: the writer task and both stream ends are owned by the test.
#[tokio::test]
async fn split_peer_close_with_write_eof_still_flushes_the_reply() {
    use ripdpi_wireguard_ws::WsCarrierError;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::{WebSocketStream, tungstenite::protocol::Role};
    let (local, mut peer) = tokio::io::duplex(1);
    let ws = WebSocketStream::from_raw_socket(local, Role::Client, None).await;
    let (_sender, mut receiver) = WsCarrier::new(ws).split();
    let writer = tokio::spawn(async move {
        peer.write_all(&[0x88, 2, 0x03, 0xe8]).await.expect("peer Close");
        peer.shutdown().await.expect("peer write EOF");
        peer
    });
    let mut receiving = Box::pin(receiver.recv_datagram());
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(25), &mut receiving).await.is_err(),
        "Close must remain pending while its reply is blocked"
    );
    let mut peer = writer.await.expect("Close writer");
    let drain = async {
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).await.expect("masked Close reply");
        assert_eq!(&reply[..2], &[0x88, 0x82]);
        assert_eq!([reply[6] ^ reply[2], reply[7] ^ reply[3]], [0x03, 0xe8]);
    };
    let (result, ()) = tokio::join!(receiving, drain);
    assert!(matches!(result, Err(WsCarrierError::Closed)));
}

/// # Cancel safety:
/// cancel-safe: the writer task and both stream ends are owned by the test.
#[tokio::test]
async fn unsplit_peer_close_with_write_eof_still_flushes_the_reply() {
    use ripdpi_wireguard_ws::WsCarrierError;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::{WebSocketStream, tungstenite::protocol::Role};
    let (local, mut peer) = tokio::io::duplex(1);
    let ws = WebSocketStream::from_raw_socket(local, Role::Client, None).await;
    let mut receiver = WsCarrier::new(ws);
    let writer = tokio::spawn(async move {
        peer.write_all(&[0x88, 2, 0x03, 0xe8]).await.expect("peer Close");
        peer.shutdown().await.expect("peer write EOF");
        peer
    });
    let mut receiving = Box::pin(receiver.recv_datagram());
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(25), &mut receiving).await.is_err(),
        "Close must remain pending while its reply is blocked"
    );
    let mut peer = writer.await.expect("Close writer");
    let drain = async {
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).await.expect("masked Close reply");
        assert_eq!(&reply[..2], &[0x88, 0x82]);
        assert_eq!([reply[6] ^ reply[2], reply[7] ^ reply[3]], [0x03, 0xe8]);
    };
    let (result, ()) = tokio::join!(receiving, drain);
    assert!(matches!(result, Err(WsCarrierError::Closed)));
}

struct GatedReadStream {
    inner: tokio::io::DuplexStream,
    entered: Option<tokio::sync::oneshot::Sender<()>>,
    release: std::sync::mpsc::Receiver<()>,
}

impl tokio::io::AsyncRead for GatedReadStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if let Some(entered) = self.entered.take() {
            let _ = entered.send(());
            // Test-only gate: hold the real split Stream's BiLock while another
            // runtime thread queues and cancels a send. The wait is bounded.
            self.release.recv_timeout(std::time::Duration::from_secs(3)).expect("release read gate");
        }
        std::pin::Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}

impl tokio::io::AsyncWrite for GatedReadStream {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        data: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        std::pin::Pin::new(&mut self.inner).poll_write(cx, data)
    }

    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

/// # Cancel safety:
/// cancel-safe: stream ends and the bounded read gate are test-owned, and the
/// reader task is joined. The cancelled send is deliberately never retried.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn cancelled_send_slot_does_not_drop_the_peer_close_reply() {
    use ripdpi_wireguard_ws::WsCarrierError;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::{WebSocketStream, tungstenite::protocol::Role};
    let (local, mut peer) = tokio::io::duplex(64);
    let (entered_tx, entered_rx) = tokio::sync::oneshot::channel();
    let (release_tx, release_rx) = std::sync::mpsc::channel();
    let stream = GatedReadStream { inner: local, entered: Some(entered_tx), release: release_rx };
    let ws = WebSocketStream::from_raw_socket(stream, Role::Client, None).await;
    let (sender, mut receiver) = WsCarrier::new(ws).split();
    peer.write_all(&[0x88, 2, 0x03, 0xe8]).await.expect("literal peer Close");
    let receiving = tokio::spawn(async move { receiver.recv_datagram().await });
    entered_rx.await.expect("read holds the split lock");
    let mut sending = Box::pin(sender.send_datagram(b"cancelled datagram"));
    assert!(futures::poll!(&mut sending).is_pending());
    drop(sending);
    release_tx.send(()).expect("release real stream read");
    let result = receiving.await.expect("reader task");
    assert!(matches!(result, Err(WsCarrierError::Closed)), "{result:?}");
    let mut reply = [0; 8];
    peer.read_exact(&mut reply).await.expect("masked Close reply");
    assert_eq!(&reply[..2], &[0x88, 0x82]);
    assert_eq!([reply[6] ^ reply[2], reply[7] ^ reply[3]], [0x03, 0xe8]);
    drop(sender);
    assert_eq!(peer.read(&mut [0]).await.expect("no cancelled datagram"), 0);
}
