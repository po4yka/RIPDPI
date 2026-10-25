use std::collections::HashSet;

use smoltcp::phy::ChecksumCapabilities;
use smoltcp::wire::{IpProtocol, Ipv4Packet, Ipv4Repr, TcpControl, TcpPacket, TcpRepr, TcpSeqNumber};

use super::*;
use crate::ports::{PortForwardConfig, VirtualPortPool};
use crate::virtual_iface::bus::BusEndpoint;

/// # Cancel safety
///
/// cancel-safe: broadcast receives consume complete events, never partial packet bytes.
async fn outbound(endpoint: &mut BusEndpoint) -> Bytes {
    loop {
        if let Event::OutboundInternetPacket(packet) = endpoint.recv().await {
            return packet;
        }
    }
}

fn syn_ack(syn: &[u8]) -> Bytes {
    peer_segment(syn, TcpControl::Syn, 12345, 0, &[])
}

/// A segment from the virtual peer that answers the interface's `syn` and
/// acknowledges `acked` sequence numbers the interface sent after its SYN.
fn peer_segment(syn: &[u8], control: TcpControl, seq: i32, acked: usize, payload: &[u8]) -> Bytes {
    let ip = Ipv4Packet::new_checked(syn).expect("IPv4 SYN");
    let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP SYN");
    let reply = TcpRepr {
        src_port: tcp.dst_port(),
        dst_port: tcp.src_port(),
        control,
        seq_number: TcpSeqNumber(seq),
        ack_number: Some(tcp.seq_number() + 1 + acked),
        window_len: 32768,
        window_scale: None,
        max_seg_size: Some(1200),
        sack_permitted: false,
        sack_ranges: [None; 3],
        timestamp: None,
        payload,
    };
    let repr = Ipv4Repr {
        src_addr: ip.dst_addr(),
        dst_addr: ip.src_addr(),
        next_header: IpProtocol::Tcp,
        payload_len: reply.buffer_len(),
        hop_limit: 64,
    };
    let checksums = ChecksumCapabilities::default();
    let mut bytes = vec![0; repr.buffer_len() + reply.buffer_len()];
    repr.emit(&mut Ipv4Packet::new_unchecked(&mut bytes), &checksums);
    reply.emit(
        &mut TcpPacket::new_unchecked(&mut bytes[repr.buffer_len()..]),
        &repr.src_addr.into(),
        &repr.dst_addr.into(),
        &checksums,
    );
    Bytes::from(bytes)
}

enum CloseMode {
    KeepOpen,
    Graceful,
    Deadline,
    PendingData,
}

/// # Cancel safety
///
/// NOT cancel-safe: this helper always shuts down and joins its owned interface before returning.
async fn exercise_interface(mode: CloseMode) -> TcpSeqNumber {
    let bus = Bus::new();
    let mut endpoint = bus.new_endpoint();
    let mut interface = DynamicTcpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
    if matches!(mode, CloseMode::Deadline) {
        interface.close_grace = Duration::from_millis(50);
    }
    let mut worker = Box::pin(interface.run());
    let pool = VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool");
    let lease = pool.acquire().expect("TCP lease");
    let forward =
        PortForwardConfig { destination: "10.77.0.1:41001".parse().expect("target"), protocol: PortProtocol::Tcp };
    let mut session = Some(bus.open_tcp_session(forward, lease.clone()));
    let observed = tokio::select! {
        biased;
        result = &mut worker => panic!("interface stopped early: {result:?}"),
        result = tokio::time::timeout(Duration::from_secs(2), async {
            let syn = outbound(&mut endpoint).await;
            let ip = Ipv4Packet::new_checked(&syn[..]).expect("IPv4 SYN");
            let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP SYN");
            assert!(tcp.syn());
            let sequence = tcp.seq_number();
            if !matches!(mode, CloseMode::KeepOpen) {
                endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, syn_ack(&syn)));
                let _ack = outbound(&mut endpoint).await;
                if matches!(mode, CloseMode::PendingData) {
                    lease.request_close();
                    endpoint.send(Event::VirtualDeviceFed(PortProtocol::Tcp));
                    assert!(tokio::time::timeout(Duration::from_millis(20), outbound(&mut endpoint)).await.is_err(),
                        "shared EOF state must not overtake ordered pending data");
                }
                assert!(session.as_ref().expect("open session").send_local(Bytes::from_static(b"before-eof")));
                let mut payload = Vec::new();
                lease.request_close();
                drop(session.take());
                let mut saw_fin = false;
                loop {
                    let packet = outbound(&mut endpoint).await;
                    let ip = Ipv4Packet::new_checked(&packet[..]).expect("IPv4 close packet");
                    let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP close packet");
                    if tcp.rst() {
                        assert!(matches!(mode, CloseMode::Deadline), "normal EOF must preserve accepted payload");
                        if matches!(mode, CloseMode::Deadline) {
                            assert!(saw_fin, "cleanup reset must follow the graceful FIN attempt");
                        }
                        break;
                    }
                    payload.extend_from_slice(tcp.payload());
                    if tcp.fin() {
                        assert_eq!(payload, b"before-eof");
                        saw_fin = true;
                        if !matches!(mode, CloseMode::Deadline) {
                            break;
                        }
                    }
                }
            }
            sequence
        }) => result,
    };
    bus.shutdown();
    worker.await.expect("interface shutdown");
    observed.expect("outbound TCP deadline")
}

/// # Cancel safety
///
/// NOT cancel-safe: each iteration owns and fully shuts down its interface.
#[tokio::test]
async fn recreated_interfaces_do_not_repeat_initial_tcp_sequence() {
    let mut sequences = HashSet::new();
    for _ in 0..4 {
        sequences.insert(exercise_interface(CloseMode::KeepOpen).await.0);
    }
    assert!(sequences.len() > 1, "recreated stacks must not reuse a fixed TCP sequence seed");
}

/// # Cancel safety
///
/// NOT cancel-safe: the owned interface is shut down even if the packet deadline expires.
#[tokio::test]
async fn local_eof_drains_accepted_payload_and_emits_fin() {
    exercise_interface(CloseMode::Graceful).await;
}

/// # Cancel safety
///
/// NOT cancel-safe: helper always joins its interface after observing the cleanup RST.
#[tokio::test]
async fn unanswered_fin_is_reset_before_its_socket_is_removed() {
    exercise_interface(CloseMode::Deadline).await;
}

/// # Cancel safety
///
/// NOT cancel-safe: helper always joins its interface after the ordered close exchange.
#[tokio::test]
async fn shared_eof_state_does_not_overtake_pending_payload() {
    exercise_interface(CloseMode::PendingData).await;
}

/// # Cancel safety
///
/// NOT cancel-safe: the owned interface is shut down after the payload deadline.
#[tokio::test]
async fn acked_remote_payload_survives_packet_bus_overflow() {
    let bus = Bus::new();
    let mut endpoint = bus.new_endpoint();
    let interface = DynamicTcpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
    let mut worker = Box::pin(interface.run());
    let pool = VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool");
    let lease = pool.acquire().expect("TCP lease");
    let forward =
        PortForwardConfig { destination: "10.77.0.1:41001".parse().expect("target"), protocol: PortProtocol::Tcp };
    let mut session = bus.open_tcp_session(forward, lease);
    let received = tokio::select! {
        biased;
        result = &mut worker => panic!("interface stopped early: {result:?}"),
        result = tokio::time::timeout(Duration::from_secs(2), async {
            let syn = outbound(&mut endpoint).await;
            endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, syn_ack(&syn)));
            let _handshake_ack = outbound(&mut endpoint).await;
            endpoint.send(Event::InboundInternetPacket(
                PortProtocol::Tcp,
                peer_segment(&syn, TcpControl::Psh, 12346, 0, b"remote-bytes"),
            ));
            // Once smoltcp ACKs the payload, the peer will not resend it.
            let ack = outbound(&mut endpoint).await;
            let ip = Ipv4Packet::new_checked(&ack[..]).expect("IPv4 ACK");
            let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP ACK");
            assert_eq!(tcp.ack_number(), TcpSeqNumber(12346 + 12));
            // A slow handler sees the packet bus overflow before it reads.
            for _ in 0..1100 {
                endpoint.send(Event::VirtualDeviceFed(PortProtocol::Udp));
            }
            session.recv_remote().await.expect("remote payload")
        }) => result,
    };
    assert_eq!(&received.expect("payload deadline")[..], b"remote-bytes");
    bus.shutdown();
    worker.await.expect("interface shutdown");
}

/// # Cancel safety
///
/// NOT cancel-safe: the owned interface is shut down after the drain deadline.
#[tokio::test]
async fn peer_close_keeps_payload_buffered_behind_a_full_session_queue() {
    let bus = Bus::new();
    let mut endpoint = bus.new_endpoint();
    let interface = DynamicTcpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
    let mut worker = Box::pin(interface.run());
    let pool = VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool");
    let lease = pool.acquire().expect("TCP lease");
    let forward =
        PortForwardConfig { destination: "10.77.0.1:41001".parse().expect("target"), protocol: PortProtocol::Tcp };
    let mut session = bus.open_tcp_session(forward, lease);
    // More chunks than the session queue holds, so the tail waits in smoltcp.
    let chunks: Vec<Vec<u8>> = (0..12).map(|index| format!("chunk-{index:02};").into_bytes()).collect();
    let expected = chunks.concat();
    let received = tokio::select! {
        biased;
        result = &mut worker => panic!("interface stopped early: {result:?}"),
        result = tokio::time::timeout(Duration::from_secs(5), async {
            let syn = outbound(&mut endpoint).await;
            endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, syn_ack(&syn)));
            let _handshake_ack = outbound(&mut endpoint).await;
            let mut seq = 12346;
            for chunk in &chunks {
                endpoint.send(Event::InboundInternetPacket(
                    PortProtocol::Tcp,
                    peer_segment(&syn, TcpControl::Psh, seq, 0, chunk),
                ));
                seq += i32::try_from(chunk.len()).expect("chunk length");
                let _ack = outbound(&mut endpoint).await;
            }
            endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, peer_segment(&syn, TcpControl::Fin, seq, 0, &[])));
            loop {
                let packet = outbound(&mut endpoint).await;
                let ip = Ipv4Packet::new_checked(&packet[..]).expect("IPv4 packet");
                if TcpPacket::new_checked(ip.payload()).expect("TCP packet").fin() {
                    break;
                }
            }
            // ACK the interface's FIN: the socket reaches Closed with data still buffered.
            endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, peer_segment(&syn, TcpControl::None, seq + 1, 1, &[])));
            tokio::time::sleep(Duration::from_millis(50)).await;
            let mut received = Vec::new();
            while let Some(data) = session.recv_remote().await {
                received.extend_from_slice(&data);
            }
            received
        }) => result,
    };
    assert_eq!(received.expect("drain deadline"), expected, "the peer's ACKed tail must reach the handler");
    bus.shutdown();
    worker.await.expect("interface shutdown");
}

/// # Cancel safety
///
/// NOT cancel-safe: the owned interface is shut down after the SYN deadline.
#[tokio::test]
async fn unaddressable_connect_fails_only_its_own_session() {
    let bus = Bus::new();
    let mut endpoint = bus.new_endpoint();
    let interface = DynamicTcpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
    let mut worker = Box::pin(interface.run());
    let pool = VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool");
    let session_to = |target: &str| {
        let forward = PortForwardConfig { destination: target.parse().expect("target"), protocol: PortProtocol::Tcp };
        bus.open_tcp_session(forward, pool.acquire().expect("TCP lease"))
    };
    // smoltcp refuses a remote port 0 or an unspecified remote address.
    let mut bad_port = session_to("10.77.0.1:0");
    let mut bad_address = session_to("0.0.0.0:80");
    let _good = session_to("10.77.0.1:41001");
    let outcome = tokio::select! {
        biased;
        result = &mut worker => panic!("one bad CONNECT stopped the interface: {result:?}"),
        result = tokio::time::timeout(Duration::from_secs(2), async {
            assert!(bad_port.recv_remote().await.is_none(), "a refused connect must end its own session");
            assert!(bad_address.recv_remote().await.is_none(), "a refused connect must end its own session");
            let syn = outbound(&mut endpoint).await;
            let ip = Ipv4Packet::new_checked(&syn[..]).expect("IPv4 SYN");
            let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP SYN");
            assert!(tcp.syn());
            assert_eq!(tcp.dst_port(), 41001, "the valid session must still connect");
        }) => result,
    };
    outcome.expect("session deadline");
    bus.shutdown();
    worker.await.expect("interface shutdown");
}
