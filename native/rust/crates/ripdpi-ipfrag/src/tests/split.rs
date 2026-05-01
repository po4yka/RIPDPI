use super::support::*;

#[test]
fn tcp_fragment_pair_rounds_payload_split_up_to_next_ip_boundary() {
    let spec = TcpFragmentSpec {
        src: SocketAddr::from(([203, 0, 113, 10], 50000)),
        dst: SocketAddr::from(([198, 51, 100, 20], 443)),
        ttl: 64,
        identification: 0x9988,
        sequence_number: 0x0102_0304,
        acknowledgment_number: 0x0506_0708,
        window_size: 4096,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"fragmented tls client hello";

    let pair = build_tcp_fragment_pair(spec, payload, 1).expect("build aligned tcp fragment pair");
    let (second_header, _) = Ipv4Header::from_slice(&pair.second).expect("parse second ipv4 header");

    assert_eq!(pair.effective_transport_split, 24);
    assert_eq!(second_header.fragment_offset.byte_offset() as usize, 24);
}

#[test]
fn degenerate_fragment_pair_is_rejected_after_alignment() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([192, 0, 2, 10], 40000)),
        dst: SocketAddr::from(([198, 51, 100, 20], 443)),
        ttl: 64,
        identification: 7,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"tiny";

    let err = build_udp_fragment_pair(spec, payload, 16).expect_err("reject degenerate udp fragment pair");
    assert!(matches!(err, BuildError::InvalidSplit { .. }));
}
