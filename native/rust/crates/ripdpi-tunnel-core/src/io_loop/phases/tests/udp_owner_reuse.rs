use ripdpi_flow_app_attribution::{
    FlowRegistrationId, FlowResolveRequest, evict_flow_if_current, note_flow, store_uid_and_scope_resolution,
    take_pending_request,
};

use super::super::super::udp_assoc::UdpEvent;
use super::*;

fn request(port: u16, remote_port: u16) -> FlowResolveRequest {
    FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_UDP,
        local: SocketAddr::from(([10, 0, 0, 2], port)),
        remote: SocketAddr::from(([93, 184, 216, 34], remote_port)),
    }
}

fn admit(state: &mut LoopState, request: FlowResolveRequest) -> FlowRegistrationId {
    route_tun_packet(&ipv4_udp_packet(request.local.port(), request.remote.port(), b"outbound"), state);
    let job = take_pending_request(request).expect("owner lookup");
    store_uid_and_scope_resolution(&job, Some(10_123), Some("81c5afbf-9a33-4680-880a-06c47840e52b"));
    retry_pending_uid_packets(state);
    note_flow(request.protocol, request.local, request.remote).registration_id
}

/// # Cancel safety:
/// NOT cancel-safe: the test must join its owned UDP workers through shutdown.
#[tokio::test(flavor = "current_thread")]
async fn observed_reused_tuple_rejects_old_replies_while_pending_or_denied() {
    for (port, denied) in [(55_661, false), (55_662, true)] {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("proxy listener");
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.runtime.proxy_sockaddr = listener.local_addr().expect("proxy address");
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let original = admit(&mut state, request(port, 443));
        let neighbor = admit(&mut state, request(port, 444));
        let (old_id, old_destination) = state.udp_associations.test_destination_snapshot(&original).expect("old flow");
        let (neighbor_id, neighbor_destination) =
            state.udp_associations.test_destination_snapshot(&neighbor).expect("neighbor flow");
        assert!(evict_flow_if_current(original));
        route_tun_packet(&ipv4_udp_packet(port, 443, b"new owner"), &mut state);
        if denied {
            let job = take_pending_request(original.request()).expect("successor lookup");
            store_uid_and_scope_resolution(&job, Some(20_000), None);
            retry_pending_uid_packets(&mut state);
        }
        let mut response = Vec::new();
        etherparse::PacketBuilder::ipv4([93, 184, 216, 34], [10, 0, 0, 2], 64)
            .udp(443, port)
            .write(&mut response, b"old owner response")
            .expect("response");
        handle_wait_event(
            &mut state,
            WaitEvent::Udp(Some(UdpEvent::Packet {
                src: original.request().local,
                association_id: old_id,
                destination: Arc::clone(&old_destination),
                raw: response,
            })),
        );
        let leaked = state.device.tx_queue.pop_front();
        handle_wait_event(
            &mut state,
            WaitEvent::Udp(Some(UdpEvent::RemoteError {
                src: original.request().local,
                association_id: old_id,
                registration_id: original,
                destination: old_destination,
                raw: b"stale owner error".to_vec(),
            })),
        );
        let leaked_error = state.device.tx_queue.pop_front();
        handle_wait_event(
            &mut state,
            WaitEvent::Udp(Some(UdpEvent::Packet {
                src: neighbor.request().local,
                association_id: neighbor_id,
                destination: neighbor_destination,
                raw: b"neighbor response".to_vec(),
            })),
        );
        let neighbor_output = state.device.tx_queue.pop_front();
        state.shutdown().await;
        assert!(leaked.is_none(), "old owner response must stop when successor is observed (denied={denied})");
        assert!(leaked_error.is_none(), "old owner error must not reach successor");
        assert_eq!(neighbor_output.as_deref(), Some(b"neighbor response".as_slice()));
    }
}

/// # Cancel safety:
/// cancel-safe: this future owns the stream; interruption closes the partial exchange.
async fn refresh_proxy_connection(mut stream: tokio::net::TcpStream, relay_port: u16) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut greeting = [0; 2];
    stream.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting[0], 5);
    let mut methods = vec![0; usize::from(greeting[1])];
    stream.read_exact(&mut methods).await.unwrap();
    assert!(methods.contains(&2));
    stream.write_all(&[5, 2]).await.unwrap();
    let mut auth = [0; 2];
    stream.read_exact(&mut auth).await.unwrap();
    assert_eq!(auth[0], 1);
    let mut username = vec![0; usize::from(auth[1])];
    stream.read_exact(&mut username).await.unwrap();
    assert_eq!(username, b"vpn08:81c5afbf-9a33-4680-880a-06c47840e52b");
    let mut size = [0; 1];
    stream.read_exact(&mut size).await.unwrap();
    let mut password = vec![0; usize::from(size[0])];
    stream.read_exact(&mut password).await.unwrap();
    assert_eq!(password, b"refresh-test-token");
    stream.write_all(&[1, 0]).await.unwrap();
    let mut request = [0; 4];
    stream.read_exact(&mut request).await.unwrap();
    assert_eq!((request[0], request[2]), (5, 0));
    let size = match request[3] {
        1 => 6,
        4 => 18,
        other => panic!("unexpected endpoint type {other}"),
    };
    stream.read_exact(&mut vec![0; size]).await.unwrap();
    if request[1] != 3 {
        stream.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
        return;
    }
    let port = relay_port.to_be_bytes();
    stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
    let mut byte = [0; 1];
    assert_eq!(stream.read(&mut byte).await.unwrap(), 0, "control connection carries no UDP data");
}

/// # Cancel safety:
/// cancel-safe: all connection tasks are owned by the abort-on-drop JoinSet.
async fn refresh_proxy(listener: TcpListener, relay_port: u16, accepts: Arc<AtomicUsize>, cancel: CancellationToken) {
    let mut connections = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            _ = cancel.cancelled() => break,
            accepted = listener.accept() => {
                let (stream, _) = accepted.unwrap();
                accepts.fetch_add(1, Ordering::Relaxed);
                connections.spawn(refresh_proxy_connection(stream, relay_port));
            }
            Some(result) = connections.join_next(), if !connections.is_empty() => {
                result.expect("valid loopback peer exchange");
            }
        }
    }
    connections.abort_all();
    while let Some(result) = connections.join_next().await {
        if let Err(error) = result {
            assert!(error.is_cancelled(), "loopback peer task must not panic: {error}");
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the owning loop must reach shutdown to join its UDP workers.
#[tokio::test(flavor = "current_thread")]
async fn udp_uid_expiry_preserves_real_source_port_and_control_connection_for_same_owner() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let relay = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.proxy_sockaddr = listener.local_addr().unwrap();
    state.runtime.auth = Auth::UserPass { username: "ripdpi".into(), password: "refresh-test-token".into() };
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    state.runtime.udp_idle_timeout = Duration::from_secs(30);
    let accepts = Arc::new(AtomicUsize::new(0));
    let peer_cancel = CancellationToken::new();
    let mut peers = tokio::task::JoinSet::new();
    peers.spawn(refresh_proxy(listener, relay.local_addr().unwrap().port(), Arc::clone(&accepts), peer_cancel.clone()));
    let original = admit(&mut state, request(55_663, 443));
    let mut datagram = [0; 2048];
    let (first_length, first_source) =
        tokio::time::timeout(Duration::from_secs(3), relay.recv_from(&mut datagram)).await.unwrap().unwrap();
    let first_wire = datagram[..first_length].to_vec();
    let first_accepts = accepts.load(Ordering::Relaxed);
    relay.send_to(b"\0\0\0\x01\x5d\xb8\xd8\x22\x01\xbbold reply", first_source).await.unwrap();
    let old_event = tokio::time::timeout(Duration::from_secs(3), state.udp_rx.recv()).await.unwrap().unwrap();
    assert!(matches!(&old_event, UdpEvent::Packet { .. }), "peer sent an ordinary UDP reply");
    tokio::time::sleep(Duration::from_millis(5_050)).await;
    relay.send_to(b"\0\0\0\x01\x5d\xb8\xd8\x22\x01\xbbquiet reply", first_source).await.unwrap();
    let quiet_event = tokio::time::timeout(Duration::from_secs(3), state.udp_rx.recv()).await.unwrap().unwrap();
    handle_wait_event(&mut state, WaitEvent::Udp(Some(quiet_event)));
    let quiet_reply_delivered = state.device.tx_queue.pop_front().is_some();
    route_tun_packet(&ipv4_udp_packet(55_663, 443, b"refresh"), &mut state);
    let job = take_pending_request(original.request()).expect("ordinary five-second UID expiry must resolve again");
    handle_wait_event(&mut state, WaitEvent::Udp(Some(old_event)));
    let old_reply_dropped = state.device.tx_queue.is_empty();
    store_uid_and_scope_resolution(&job, Some(10_123), Some("81c5afbf-9a33-4680-880a-06c47840e52b"));
    retry_pending_uid_packets(&mut state);
    let second = tokio::time::timeout(Duration::from_secs(3), relay.recv_from(&mut datagram)).await;
    let second_accepts = accepts.load(Ordering::Relaxed);
    state.shutdown().await;
    peer_cancel.cancel();
    peers.join_next().await.unwrap().expect("peer server joined");
    let (second_length, second_source) = second.expect("refreshed datagram deadline").unwrap();
    assert_eq!(first_wire, b"\0\0\0\x01\x5d\xb8\xd8\x22\x01\xbboutbound");
    assert_eq!(&datagram[..second_length], b"\0\0\0\x01\x5d\xb8\xd8\x22\x01\xbbrefresh");
    assert!(quiet_reply_delivered, "inbound-only traffic must survive UID age before any successor is observed");
    assert!(old_reply_dropped, "queued old generation reply must stop during Pending");
    assert!(first_accepts > 0);
    assert_eq!(second_source, first_source, "same owner UID refresh must preserve the real UDP source port");
    assert_eq!(second_accepts, first_accepts, "same owner refresh must not reconnect SOCKS control");
}
