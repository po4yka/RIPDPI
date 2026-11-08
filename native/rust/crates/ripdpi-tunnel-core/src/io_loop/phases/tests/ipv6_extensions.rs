use super::*;

// Scapy 2.6.1 packets with the same checked TCP SYN behind different extension chains.
const INPUTS: [&str; 5] = [
    "6000000000140640fd00000000000000000000000000000220010db80000000000000000000000099c4001bb000000640000000050022000c6be0000",
    "60000000001c0040fd00000000000000000000000000000220010db800000000000000000000000906000104000000009c4001bb000000640000000050022000c6be0000",
    "60000000001c3c40fd00000000000000000000000000000220010db800000000000000000000000906000104000000009c4001bb000000640000000050022000c6be0000",
    "60000000001c2c40fd00000000000000000000000000000220010db800000000000000000000000906000000000000079c4001bb000000640000000050022000c6be0000",
    "6000000000240040fd00000000000000000000000000000220010db80000000000000000000000093c0001040000000006000104000000009c4001bb000000640000000050022000c6be0000",
];

#[test]
fn ipv6_tcp_safe_extensions_reach_the_same_listener() {
    for input in INPUTS {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.iface.set_any_ip(true);
        state.iface.update_ip_addrs(|addresses| {
            addresses.push("fd00::1/64".parse().unwrap()).unwrap();
        });
        state.iface.routes_mut().add_default_ipv6_route("fd00::1".parse().unwrap()).unwrap();
        let packet = hex::decode(input).unwrap();
        route_tun_packet(&packet, &mut state);
        poll_smoltcp(&mut state);
        let response = state.device.tx_queue.pop_front().expect("SYN must produce a response");
        assert_eq!(response[6], 6, "SYN must produce TCP, not an ICMPv6 extension error");
        assert_eq!(response[53] & 0x12, 0x12, "SYNACK");
    }
}

#[test]
fn zero_ipv6_payload_length_with_tcp_data_is_rejected() {
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.iface.set_any_ip(true);
    state.iface.update_ip_addrs(|addresses| addresses.push("fd00::1/64".parse().unwrap()).unwrap());
    let mut packet = hex::decode(INPUTS[1]).unwrap();
    packet[4..6].fill(0);
    route_tun_packet(&packet, &mut state);
    poll_smoltcp(&mut state);
    assert!(state.device.tx_queue.is_empty(), "normalization must not turn a zero length into a valid TCP SYN");
    assert!(state.pending_listens.is_empty());
}
