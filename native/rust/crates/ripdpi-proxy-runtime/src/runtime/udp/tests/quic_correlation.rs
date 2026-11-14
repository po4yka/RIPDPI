use super::*;

fn rfc_packets(v2: bool) -> (&'static [u8], &'static [u8], &'static [u8]) {
    if v2 {
        (
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9369-client-initial.bin"
            )),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9369-server-initial.bin"
            )),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9369-retry.bin"
            )),
        )
    } else {
        (
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin"
            )),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin"
            )),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/rfc9001-retry.bin"
            )),
        )
    }
}

fn vn_reply(request: &[u8], decoy: bool) -> Vec<u8> {
    let dcid_len = usize::from(request[5]);
    let scid_offset = 6 + dcid_len;
    let scid_len = usize::from(request[scid_offset]);
    let scid =
        if decoy { &[0x11, 0x22, 0x33, 0x44][..] } else { &request[scid_offset + 1..scid_offset + 1 + scid_len] };
    let mut response = vec![0xc0, 0, 0, 0, 0, scid.len() as u8];
    response.extend_from_slice(scid);
    response.push(dcid_len as u8);
    response.extend_from_slice(&request[6..6 + dcid_len]);
    let alternative = if request[1..5] == 1u32.to_be_bytes() { ripdpi_packets::QUIC_V2_VERSION } else { 1 };
    response.extend_from_slice(&alternative.to_be_bytes());
    response
}

fn assert_first_reply_accounting(request: &[u8], replies: &[(&[u8], bool)], follow_up: bool) {
    assert_accounting_with_second_initial(request, replies, follow_up, None);
}

fn assert_accounting_with_second_initial(
    request: &[u8],
    replies: &[(&[u8], bool)],
    follow_up: bool,
    second: Option<&[u8]>,
) {
    let peer = UdpSocket::bind("127.0.0.1:0").unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let target = peer.local_addr().unwrap();
    let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
    upstream.connect(target).unwrap();
    upstream.set_nonblocking(true).unwrap();
    peer.connect(upstream.local_addr().unwrap()).unwrap();
    let client_relay = UdpSocket::bind("127.0.0.1:0").unwrap();
    let client = UdpSocket::bind("127.0.0.1:0").unwrap();
    client.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let key = UdpFlowKey {
        client: client.local_addr().unwrap(),
        target,
        preserve_host_in_response: false,
        authoritative_host: None,
    };
    let state = test_runtime_state(RuntimeConfig::default());
    let mut flows = HashMap::from([(key.clone(), flow_entry_for(upstream, target))]);
    let entry = flows.get_mut(&key).unwrap();
    super::super::upstream_pump::send_udp_flow_payload(&state, entry, request, Instant::now(), None).unwrap();
    let mut received = [0; 65535];
    assert_eq!(peer.recv(&mut received).unwrap(), request.len());
    if follow_up {
        super::super::upstream_pump::send_udp_flow_payload(&state, entry, b"\x40follow-up", Instant::now(), None)
            .unwrap();
        peer.recv(&mut received).unwrap();
        let mut malformed = request.to_vec();
        *malformed.last_mut().unwrap() ^= 1;
        super::super::upstream_pump::send_udp_flow_payload(&state, entry, &malformed, Instant::now(), None).unwrap();
        peer.recv(&mut received).unwrap();
    }
    if let Some(next) = second {
        super::super::upstream_pump::send_udp_flow_payload(&state, entry, next, Instant::now(), None).unwrap();
        peer.recv(&mut received).unwrap();
    }
    let expected_pending = second.unwrap_or(request);
    let mut encode = Vec::new();
    let mut scratch = UdpUpstreamPollScratch::default();
    for &(reply, accepted) in replies {
        let last_used = flows.get(&key).unwrap().last_used;
        peer.send(reply).unwrap();
        let deadline = Instant::now() + Duration::from_secs(1);
        while !pump_udp_upstream_responses(
            &state,
            &client_relay,
            &mut received,
            &mut encode,
            &mut flows,
            &mut scratch,
            None,
            None,
        )
        .unwrap()
        {
            assert!(Instant::now() < deadline);
            std::thread::yield_now();
        }
        let n = client.recv(&mut received).unwrap();
        let (_, forwarded) = parse_socks5_udp_packet(&received[..n], &state).unwrap();
        assert_eq!(forwarded, reply, "forwarding does not depend on success accounting");
        let entry = flows.get(&key).unwrap();
        assert_eq!(entry.answered, accepted);
        assert_eq!(entry.awaiting_response, !accepted);
        if !accepted {
            assert_eq!(entry.last_used, last_used, "unrelated traffic must not extend expiry");
            assert_eq!(entry.pending_payload(), expected_pending, "keep first-flight metadata for timeout/fallback");
        } else {
            assert!(entry.session.pending_initial().is_none());
        }
    }
}

#[test]
fn dpi24_quic_success_requires_integrity_and_survives_outbound_follow_up() {
    for v2 in [false, true] {
        let (request, initial, retry) = rfc_packets(v2);
        let decoy_vn = vn_reply(request, true);
        let actual_vn = vn_reply(request, false);
        let mut corrupt = initial.to_vec();
        *corrupt.last_mut().unwrap() ^= 1;
        for accepted in [initial, retry] {
            assert_first_reply_accounting(
                request,
                &[(&decoy_vn, false), (&actual_vn, false), (&corrupt, false), (accepted, true)],
                true,
            );
        }
    }
}

#[test]
fn dpi24_generic_udp_keeps_first_reply_behavior() {
    assert_first_reply_accounting(b"generic request", &[(b"generic response", true)], false);
}

#[test]
fn dpi24_declared_compatible_initial_is_accepted() {
    for (request, response) in [
        (
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-client.bin"
            ))
            .as_slice(),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-server.bin"
            ))
            .as_slice(),
        ),
        (
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/compatible/v2-v1-client.bin"
            ))
            .as_slice(),
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/compatible/v2-v1-server.bin"
            ))
            .as_slice(),
        ),
    ] {
        assert_first_reply_accounting(request, &[(response, true)], true);
    }
}

#[test]
fn dpi24_unverifiable_expiry_is_neutral_but_hard_errors_keep_fallback() {
    let request = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-partial-client.bin"
    ))
    .as_slice();
    let response = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-server.bin"
    ))
    .as_slice();
    assert_neutral_expiry_and_fallback(request, response);
    let request = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-grease-partial-client.bin"
    ));
    let response = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-grease-retry.bin"
    ));
    assert_neutral_expiry_and_fallback(request, response);
}

fn assert_neutral_expiry_and_fallback(request: &[u8], response: &[u8]) {
    assert_first_reply_accounting(request, &[(response, false)], true);
    for (hard_error, new_connection) in [(false, false), (true, false), (false, true)] {
        let peer = UdpSocket::bind("127.0.0.1:0").unwrap();
        let target = peer.local_addr().unwrap();
        let fallback = UdpSocket::bind("127.0.0.1:0").unwrap();
        fallback.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
        upstream.connect(target).unwrap();
        upstream.set_nonblocking(true).unwrap();
        peer.connect(upstream.local_addr().unwrap()).unwrap();
        let client_relay = UdpSocket::bind("127.0.0.1:0").unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        let key = UdpFlowKey {
            client: client.local_addr().unwrap(),
            target,
            preserve_host_in_response: false,
            authoritative_host: None,
        };
        let state = test_runtime_state(RuntimeConfig::default());
        let mut entry = flow_entry_for(upstream, target);
        entry.target_candidates.push(fallback.local_addr().unwrap());
        super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, request, Instant::now(), None).unwrap();
        super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, b"follow-up", Instant::now(), None)
            .unwrap();
        entry.last_used = Instant::now() - UDP_FLOW_IDLE_TIMEOUT;
        let mut flows = HashMap::from([(key.clone(), entry)]);
        peer.send(response).unwrap();
        let mut buffer = [0; 2048];
        let receive_deadline = Instant::now() + Duration::from_secs(1);
        loop {
            if pump_udp_upstream_responses(
                &state,
                &client_relay,
                &mut buffer,
                &mut Vec::new(),
                &mut flows,
                &mut UdpUpstreamPollScratch::default(),
                None,
                None,
            )
            .unwrap()
            {
                break;
            }
            assert!(Instant::now() < receive_deadline, "loopback response did not become readable");
            std::thread::yield_now();
        }
        assert!(flows.get(&key).unwrap().session.has_unverifiable_client_offer());
        let mut malformed = request.to_vec();
        *malformed.last_mut().unwrap() ^= 1;
        let entry = flows.get_mut(&key).unwrap();
        super::super::upstream_pump::send_udp_flow_payload(&state, entry, &malformed, Instant::now(), None).unwrap();
        entry.last_used = Instant::now() - UDP_FLOW_IDLE_TIMEOUT;
        assert_eq!(entry.session.pending_initial(), Some(request));
        assert!(entry.session.has_unverifiable_client_offer());
        let expected_request = if new_connection {
            let next = include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-post-retry-client.bin"
            ))
            .as_slice();
            let entry = flows.get_mut(&key).unwrap();
            super::super::upstream_pump::send_udp_flow_payload(&state, entry, next, Instant::now(), None).unwrap();
            entry.last_used = Instant::now() - UDP_FLOW_IDLE_TIMEOUT;
            assert!(!entry.session.has_unverifiable_client_offer());
            // A delayed unknown-offer response to A cannot make the pending B neutral.
            peer.send(response).unwrap();
            let deadline = Instant::now() + Duration::from_secs(1);
            while !pump_udp_upstream_responses(
                &state,
                &client_relay,
                &mut buffer,
                &mut Vec::new(),
                &mut flows,
                &mut UdpUpstreamPollScratch::default(),
                None,
                None,
            )
            .unwrap()
            {
                assert!(Instant::now() < deadline);
                std::thread::yield_now();
            }
            assert!(!flows.get(&key).unwrap().session.has_unverifiable_client_offer());
            next
        } else {
            request
        };
        if hard_error {
            super::super::flow::retire_failed_udp_flow(&state, &mut flows, &key, None, Instant::now()).unwrap();
            let n = fallback.recv(&mut buffer).unwrap();
            assert_eq!(&buffer[..n], expected_request, "retry Initial instead of the latest follow-up");
            assert!(!flows.get(&key).unwrap().session.has_unverifiable_client_offer());
        } else {
            super::super::flow::expire_udp_flows(&state, &mut flows, None, Instant::now(), &mut Vec::new()).unwrap();
            if new_connection {
                let n = fallback.recv(&mut buffer).unwrap();
                assert_eq!(&buffer[..n], expected_request);
                assert!(flows.get(&key).unwrap().awaiting_response);
            } else {
                assert!(!flows.contains_key(&key));
                assert!(fallback.recv(&mut buffer).is_err());
            }
        }
    }
}

#[test]
fn dpi24_decoy_vn_does_not_suppress_expiry_after_follow_up() {
    let (request, _, _) = rfc_packets(false);
    let peer = UdpSocket::bind("127.0.0.1:0").unwrap();
    let target = peer.local_addr().unwrap();
    let fallback = UdpSocket::bind("127.0.0.1:0").unwrap();
    fallback.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
    upstream.connect(target).unwrap();
    upstream.set_nonblocking(true).unwrap();
    peer.connect(upstream.local_addr().unwrap()).unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").unwrap();
    let client = UdpSocket::bind("127.0.0.1:0").unwrap();
    let key = UdpFlowKey {
        client: client.local_addr().unwrap(),
        target,
        preserve_host_in_response: false,
        authoritative_host: None,
    };
    let state = test_runtime_state(RuntimeConfig::default());
    let mut entry = flow_entry_for(upstream, target);
    entry.target_candidates.push(fallback.local_addr().unwrap());
    super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, request, Instant::now(), None).unwrap();
    super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, b"follow-up", Instant::now(), None).unwrap();
    entry.last_used = Instant::now() - UDP_FLOW_IDLE_TIMEOUT;
    let mut flows = HashMap::from([(key.clone(), entry)]);
    peer.send(&vn_reply(request, true)).unwrap();
    let mut buffer = [0; 2048];
    let receive_deadline = Instant::now() + Duration::from_secs(1);
    loop {
        if pump_udp_upstream_responses(
            &state,
            &relay,
            &mut buffer,
            &mut Vec::new(),
            &mut flows,
            &mut UdpUpstreamPollScratch::default(),
            None,
            None,
        )
        .unwrap()
        {
            break;
        }
        assert!(Instant::now() < receive_deadline, "loopback response did not become readable");
        std::thread::yield_now();
    }
    super::super::flow::expire_udp_flows(&state, &mut flows, None, Instant::now(), &mut Vec::new()).unwrap();
    let n = fallback.recv(&mut buffer).unwrap();
    assert_eq!(&buffer[..n], request);
    assert!(flows.get(&key).unwrap().awaiting_response);
}

#[test]
fn dpi24_declared_fixed_bit_grease_initial_and_retry_are_accepted() {
    macro_rules! fixture {
        ($name:literal) => {
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../ripdpi-packets/tests/fixtures/quic/compatible/",
                $name,
                ".bin"
            ))
            .as_slice()
        };
    }
    for (request, initial, retry, compatible) in [
        (
            fixture!("v1-grease-client"),
            fixture!("v1-grease-initial"),
            fixture!("v1-grease-retry"),
            fixture!("v1-v2-grease-initial"),
        ),
        (
            fixture!("v2-grease-client"),
            fixture!("v2-grease-initial"),
            fixture!("v2-grease-retry"),
            fixture!("v2-v1-grease-initial"),
        ),
    ] {
        for response in [initial, retry, compatible] {
            assert_first_reply_accounting(request, &[(response, true)], true);
        }
    }
}

#[test]
fn dpi24_bad_integrity_initial_lookalike_keeps_generic_udp_accounting() {
    for v2 in [false, true] {
        let (request, _, _) = rfc_packets(v2);
        let mut malformed = request.to_vec();
        *malformed.last_mut().unwrap() ^= 1;
        assert_first_reply_accounting(&malformed, &[(b"generic response", true)], false);
    }
}

#[test]
fn dpi24_multiplexed_later_initial_keeps_earlier_valid_response() {
    let (request, response, _) = rfc_packets(false);
    let next = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-post-retry-client.bin"
    ));
    assert_accounting_with_second_initial(request, &[(response, true)], true, Some(next));
}

#[test]
fn dpi24_failed_initial_write_falls_back_with_attempted_initial() {
    let closed = UdpSocket::bind("127.0.0.1:0").unwrap();
    let target = closed.local_addr().unwrap();
    drop(closed);
    let fallback = UdpSocket::bind("127.0.0.1:0").unwrap();
    fallback.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
    upstream.connect(target).unwrap();
    let state = test_runtime_state(RuntimeConfig::default());
    let mut entry = flow_entry_for(upstream, target);
    entry.target_candidates.push(fallback.local_addr().unwrap());
    let (first, first_reply, _) = rfc_packets(false);
    super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, first, Instant::now(), None).unwrap();
    std::thread::sleep(Duration::from_millis(50));
    let next = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-post-retry-client.bin"
    ));
    assert!(
        super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, next, Instant::now(), None).is_err()
    );
    assert_eq!(entry.pending_payload(), next);
    assert!(entry.session.response_matches_outbound(first_reply), "a failed B write does not register or evict A");
    let key = UdpFlowKey {
        client: "127.0.0.1:12345".parse().unwrap(),
        target,
        preserve_host_in_response: false,
        authoritative_host: None,
    };
    let mut flows = HashMap::from([(key.clone(), entry)]);
    super::super::flow::retire_failed_udp_flow(&state, &mut flows, &key, None, Instant::now()).unwrap();
    let mut actual = [0; 2048];
    let n = fallback.recv(&mut actual).unwrap();
    assert_eq!(&actual[..n], next);
}

#[test]
fn dpi24_unsent_lua_drop_initial_never_records_response_success() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("candidate.lua"), "function candidate(d) return VERDICT_DROP end").unwrap();
    let state = test_runtime_state_with_context(RuntimeConfig::default(), Some(ProxyRuntimeContext {
        strategy_chain_yaml: Some("version: 1\nstrategies:\n  - id: socket\n    on_fail: fallback_plain\n    steps:\n      - type: lua\n        function: candidate\n        script_paths: [candidate.lua]\n".into()),
        lua_script_base_dir: Some(dir.path().to_string_lossy().into_owned()),
        ..ProxyRuntimeContext::default()
    }));
    let peer = UdpSocket::bind("127.0.0.1:0").unwrap();
    peer.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
    let target = peer.local_addr().unwrap();
    let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
    upstream.connect(target).unwrap();
    upstream.set_nonblocking(true).unwrap();
    peer.connect(upstream.local_addr().unwrap()).unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").unwrap();
    let client = UdpSocket::bind("127.0.0.1:0").unwrap();
    client.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let mut entry = flow_entry_for(upstream, target);
    entry.lua_flow = state.new_lua_flow();
    let (request, initial, retry) = rfc_packets(false);
    super::super::upstream_pump::send_udp_flow_payload(&state, &mut entry, request, Instant::now(), None).unwrap();
    let mut wire = [0; 2048];
    assert!(peer.recv(&mut wire).is_err(), "Lua Drop has no real write");
    let last_used = entry.last_used;
    let key = UdpFlowKey {
        client: client.local_addr().unwrap(),
        target,
        preserve_host_in_response: false,
        authoritative_host: None,
    };
    let mut flows = HashMap::from([(key.clone(), entry)]);
    for response in [b"generic reply".as_slice(), initial, retry] {
        peer.send(response).unwrap();
        let deadline = Instant::now() + Duration::from_secs(1);
        while !pump_udp_upstream_responses(
            &state,
            &relay,
            &mut wire,
            &mut Vec::new(),
            &mut flows,
            &mut UdpUpstreamPollScratch::default(),
            None,
            None,
        )
        .unwrap()
        {
            assert!(Instant::now() < deadline);
            std::thread::yield_now();
        }
        let n = client.recv(&mut wire).unwrap();
        let (_, forwarded) = parse_socks5_udp_packet(&wire[..n], &state).unwrap();
        assert_eq!(forwarded, response);
        let entry = flows.get(&key).unwrap();
        assert!(!entry.answered);
        assert!(!entry.awaiting_response);
        assert_eq!(entry.last_used, last_used);
    }
    let entry = flows.get_mut(&key).unwrap();
    entry.lua_flow = None;
    super::super::upstream_pump::send_udp_flow_payload(&state, entry, request, Instant::now(), None).unwrap();
    let n = peer.recv(&mut wire).unwrap();
    assert_eq!(&wire[..n], request);
    assert!(
        entry.session.response_matches_outbound(initial),
        "a committed real Initial permits its authenticated response"
    );
}
