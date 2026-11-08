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
