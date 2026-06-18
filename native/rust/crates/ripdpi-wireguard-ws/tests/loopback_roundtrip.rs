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
