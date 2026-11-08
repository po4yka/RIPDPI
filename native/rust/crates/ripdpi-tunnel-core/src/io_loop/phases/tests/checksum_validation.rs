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
