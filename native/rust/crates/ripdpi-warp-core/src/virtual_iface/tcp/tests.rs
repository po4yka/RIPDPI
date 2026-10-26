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
    peer_segment_with_window(syn, control, seq, acked, payload, 32768)
}

fn peer_segment_with_window(
    syn: &[u8],
    control: TcpControl,
    seq: i32,
    acked: usize,
    payload: &[u8],
    window_len: u16,
) -> Bytes {
    let ip = Ipv4Packet::new_checked(syn).expect("IPv4 SYN");
    let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP SYN");
    let reply = TcpRepr {
        src_port: tcp.dst_port(),
        dst_port: tcp.src_port(),
        control,
        seq_number: TcpSeqNumber(seq),
        ack_number: Some(tcp.seq_number() + 1 + acked),
        window_len,
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
                let open = session.as_ref().expect("open session");
                let credit = open.upload_credit().await.expect("upload credit");
                assert!(open.send_local(credit, Bytes::from_static(b"before-eof")));
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

/// A SOCKS client whose virtual peer completes the handshake but never
/// acknowledges uploaded data.
struct StalledUpload {
    client: tokio::net::TcpStream,
    handler: tokio::task::JoinHandle<std::io::Result<()>>,
    worker: tokio::task::JoinHandle<anyhow::Result<()>>,
}

/// # Cancel safety
///
/// NOT cancel-safe: a dropped setup leaves its spawned tasks to the test runtime.
async fn stalled_upload(close_grace: Duration, peer_timeout: Duration) -> StalledUpload {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let bus = Bus::new();
    let mut endpoint = bus.new_endpoint();
    let mut interface = DynamicTcpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
    interface.close_grace = close_grace;
    interface.peer_timeout = peer_timeout;
    let worker = tokio::spawn(interface.run());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("listener");
    let (client, server) =
        tokio::join!(tokio::net::TcpStream::connect(listener.local_addr().expect("address")), listener.accept());
    let mut client = client.expect("client");
    let handler = tokio::spawn(crate::socks::handle_socks_client(
        server.expect("server").0,
        bus.clone(),
        Arc::new(VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool")),
        Arc::new(crate::ports::UdpAssociationPool::new()),
    ));
    let mut reply = [0u8; 10];
    client.write_all(&[0x05, 0x01, 0x00]).await.expect("greeting");
    client.read_exact(&mut reply[..2]).await.expect("method");
    // CONNECT 10.77.0.1:41001
    client.write_all(&[0x05, 0x01, 0x00, 0x01, 10, 77, 0, 1, 0xa0, 0x29]).await.expect("connect");
    let syn = tokio::time::timeout(Duration::from_secs(2), outbound(&mut endpoint)).await.expect("SYN");
    endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, syn_ack(&syn)));
    // The success reply follows the virtual handshake.
    client.read_exact(&mut reply).await.expect("connect reply");
    assert_eq!(reply[..2], [0x05, 0x00]);
    StalledUpload { client, handler, worker }
}

/// Write until the upload stalls for 500 ms or `limit` bytes are accepted.
///
/// # Cancel safety
///
/// NOT cancel-safe: a dropped write may leave a partial chunk in the socket.
async fn upload_until_stalled(client: &mut tokio::net::TcpStream, limit: usize) -> usize {
    use tokio::io::AsyncWriteExt;

    let chunk = vec![0u8; 64 << 10];
    let mut written = 0;
    while written < limit {
        match tokio::time::timeout(Duration::from_millis(500), client.write_all(&chunk)).await {
            Ok(result) => {
                result.expect("upload write");
                written += chunk.len();
            }
            Err(_) => break,
        }
    }
    written
}

/// # Cancel safety
///
/// NOT cancel-safe: the test aborts its handler and interface tasks before returning.
#[tokio::test]
async fn stalled_peer_stops_the_handler_reading_its_client() {
    let mut upload = stalled_upload(Duration::from_secs(30), Duration::from_secs(60)).await;
    let written = upload_until_stalled(&mut upload.client, 48 << 20).await;
    upload.handler.abort();
    upload.worker.abort();
    // Kernel loopback buffers hold a few MiB; the session itself must hold little.
    assert!(written < 16 << 20, "a stalled peer must stop the upload, but {written} bytes were accepted");
}

/// # Cancel safety
///
/// NOT cancel-safe: the test aborts its interface task before returning.
#[tokio::test]
async fn client_reset_ends_a_session_stalled_on_upload_credit() {
    let StalledUpload { mut client, handler, worker } =
        stalled_upload(Duration::from_millis(50), Duration::from_secs(60)).await;
    upload_until_stalled(&mut client, 48 << 20).await;
    // The handler holds no credit now, and the peer never drains the queue. A
    // graceful FIN would wait behind the unread bytes, so the client aborts.
    client.set_zero_linger().expect("abortive close");
    drop(client);
    let ended = tokio::time::timeout(Duration::from_secs(3), handler).await;
    worker.abort();
    ended.expect("a closed client must end its stalled session").expect("handler task joins").expect("clean close");
}

/// # Cancel safety
///
/// NOT cancel-safe: the test aborts its interface task before returning.
#[tokio::test]
async fn peer_that_never_acknowledges_upload_is_timed_out() {
    use tokio::io::AsyncWriteExt;

    let StalledUpload { mut client, handler, worker } =
        stalled_upload(Duration::from_secs(30), Duration::from_millis(300)).await;
    client.write_all(b"never-acknowledged").await.expect("upload");
    let ended = tokio::time::timeout(Duration::from_secs(3), handler).await;
    worker.abort();
    ended.expect("a silent peer must not hold the session forever").expect("handler task joins").expect("clean end");
}

/// # Cancel safety
///
/// NOT cancel-safe: the test aborts its interface task before returning.
#[tokio::test]
async fn acknowledged_upload_beyond_the_session_credit_arrives_in_order() {
    let bus = Bus::new();
    let mut endpoint = bus.new_endpoint();
    let interface = DynamicTcpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
    let worker = tokio::spawn(interface.run());
    let pool = VirtualPortPool::new(PortProtocol::Tcp).expect("TCP pool");
    let forward =
        PortForwardConfig { destination: "10.77.0.1:41001".parse().expect("target"), protocol: PortProtocol::Tcp };
    let session = bus.open_tcp_session(forward, pool.acquire().expect("TCP lease"));
    // Four times the session credit, so the upload completes only if credit returns.
    let expected: Vec<u8> = (0..1usize << 20).map(|index| (index % 251) as u8).collect();
    let peer = async {
        let syn = outbound(&mut endpoint).await;
        let first_byte = TcpPacket::new_checked(Ipv4Packet::new_checked(&syn[..]).expect("IPv4 SYN").payload())
            .expect("TCP SYN")
            .seq_number()
            + 1;
        endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, syn_ack(&syn)));
        let mut received = Vec::new();
        while received.len() < expected.len() {
            let packet = outbound(&mut endpoint).await;
            let ip = Ipv4Packet::new_checked(&packet[..]).expect("IPv4 packet");
            let tcp = TcpPacket::new_checked(ip.payload()).expect("TCP packet");
            if tcp.payload().is_empty() {
                continue;
            }
            if tcp.seq_number() - first_byte == received.len() {
                received.extend_from_slice(tcp.payload());
            }
            // A small window keeps smoltcp's send buffer partly full, so queued
            // chunks enter it in parts.
            let ack = peer_segment_with_window(&syn, TcpControl::None, 12346, received.len(), &[], 4096);
            endpoint.send(Event::InboundInternetPacket(PortProtocol::Tcp, ack));
        }
        received
    };
    let upload = async {
        for chunk in expected.chunks(48 << 10) {
            let credit = session.upload_credit().await.expect("upload credit");
            assert!(session.send_local(credit, Bytes::copy_from_slice(chunk)), "interface accepts the chunk");
        }
    };
    let outcome = tokio::time::timeout(Duration::from_secs(20), async { tokio::join!(peer, upload).0 }).await;
    worker.abort();
    assert!(outcome.expect("upload credit must come back as the peer acknowledges") == expected, "bytes in order");
}
