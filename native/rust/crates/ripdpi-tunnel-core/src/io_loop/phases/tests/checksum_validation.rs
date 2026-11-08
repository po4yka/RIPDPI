use super::*;

/// # Cancel safety:
/// cancel-safe: routing is synchronous and invalid input creates no worker.
#[tokio::test]
async fn bad_udp_checksum_is_dropped_before_hooks_and_association_admission() {
    let seen_packets = Arc::new(Mutex::new(Vec::new()));
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
    let packet = hex::decode("4500001f00000000401134c30a000002cb0071099c4001bb000b17ce010203")
        .expect("independent packet with corrupted UDP checksum");

    route_tun_packet(&packet, &mut state);

    assert!(seen_packets.lock().expect("seen packets").is_empty());
    assert!(state.udp_associations.is_empty());
    assert!(state.device.rx_queue.is_empty());
    assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_parse_failures, 1);
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn invalid_ipv4_header_checksum_is_rejected_before_routing() {
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    // Scapy IPv4/UDP vector; only the IPv4 checksum is corrupt.
    let mut packet = hex::decode("4500001f00000000401134c30a000002cb0071099c4001bb000b17cf010203").unwrap();
    packet[10] ^= 0x80;
    route_tun_packet(&packet, &mut state);
    let admitted = state.udp_associations.len();
    let failures = state.stats.tun_forwarding_evidence_snapshot().tun_parse_failures;
    state.cancel.cancel();
    state.shutdown().await;
    assert_eq!(admitted, 0, "a correct UDP checksum must not bypass IPv4 header validation");
    assert_eq!(failures, 1);
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn invalid_ipv4_fragment_cannot_supply_reassembly_bytes() {
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    let mut packet = Vec::new();
    etherparse::PacketBuilder::ipv4([10, 0, 0, 2], [203, 0, 113, 9], 64)
        .udp(54000, 443)
        .write(&mut packet, &[7; 64])
        .unwrap();
    let mut fragments = ipv4_fragments(&packet, 24);
    for fragment in &mut fragments {
        let mut ip = smoltcp::wire::Ipv4Packet::new_checked(fragment.as_mut_slice()).unwrap();
        ip.fill_checksum();
    }
    fragments[0][10] ^= 1;
    for fragment in &fragments {
        route_tun_packet(fragment, &mut state);
    }
    let admitted = state.udp_associations.len();
    state.cancel.cancel();
    state.shutdown().await;
    assert_eq!(admitted, 0, "invalid fragment bytes must not become a valid rebuilt IPv4 packet");
}

fn ipv6_udp_extension(next_header: u8, option: [u8; 6]) -> Vec<u8> {
    let original = hex::decode(
        "60000000000b1140fd00000000000000000000000000000120010db80000000000000000000000019c4001bb000b331f010203",
    )
    .unwrap();
    let mut packet = original[..40].to_vec();
    packet[4..6].copy_from_slice(&19u16.to_be_bytes());
    packet[6] = next_header;
    packet.extend_from_slice(&[17, 0]);
    packet.extend_from_slice(&option);
    packet.extend_from_slice(&original[40..]);
    packet
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn ipv6_discard_options_and_malformed_tlvs_never_reach_udp() {
    for next in [0, 60] {
        for option in [[0x40, 2, 0, 0, 0, 0], [0x80, 2, 0, 0, 0, 0], [0xc0, 2, 0, 0, 0, 0], [0x1e, 6, 0, 0, 0, 0]] {
            let mut state =
                test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
            route_tun_packet(&ipv6_udp_extension(next, option), &mut state);
            let admitted = state.udp_associations.len();
            state.cancel.cancel();
            state.shutdown().await;
            assert_eq!(admitted, 0, "next header {next}, option {option:?}");
        }
    }
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn ipv6_padding_and_skip_options_keep_udp_payload() {
    for next in [0, 60] {
        for option in [[1, 4, 0, 0, 0, 0], [0x1e, 2, 0xab, 0xcd, 0, 0]] {
            let mut state =
                test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
            route_tun_packet(&ipv6_udp_extension(next, option), &mut state);
            let admitted = state.udp_associations.len();
            state.cancel.cancel();
            state.shutdown().await;
            assert_eq!(admitted, 1, "next header {next}, option {option:?}");
        }
    }
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn ipv6_option_feedback_is_rate_limited_and_requires_admitted_policy() {
    for enforcing in [false, true] {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.iface.update_ip_addrs(|addresses| addresses.push("fd00::1/64".parse().unwrap()).unwrap());
        if enforcing {
            state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::new());
        }
        for _ in 0..12 {
            route_tun_packet(&ipv6_udp_extension(0, [0x80, 2, 0, 0, 0, 0]), &mut state);
        }
        let replies = state.device.tx_queue.len();
        state.cancel.cancel();
        state.shutdown().await;
        assert_eq!(replies, if enforcing { 0 } else { 10 });
    }
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn every_ipv6_fragment_checks_its_own_unfragmentable_options() {
    for (next_header, second_option) in [(0, 0x1e), (0, 0x40), (60, 0x1e), (60, 0x40)] {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        let mut datagram = Vec::new();
        etherparse::PacketBuilder::ipv6(
            "fd00::2".parse::<Ipv6Addr>().unwrap().octets(),
            "2001:db8::9".parse::<Ipv6Addr>().unwrap().octets(),
            64,
        )
        .udp(54100, 443)
        .write(&mut datagram, &[7; 24])
        .unwrap();
        let udp = &datagram[40..];
        for (index, part) in udp.chunks(24).enumerate() {
            let mut fragment = datagram[..40].to_vec();
            fragment[4..6].copy_from_slice(&u16::try_from(16 + part.len()).unwrap().to_be_bytes());
            fragment[6] = next_header; // This option header precedes Fragment in every packet.
            fragment.extend_from_slice(&[44, 0, if index == 0 { 0x1e } else { second_option }, 2, 0, 0, 0, 0]);
            fragment.extend_from_slice(&[17, 0]);
            fragment.extend_from_slice(&(if index == 0 { 1_u16 } else { 24_u16 }).to_be_bytes());
            fragment.extend_from_slice(&99_u32.to_be_bytes());
            fragment.extend_from_slice(part);
            route_tun_packet(&fragment, &mut state);
        }
        let admitted = state.udp_associations.len();
        state.cancel.cancel();
        state.shutdown().await;
        assert_eq!(admitted, usize::from(second_option != 0x40));
    }
}

/// # Cancel safety:
/// cancel-safe: routing does not suspend and shutdown owns all spawned workers.
#[tokio::test]
async fn zero_ipv6_payload_length_with_udp_data_is_rejected() {
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    let mut packet = ipv6_udp_extension(0, [1, 4, 0, 0, 0, 0]);
    packet[4..6].fill(0);
    route_tun_packet(&packet, &mut state);
    let admitted = state.udp_associations.len();
    state.cancel.cancel();
    state.shutdown().await;
    assert_eq!(admitted, 0, "ordinary IPv6 data cannot use an unimplemented jumbo encoding");
}
