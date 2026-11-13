use super::support::*;

fn tcp_spec(ipv6: bool, timestamp: bool, ipv6_ext: Ipv6ExtHeaders) -> TcpFragmentSpec {
    TcpFragmentSpec {
        src: if ipv6 { "[2001:db8::1]:50000" } else { "192.0.2.1:50000" }.parse().unwrap(),
        dst: if ipv6 { "[2001:db8::2]:443" } else { "198.51.100.2:443" }.parse().unwrap(),
        ttl: 64,
        identification: 7,
        sequence_number: 1,
        acknowledgment_number: 2,
        window_size: 4096,
        timestamp: timestamp.then_some(TcpTimestampOption { value: 3, echo_reply: 4 }),
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext,
    }
}

#[test]
fn tcp_fragment_pair_checks_reassembled_ipv4_length() {
    for (timestamp, max_payload) in [(false, 65495), (true, 65483)] {
        let spec = tcp_spec(false, timestamp, Ipv6ExtHeaders::default());
        let pair = build_tcp_fragment_pair(spec, &vec![0x41; max_payload], 1).expect("maximum IPv4 packet");
        let first_header_len = usize::from(pair.first[0] & 15) * 4;
        let second_offset = usize::from(u16::from_be_bytes([pair.second[6], pair.second[7]]) & 0x1fff) * 8;
        let second_header_len = usize::from(pair.second[0] & 15) * 4;
        assert_eq!(first_header_len + second_offset + pair.second.len() - second_header_len, 65535);
        assert!(
            matches!(build_tcp_fragment_pair(spec, &vec![0x41; max_payload + 1], 1), Err(BuildError::ValueTooLarge)),
            "the reassembled IPv4 header and transport must fit Total Length"
        );
    }
}

#[test]
fn tcp_fragment_pair_checks_reassembled_ipv6_payload_length() {
    let profiles = [
        (Ipv6ExtHeaders::default(), 0),
        (Ipv6ExtHeaders { hop_by_hop: true, ..Default::default() }, 8),
        (Ipv6ExtHeaders { dest_opt: true, ..Default::default() }, 8),
        (Ipv6ExtHeaders { hop_by_hop: true, dest_opt: true, ..Default::default() }, 16),
        (Ipv6ExtHeaders { hop_by_hop: true, dest_opt_fragmentable: true, ..Default::default() }, 16),
    ];
    for (profile, extension_len) in profiles {
        for (timestamp, tcp_header_len) in [(false, 20), (true, 32)] {
            let spec = tcp_spec(true, timestamp, profile);
            let max_payload = 65535 - extension_len - tcp_header_len;
            let pair = build_tcp_fragment_pair(spec, &vec![0x41; max_payload], 1).expect("maximum IPv6 packet");
            let unfragmentable_len = if profile.hop_by_hop { 8 } else { 0 } + if profile.dest_opt { 8 } else { 0 };
            let fragment_header = 40 + unfragmentable_len;
            let offset = usize::from(
                u16::from_be_bytes([pair.second[fragment_header + 2], pair.second[fragment_header + 3]]) & 0xfff8,
            );
            assert_eq!(unfragmentable_len + offset + pair.second.len() - fragment_header - 8, 65535);
            assert!(
                matches!(
                    build_tcp_fragment_pair(spec, &vec![0x41; max_payload + 1], 1),
                    Err(BuildError::ValueTooLarge)
                ),
                "the reassembled IPv6 payload includes every extension header"
            );
        }
    }
}
