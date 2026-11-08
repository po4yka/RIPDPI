use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio_util::sync::CancellationToken;

use crate::io_loop::udp_assoc::association_state::OutboundDatagram;
use crate::io_loop::udp_assoc::event_handling::UdpEvent;
use crate::io_loop::udp_assoc::worker::spawn_udp_association;
use crate::session::udp::UdpMemoryBudget;
use crate::session::{Auth, TargetAddr};

/// # Cancel safety:
/// cancel-safe: pending binds own their sockets; after spawning the peer there is
/// no await before its cleanup handle is returned to the caller.
async fn scripted_peer(reply_payload: Vec<u8>, reply_count: usize) -> (SocketAddr, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("proxy listener");
    let proxy = listener.local_addr().expect("proxy address");
    let relay = UdpSocket::bind("127.0.0.1:0").await.expect("UDP relay");
    let relay_port = relay.local_addr().expect("relay address").port().to_be_bytes();
    let peer = tokio::spawn(async move {
        let (mut control, _) = listener.accept().await.expect("control connection");
        let mut greeting = [0; 3];
        control.read_exact(&mut greeting).await.expect("greeting");
        assert_eq!(greeting, [5, 1, 0]);
        control.write_all(&[5, 0]).await.expect("accept authentication");
        let mut request = [0; 10];
        control.read_exact(&mut request).await.expect("associate request");
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        control.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, relay_port[0], relay_port[1]]).await.expect("relay endpoint");
        for _ in 0..reply_count {
            let mut request = [0; 64];
            let (len, source) = relay.recv_from(&mut request).await.expect("outbound datagram");
            assert_eq!(&request[..len], b"\0\0\0\x01\xcb\0\x71\x09\x01\xbbprobe");
            let mut reply = vec![0, 0, 0, 1, 203, 0, 113, 9, 1, 187];
            reply.extend_from_slice(&reply_payload);
            relay.send_to(&reply, source).await.expect("large reply");
        }
        std::future::pending::<()>().await;
    });
    (proxy, peer)
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before explicit cleanup can detach peer or worker tasks;
/// the test runtime aborts those tasks at teardown.
#[tokio::test]
async fn association_worker_delivers_the_entire_large_reply_in_mtu_sized_fragments() {
    let expected = vec![0x5a; 2000];
    let (proxy, peer) = scripted_peer(expected.clone(), 1).await;
    let target: SocketAddr = "203.0.113.9:443".parse().expect("target");
    let source = "10.0.0.2:53000".parse().expect("source");
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (tx, mut rx) = tokio::sync::mpsc::channel(4);
    let association = spawn_udp_association(
        proxy,
        Auth::NoAuth,
        source,
        target,
        1,
        Duration::from_secs(10),
        Duration::from_secs(2),
        None,
        budget.clone(),
        CancellationToken::new(),
        tx,
    );
    association
        .outbound
        .send(
            OutboundDatagram::try_new(TargetAddr::Ip(target), target, target, b"probe", &budget).expect("queue bytes"),
        )
        .await
        .expect("queue datagram");
    let result = tokio::time::timeout(Duration::from_secs(2), async {
        let mut packets = Vec::new();
        for _ in 0..2 {
            let UdpEvent::Packet { raw, .. } = rx.recv().await.expect("worker event") else {
                panic!("reply must not close the association");
            };
            let oversize = raw.len() > 1500;
            packets.push(raw);
            if oversize {
                break;
            }
        }
        packets
    })
    .await;
    association.cancel.cancel();
    tokio::time::timeout(Duration::from_secs(2), association.worker)
        .await
        .expect("worker stop deadline")
        .expect("worker task");
    peer.abort();
    assert!(peer.await.expect_err("peer remains open until aborted").is_cancelled());
    let packets = result.expect("both reply fragments must arrive");
    let mut datagram = Vec::new();
    for packet in packets {
        assert!(packet.len() <= 1500, "worker packet exceeds TUN MTU");
        assert_eq!(&packet[12..16], &[203, 0, 113, 9]);
        assert_eq!(&packet[16..20], &[10, 0, 0, 2]);
        let offset = usize::from(u16::from_be_bytes([packet[6], packet[7]]) & 0x1fff) * 8;
        assert_eq!(offset, datagram.len());
        datagram.extend_from_slice(&packet[20..]);
    }
    assert_eq!(u16::from_be_bytes([datagram[4], datagram[5]]), 2008);
    assert_eq!(&datagram[8..], expected.as_slice());
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before explicit cleanup can detach peer or worker tasks;
/// the test runtime aborts those tasks at teardown.
#[tokio::test]
async fn oversized_outer_frame_does_not_retire_an_existing_destination() {
    let (proxy, peer) = scripted_peer(b"ok".to_vec(), 2).await;
    let target: SocketAddr = "203.0.113.9:443".parse().expect("target");
    let oversized_target: SocketAddr = "203.0.113.9:444".parse().expect("oversized destination");
    let source = "10.0.0.2:53000".parse().expect("source");
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (tx, mut rx) = tokio::sync::mpsc::channel(4);
    let association = spawn_udp_association(
        proxy,
        Auth::NoAuth,
        source,
        target,
        1,
        Duration::from_secs(10),
        Duration::from_secs(2),
        None,
        budget.clone(),
        CancellationToken::new(),
        tx,
    );
    association
        .outbound
        .send(
            OutboundDatagram::try_new(TargetAddr::Ip(target), target, target, b"probe", &budget)
                .expect("initial queue bytes"),
        )
        .await
        .expect("queue initial probe");
    let initial = tokio::time::timeout(Duration::from_secs(2), rx.recv())
        .await
        .expect("initial reply deadline")
        .expect("initial event");
    assert!(matches!(initial, UdpEvent::Packet { ref raw, .. } if &raw[28..] == b"ok"));
    // This is a valid maximum IPv4 UDP payload, but SOCKS framing exceeds the outer socket ceiling.
    let oversized = vec![0x5a; 65_507];
    association
        .outbound
        .send(
            OutboundDatagram::try_new(
                TargetAddr::Ip(oversized_target),
                oversized_target,
                oversized_target,
                &oversized,
                &budget,
            )
            .expect("oversized queue bytes"),
        )
        .await
        .expect("queue oversized datagram");
    association
        .outbound
        .send(
            OutboundDatagram::try_new(TargetAddr::Ip(target), target, target, b"probe", &budget)
                .expect("later queue bytes"),
        )
        .await
        .expect("queue later probe");
    let result = tokio::time::timeout(Duration::from_secs(3), rx.recv()).await;
    association.cancel.cancel();
    tokio::time::timeout(Duration::from_secs(2), association.worker)
        .await
        .expect("worker stop deadline")
        .expect("worker task");
    peer.abort();
    assert!(peer.await.expect_err("peer remains open until aborted").is_cancelled());
    let event = result.expect("existing destination reply deadline").expect("worker event");
    assert!(
        matches!(event, UdpEvent::Packet { ref raw, .. } if &raw[28..] == b"ok"),
        "a size error must not close the existing association"
    );
}
