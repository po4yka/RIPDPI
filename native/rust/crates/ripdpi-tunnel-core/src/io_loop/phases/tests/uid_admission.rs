use super::*;

#[test]
fn pending_tcp_gc_cancels_lookup_generation() {
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    let packet = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(93, 184, 216, 34), 55_626, 443);
    let request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_TCP,
        local: "10.0.0.2:55626".parse().expect("local endpoint"),
        remote: "93.184.216.34:443".parse().expect("remote endpoint"),
    };
    route_tun_packet(&packet, &mut state);
    let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("pending TCP job");
    gc_stale_pending_listens(&mut state.pending_listens, &mut state.socket_set, Duration::ZERO);
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
    assert_eq!(
        ripdpi_flow_app_attribution::lookup_flow_uid(request.protocol, request.local, request.remote),
        ripdpi_flow_app_attribution::FlowUidLookup::Missing,
        "GC must retire the pending listener's UID job"
    );
    retry_pending_uid_packets(&mut state);
    assert!(state.device.rx_queue.is_empty());
}

/// # Cancel safety:
/// all packets and cache registrations belong to this test.
#[tokio::test(flavor = "current_thread")]
async fn queued_packet_cannot_borrow_reused_tuple_uid_generation() {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen))));
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    let packet = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(93, 184, 216, 34), 55_625, 443);
    let request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_TCP,
        local: "10.0.0.2:55625".parse().expect("local endpoint"),
        remote: "93.184.216.34:443".parse().expect("remote endpoint"),
    };
    route_tun_packet(&packet, &mut state);
    let original = ripdpi_flow_app_attribution::note_flow(request.protocol, request.local, request.remote);
    ripdpi_flow_app_attribution::evict_flow_if_current(original.registration_id);
    let replacement = ripdpi_flow_app_attribution::note_flow(request.protocol, request.local, request.remote);
    let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("replacement job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
    retry_pending_uid_packets(&mut state);
    assert!(seen.lock().expect("seen packets").is_empty(), "old packet must not inherit a new owner's UID");
    assert!(state.pending_uid_packets.is_empty());
    ripdpi_flow_app_attribution::evict_flow_if_current(replacement.registration_id);
    state.shutdown().await;
}

/// # Cancel safety:
/// no external I/O is started; shutdown only releases test-owned state.
#[tokio::test(flavor = "current_thread")]
async fn uid_admission_precedes_consuming_udp_egress_interceptor() {
    let seen_packets = Arc::new(Mutex::new(Vec::new()));
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen_packets))));
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    let denied_packet = ipv4_udp_packet(55_621, 443, b"uid-denied");
    let denied_request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_UDP,
        local: "10.0.0.2:55621".parse().expect("local endpoint"),
        remote: "93.184.216.34:443".parse().expect("remote endpoint"),
    };

    route_tun_packet(&denied_packet, &mut state);
    assert_eq!(seen_packets.lock().expect("seen packets").len(), 0, "pending UID must not reach raw egress");
    let job = ripdpi_flow_app_attribution::take_pending_request(denied_request).expect("denied flow job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(20_000));
    retry_pending_uid_packets(&mut state);
    assert!(seen_packets.lock().expect("seen packets").is_empty(), "denied UID must not reach raw egress");

    let allowed_packet = ipv4_udp_packet(55_622, 443, b"uid-allowed");
    let allowed_request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_UDP,
        local: "10.0.0.2:55622".parse().expect("local endpoint"),
        remote: "93.184.216.34:443".parse().expect("remote endpoint"),
    };
    route_tun_packet(&allowed_packet, &mut state);
    assert!(seen_packets.lock().expect("seen packets").is_empty(), "allowed flow still waits for UID resolution");
    let job = ripdpi_flow_app_attribution::take_pending_request(allowed_request).expect("allowed flow job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
    retry_pending_uid_packets(&mut state);
    retry_pending_uid_packets(&mut state);

    assert_eq!(seen_packets.lock().expect("seen packets").as_slice(), [allowed_packet], "admitted packet runs once");
    assert!(state.udp_associations.is_empty(), "consumed packet must not create a normal UDP association");
    assert!(state.pending_uid_packets.is_empty(), "resolved packets must leave the pending queue");
    state.shutdown().await;
}

/// # Cancel safety:
/// the test starts no external I/O and owns all queued packets.
#[tokio::test(flavor = "current_thread")]
async fn uid_admission_precedes_tcp_syn_egress_and_returns_denied_reset() {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen))));
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    let packet = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(93, 184, 216, 34), 55_623, 443);
    let request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_TCP,
        local: "10.0.0.2:55623".parse().expect("local endpoint"),
        remote: "93.184.216.34:443".parse().expect("remote endpoint"),
    };
    route_tun_packet(&packet, &mut state);
    assert!(seen.lock().expect("seen packets").is_empty(), "pending TCP must not reach raw egress");
    let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("TCP UID job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(20_000));
    retry_pending_uid_packets(&mut state);
    assert!(seen.lock().expect("seen packets").is_empty(), "denied TCP must not reach raw egress");
    assert!(state.device.rx_queue.is_empty(), "denied TCP must not enter the stack");
    {
        let reset = state.device.tx_queue.front().expect("local TCP reset");
        let parsed = etherparse::SlicedPacket::from_ip(reset).expect("valid reset packet");
        let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport else { panic!("TCP reset") };
        assert!(tcp.rst() && tcp.ack(), "denied SYN receives RST|ACK");
    }
    assert!(state.sessions.is_empty());
    assert!(state.pending_listens.is_empty(), "denied SYN must retire its listener and lookup");

    let allowed = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(93, 184, 216, 34), 55_624, 443);
    let allowed_request = ripdpi_flow_app_attribution::FlowResolveRequest {
        local: "10.0.0.2:55624".parse().expect("local endpoint"),
        ..request
    };
    route_tun_packet(&allowed, &mut state);
    let job = ripdpi_flow_app_attribution::take_pending_request(allowed_request).expect("allowed TCP UID job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
    retry_pending_uid_packets(&mut state);
    retry_pending_uid_packets(&mut state);
    assert_eq!(seen.lock().expect("seen packets").as_slice(), [allowed]);
    assert!(state.device.rx_queue.is_empty(), "consumed SYN must not enter the stack");
    state.shutdown().await;
}

#[test]
fn expired_pending_packet_cannot_reach_raw_egress() {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen))));
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    let packet = ipv4_udp_packet(55_627, 443, b"expired-uid-packet");
    let registration_id = ripdpi_flow_app_attribution::note_flow(
        crate::uid_policy::PROTO_UDP,
        "10.0.0.2:55627".parse().expect("local"),
        "93.184.216.34:443".parse().expect("remote"),
    )
    .registration_id;
    let job = ripdpi_flow_app_attribution::take_pending_request(registration_id.request()).expect("UID job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
    state.pending_uid_packets.retain(&packet, registration_id, std::time::Instant::now() - Duration::from_secs(6));
    retry_pending_uid_packets(&mut state);
    assert!(seen.lock().expect("seen packets").is_empty());
    assert!(state.pending_uid_packets.is_empty());
    ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
}

/// # Cancel safety:
/// The test owns its loop and shuts down every spawned session.
#[tokio::test(flavor = "current_thread")]
async fn pending_source_cannot_steal_an_allowed_syn_to_the_same_destination() {
    let mut state = tcp_test_loop();
    let pending = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(93, 184, 216, 34), 55_640, 443);
    route_tun_packet(&pending, &mut state);
    establish_allowed_tcp(&mut state, 55_641);
    assert_eq!(
        state.sessions.len(),
        1,
        "allowed source must own the accepted socket, even when a prior UID is pending"
    );
    let request = state
        .sessions
        .iter_mut()
        .next()
        .expect("session")
        .1
        .attribution_id
        .as_ref()
        .expect("registration_id")
        .request();
    assert_eq!(request.local.port(), 55_641);
    assert_eq!(state.pending_listens.len(), 1, "the unresolved source still owns its lookup");
    state.shutdown().await;
}

fn tcp_test_loop() -> LoopState {
    use smoltcp::wire::{IpAddress, IpCidr, Ipv4Address};
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    state.sessions = ActiveSessions::new(8);
    state.iface.update_ip_addrs(|ips| ips.push(IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).expect("IP"));
    state.iface.routes_mut().add_default_ipv4_route(Ipv4Address::new(10, 0, 0, 2)).expect("route");
    state.iface.set_any_ip(true);
    state
}

fn establish_allowed_tcp(state: &mut LoopState, port: u16) {
    let source = Ipv4Addr::new(10, 0, 0, 2);
    let destination = Ipv4Addr::new(93, 184, 216, 34);
    route_tun_packet(&build_ipv4_tcp_syn_packet(source, destination, port, 443), state);
    let request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_TCP,
        local: SocketAddr::new(source.into(), port),
        remote: SocketAddr::new(destination.into(), 443),
    };
    let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("UID job");
    ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
    retry_pending_uid_packets(state);
    poll_smoltcp(state);
    let key = crate::io_loop::packet::TcpFlowKey { src: request.local, dst: request.remote };
    let handle = state.pending_listens.get(&key).expect("pending exact tuple").handle;
    let tcp = state.socket_set.get::<smoltcp::socket::tcp::Socket>(handle);
    assert_eq!(
        tcp.remote_endpoint().map(crate::io_loop::packet::endpoint_to_socketaddr),
        Some(request.local),
        "SynReceived socket must be reconciled before GC or the next packet"
    );
    let response = state.device.tx_queue.pop_front().expect("SYN ACK");
    let (sequence, _) = tcp_seq_ack(&response);
    route_tun_packet(&build_ipv4_tcp_ack_packet(source, destination, port, 443, 1, sequence + 1), state);
    poll_smoltcp(state);
    admit_tcp_sessions(state);
}

/// # Cancel safety:
/// All spawned sessions belong to the test and are drained on shutdown.
#[tokio::test(flavor = "current_thread")]
async fn consumed_repeated_syn_cannot_acquire_active_session_attribution() {
    let mut state = tcp_test_loop();
    establish_allowed_tcp(&mut state, 55_642);
    let registration_id = state.sessions.iter_mut().next().expect("session").1.attribution_id.expect("registration_id");
    state.runtime.tun_egress_interceptor =
        Box::new(RecordingEgressHandler::new(true, Arc::new(Mutex::new(Vec::new()))));
    let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(93, 184, 216, 34), 55_642, 443);
    route_tun_packet(&syn, &mut state);
    gc_stale_pending_listens(&mut state.pending_listens, &mut state.socket_set, Duration::ZERO);
    assert_eq!(
        ripdpi_flow_app_attribution::lookup_registered_flow_uid(&registration_id),
        ripdpi_flow_app_attribution::FlowUidLookup::Resolved(Some(10_123)),
        "an intercepted retransmission must not evict the active session's UID owner"
    );
    state.shutdown().await;
}

#[test]
fn pending_three_cycle_reconciles_before_gc() {
    use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
    use crate::io_loop::tcp_accept::ensure_pending_listen_for_syn;
    use smoltcp::socket::tcp::Socket as TcpSocket;
    let mut state = tcp_test_loop();
    let source = Ipv4Addr::new(10, 0, 0, 2);
    let destination = Ipv4Addr::new(93, 184, 216, 34);
    for port in [55_643, 55_644, 55_645] {
        let syn = build_ipv4_tcp_syn_packet(source, destination, port, 443);
        ensure_pending_listen_for_syn(&syn, &mut state.pending_listens, &mut state.socket_set);
    }
    // Deliberately accept each source in a different destination-only listener.
    for port in [55_644, 55_645, 55_643] {
        state.device.push_rx(build_ipv4_tcp_syn_packet(source, destination, port, 443));
    }
    poll_smoltcp(&mut state);
    for (key, listener) in &state.pending_listens {
        let tcp = state.socket_set.get::<TcpSocket>(listener.handle);
        assert_eq!(tcp.remote_endpoint().map(endpoint_to_socketaddr), Some(key.src));
        assert_eq!(listener.attribution_id().request().local, key.src);
    }
    let oldest =
        TcpFlowKey { src: SocketAddr::new(source.into(), 55_643), dst: SocketAddr::new(destination.into(), 443) };
    state.pending_listens.get_mut(&oldest).expect("oldest owner").created_at -= Duration::from_secs(2);
    gc_stale_pending_listens(&mut state.pending_listens, &mut state.socket_set, Duration::from_secs(1));
    assert_eq!(state.pending_listens.len(), 2);
    for (key, listener) in &state.pending_listens {
        assert_ne!(key.src, oldest.src);
        let tcp = state.socket_set.get::<TcpSocket>(listener.handle);
        assert_eq!(tcp.remote_endpoint().map(endpoint_to_socketaddr), Some(key.src), "GC removes only its own flow");
    }
}
