use super::*;
use ripdpi_packets::{
    QUIC_V1_VERSION, QUIC_V2_VERSION, QuicInitialPacketLayout, QuicInitialSeed, packetize_quic_initial,
    parse_quic_initial_seed, validate_quic_version_negotiation_response,
};
use ripdpi_proxy_runtime_adapter::model::config::{UdpChainStep, UdpChainStepKind};

fn real_initial(version: u32) -> Vec<u8> {
    if version == QUIC_V1_VERSION {
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin"
        ))
        .to_vec()
    } else {
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/quic/rfc9369-client-initial.bin"
        ))
        .to_vec()
    }
}

fn real_responses(version: u32) -> (&'static [u8], &'static [u8]) {
    if version == QUIC_V1_VERSION {
        (
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin"
            )),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9001-retry.bin"
            )),
        )
    } else {
        (
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9369-server-initial.bin"
            )),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9369-retry.bin"
            )),
        )
    }
}

fn version_negotiation(seed: &QuicInitialSeed) -> Vec<u8> {
    let mut response = vec![0x80, 0, 0, 0, 0, seed.scid.len() as u8];
    response.extend_from_slice(&seed.scid);
    response.push(seed.dcid.len() as u8);
    response.extend_from_slice(&seed.dcid);
    let other_version = if seed.version == QUIC_V1_VERSION { QUIC_V2_VERSION } else { QUIC_V1_VERSION };
    response.extend_from_slice(&other_version.to_be_bytes());
    response
}

#[test]
fn quic_decoy_response_does_not_complete_the_real_flow() {
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        let peer = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("peer");
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer timeout");
        let target = peer.local_addr().expect("peer address");
        let upstream = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("upstream");
        upstream.connect(target).expect("connect upstream");
        upstream.set_nonblocking(true).expect("nonblocking upstream");
        peer.connect(upstream.local_addr().expect("upstream address")).expect("connect peer");
        let client_relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("relay");
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("client");
        client.set_read_timeout(Some(Duration::from_secs(2))).expect("client timeout");
        let key = UdpFlowKey {
            client: client.local_addr().expect("client address"),
            target,
            preserve_host_in_response: false,
            authoritative_host: None,
        };
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicPacketNumberGap, 1)];
        let state = test_runtime_state(RuntimeConfig { groups: vec![group], ..Default::default() });
        let mut flows = HashMap::from([(key.clone(), flow_entry_for(upstream, target))]);
        let original = real_initial(version);
        super::super::upstream_pump::send_udp_flow_payload(
            &state,
            flows.get_mut(&key).expect("flow"),
            &original,
            Instant::now(),
            None,
        )
        .expect("send real payload and decoy");
        let mut wire = [0u8; 2048];
        let n = peer.recv(&mut wire).expect("receive actual decoy");
        let decoy = wire[..n].to_vec();
        let decoy_seed = parse_quic_initial_seed(&decoy).expect("decode actual decoy");
        let n = peer.recv(&mut wire).expect("receive actual original");
        assert_eq!(&wire[..n], &original);
        let real_seed = parse_quic_initial_seed(&original).expect("decode original");
        assert_ne!(decoy_seed.scid, real_seed.scid);
        let follow_up = b"\x40client-follow-up";
        super::super::upstream_pump::send_udp_flow_payload(
            &state,
            flows.get_mut(&key).expect("flow"),
            follow_up,
            Instant::now(),
            None,
        )
        .expect("send opaque follow-up");
        let n = peer.recv(&mut wire).expect("receive follow-up");
        assert_eq!(&wire[..n], follow_up);
        let mut poll_scratch = UdpUpstreamPollScratch::default();
        let mut encode_buffer = Vec::new();
        let mut pump_reply = |reply: &[u8], flows: &mut HashMap<UdpFlowKey, UdpFlowActivationState>| {
            peer.send(reply).expect("send server reply");
            let deadline = Instant::now() + Duration::from_secs(2);
            while !pump_udp_upstream_responses(
                &state,
                &client_relay,
                &mut wire,
                &mut encode_buffer,
                flows,
                &mut poll_scratch,
                None,
                None,
            )
            .expect("pump server reply")
            {
                assert!(Instant::now() < deadline, "reply never arrived");
                std::thread::sleep(Duration::from_millis(5));
            }
            let mut received = [0u8; 2048];
            let n = client.recv(&mut received).expect("receive forwarded reply");
            let (sender, payload) = parse_socks5_udp_packet(&received[..n], &state).expect("SOCKS reply");
            assert_eq!(sender, target);
            assert_eq!(payload, reply, "even an unrelated reply is forwarded without modification");
        };
        let last_real_activity = flows.get(&key).expect("flow").last_used;
        let decoy_reply = version_negotiation(&decoy_seed);
        assert!(validate_quic_version_negotiation_response(&decoy, &decoy_reply));
        assert!(!validate_quic_version_negotiation_response(&original, &decoy_reply));
        pump_reply(&decoy_reply, &mut flows);
        let entry = flows.get(&key).expect("flow");
        assert!(!entry.answered, "a decoy-only response must not answer the real flow");
        assert_eq!(entry.last_used, last_real_activity, "a decoy reply must not postpone real-flow expiry");
        assert!(entry.awaiting_response, "the real payload still needs a response");
        let real_reply = version_negotiation(&real_seed);
        assert!(validate_quic_version_negotiation_response(&original, &real_reply));
        pump_reply(&real_reply, &mut flows);
        assert!(!flows.get(&key).expect("flow").answered, "VN does not establish the selected version");
        pump_reply(real_responses(version).0, &mut flows);
        let entry = flows.get(&key).expect("flow");
        assert!(entry.answered);
        assert!(!entry.awaiting_response);
    }
}

#[test]
fn quic_reply_correlation_keeps_server_cid_changes_and_retry_context() {
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        let original = real_initial(version);
        let seed = parse_quic_initial_seed(&original).expect("original seed");
        let mut session = UdpFlowSession::new();
        session.observe_committed_datagram(&original, true);
        for reply in [real_responses(version).0, real_responses(version).1] {
            assert!(session.response_matches_outbound(reply), "independent Initial/Retry may select a server CID");
            let mut wrong_cid = reply.to_vec();
            wrong_cid[5] = 1;
            wrong_cid.insert(6, 0xaa);
            assert!(!session.response_matches_outbound(&wrong_cid));
        }
        assert!(!session.response_matches_outbound(b"\x40opaque-short-header"));
        assert!(!session.response_matches_outbound(&version_negotiation(&seed)));
        let mut bare = vec![if version == QUIC_V1_VERSION { 0xc0 } else { 0xd0 }];
        bare.extend_from_slice(&version.to_be_bytes());
        bare.extend_from_slice(&[0, 0]);
        assert!(!session.response_matches_outbound(&bare), "a bare matching header has no integrity evidence");
        let next = include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-post-retry-client.bin"
        ));
        session.observe_committed_datagram(next, true);
        session.observe_committed_datagram(b"\x40later-client-data", true);
        assert!(session.response_matches_outbound(real_responses(version).0), "earlier real contexts are not evicted");
    }
}

#[test]
fn quic_reply_correlation_bounds_untrusted_header_lengths() {
    let original = real_initial(QUIC_V1_VERSION);
    let seed = parse_quic_initial_seed(&original).expect("original seed");
    let reply = version_negotiation(&seed);
    let mut session = UdpFlowSession::new();
    assert!(session.response_matches_outbound(&reply), "generic UDP keeps its existing response policy");
    session.observe_committed_datagram(&original, true);
    for len in 1..(7 + seed.scid.len() + seed.dcid.len()) {
        assert!(!session.response_matches_outbound(&reply[..len]), "truncated long header len={len}");
    }
    let mut oversized = reply.clone();
    oversized[5] = 21;
    assert!(!session.response_matches_outbound(&oversized));
    let mut oversized = reply;
    oversized[6 + seed.scid.len()] = 21;
    assert!(!session.response_matches_outbound(&oversized));
}

#[test]
fn quic_reply_contexts_deduplicate_and_bound_multiplexed_connections() {
    let original = real_initial(QUIC_V1_VERSION);
    let mut seed = parse_quic_initial_seed(&original).expect("original seed");
    let first_reply = real_responses(QUIC_V1_VERSION).0;
    let mut session = UdpFlowSession::new();
    for _ in 0..128 {
        session.observe_committed_datagram(&original, true);
    }
    for connection in 1u8..64 {
        seed.scid = vec![connection; 8];
        seed.dcid = vec![connection; 8];
        let next =
            packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("multiplexed Initial");
        session.observe_committed_datagram(&next, true);
        assert!(session.feedback_is_eligible(), "retransmissions do not consume the context limit");
    }
    assert!(session.response_matches_outbound(first_reply), "the first of many real connections remains recognized");
    assert!(!session.response_matches_outbound(&version_negotiation(&seed)), "VN stays negative with a matching CID");
    seed.scid = vec![64; 8];
    seed.dcid = vec![64; 8];
    let overflow = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("overflow Initial");
    session.observe_committed_datagram(&overflow, true);
    assert!(!session.feedback_is_eligible());
    assert!(!session.response_matches_outbound(first_reply), "overflow is ambiguous, not positive success evidence");
    assert!(!session.response_matches_outbound(b"\x40opaque-short-header"));
}

#[test]
fn ambiguous_quic_flow_keeps_delivery_and_disarms_silent_failure() {
    assert_ambiguous_flow_policy(false);
    assert_ambiguous_flow_policy(true);
}

fn assert_ambiguous_flow_policy(hard_error: bool) {
    let peer = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("peer");
    peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer timeout");
    let target = peer.local_addr().expect("peer address");
    let upstream = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("upstream");
    upstream.connect(target).expect("connect upstream");
    upstream.set_nonblocking(true).expect("nonblocking upstream");
    peer.connect(upstream.local_addr().expect("upstream address")).expect("connect peer");
    let client_relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("relay");
    let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("client");
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("client timeout");
    let key = UdpFlowKey {
        client: client.local_addr().expect("client address"),
        target,
        preserve_host_in_response: false,
        authoritative_host: None,
    };
    let mut entry = flow_entry_for(upstream, target);
    let original = real_initial(QUIC_V1_VERSION);
    let mut seed = parse_quic_initial_seed(&original).expect("original seed");
    for connection in 0u8..65 {
        seed.scid = vec![connection; 8];
        seed.dcid = vec![connection; 8];
        let next = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("Initial");
        entry.session.observe_committed_datagram(&next, true);
    }
    let fallback = UdpSocket::bind("127.0.0.1:0").unwrap();
    fallback.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    entry.target_candidates.push(fallback.local_addr().unwrap());
    let pending = entry.pending_payload().to_vec();
    let state = test_runtime_state(RuntimeConfig::default());
    super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, b"\x40real-data", Instant::now(), None)
        .expect("send real data");
    assert!(!entry.awaiting_response, "later sends must not re-arm ambiguous failure evidence");
    let mut sent = [0u8; 64];
    let n = peer.recv(&mut sent).expect("receive real data");
    assert_eq!(&sent[..n], b"\x40real-data");
    let last_activity = Instant::now() - Duration::from_secs(1);
    entry.last_used = last_activity;
    let mut flows = HashMap::from([(key.clone(), entry)]);
    peer.send(b"\x40opaque-reply").expect("server reply");
    let mut upstream_buffer = [0u8; 2048];
    let mut encoded = Vec::new();
    let mut scratch = UdpUpstreamPollScratch::default();
    let deadline = Instant::now() + Duration::from_secs(2);
    while !pump_udp_upstream_responses(
        &state,
        &client_relay,
        &mut upstream_buffer,
        &mut encoded,
        &mut flows,
        &mut scratch,
        None,
        None,
    )
    .expect("pump reply")
    {
        assert!(Instant::now() < deadline);
        std::thread::sleep(Duration::from_millis(5));
    }
    let mut received = [0u8; 2048];
    let n = client.recv(&mut received).expect("forwarded reply");
    let (_, payload) = parse_socks5_udp_packet(&received[..n], &state).expect("SOCKS reply");
    assert_eq!(payload, b"\x40opaque-reply");
    let entry = flows.get(&key).expect("flow");
    assert!(entry.last_used > last_activity, "ambiguous traffic still records activity");
    assert!(!entry.answered);
    assert!(!entry.awaiting_response);
    if hard_error {
        super::super::flow::retire_failed_udp_flow(&state, &mut flows, &key, None, Instant::now()).unwrap();
        let mut actual = [0; 2048];
        let n = fallback.recv(&mut actual).unwrap();
        assert_eq!(&actual[..n], pending);
        assert!(flows.get(&key).unwrap().session.feedback_is_eligible());
        assert!(flows.get(&key).unwrap().awaiting_response);
        return;
    }
    let expiry = entry.last_used + UDP_FLOW_IDLE_TIMEOUT + Duration::from_millis(1);
    super::super::flow::expire_udp_flows(&state, &mut flows, None, expiry, &mut Vec::new())
        .expect("expire ambiguous flow");
    assert!(flows.is_empty());
}
