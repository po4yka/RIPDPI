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
    let ip = Ipv4Packet::new_checked(syn).expect("IPv4 SYN");
    let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP SYN");
    let reply = TcpRepr {
        src_port: tcp.dst_port(),
        dst_port: tcp.src_port(),
        control: TcpControl::Syn,
        seq_number: TcpSeqNumber(12345),
        ack_number: Some(tcp.seq_number() + 1),
        window_len: 32768,
        window_scale: None,
        max_seg_size: Some(1200),
        sack_permitted: false,
        sack_ranges: [None; 3],
        timestamp: None,
        payload: &[],
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
    LostWakeup,
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
    if matches!(mode, CloseMode::Deadline | CloseMode::LostWakeup) {
        interface.close_grace = Duration::from_millis(50);
    }
    let mut worker = Box::pin(interface.run());
    let pool = VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool");
    let lease = pool.acquire().expect("TCP lease");
    let port = lease.port();
    let forward =
        PortForwardConfig { destination: "10.77.0.1:41001".parse().expect("target"), protocol: PortProtocol::Tcp };
    let observed = tokio::select! {
        biased;
        result = &mut worker => panic!("interface stopped early: {result:?}"),
        result = tokio::time::timeout(Duration::from_secs(2), async {
            endpoint.send(Event::ClientConnectionInitiated(forward, lease.clone()));
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
                endpoint.send(Event::LocalData(forward, port, Bytes::from_static(b"before-eof")));
                let mut payload = Vec::new();
                if matches!(mode, CloseMode::LostWakeup) {
                    let sent = outbound(&mut endpoint).await;
                    let ip = Ipv4Packet::new_checked(&sent[..]).expect("accepted IPv4 data");
                    let tcp = TcpPacket::new_checked(ip.payload()).expect("accepted TCP data");
                    payload.extend_from_slice(tcp.payload());
                    assert_eq!(payload, b"before-eof");
                }
                lease.request_close();
                endpoint.send(Event::ClientConnectionDropped(port));
                if matches!(mode, CloseMode::LostWakeup) {
                    for _ in 0..1100 {
                        endpoint.send(Event::RemoteData(port, Bytes::new()));
                    }
                }
                let mut saw_fin = false;
                loop {
                    let packet = outbound(&mut endpoint).await;
                    let ip = Ipv4Packet::new_checked(&packet[..]).expect("IPv4 close packet");
                    let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP close packet");
                    if tcp.rst() {
                        assert!(matches!(mode, CloseMode::Deadline | CloseMode::LostWakeup), "normal EOF must preserve accepted payload");
                        if matches!(mode, CloseMode::Deadline) {
                            assert!(saw_fin, "cleanup reset must follow the graceful FIN attempt");
                        }
                        break;
                    }
                    payload.extend_from_slice(tcp.payload());
                    if tcp.fin() {
                        assert!(!matches!(mode, CloseMode::LostWakeup), "the EOF marker was evicted from the bus");
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
/// NOT cancel-safe: helper always shuts down its owned interface after observing the FIN.
#[tokio::test]
async fn lost_eof_marker_is_reset_by_cleanup_deadline() {
    exercise_interface(CloseMode::LostWakeup).await;
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
