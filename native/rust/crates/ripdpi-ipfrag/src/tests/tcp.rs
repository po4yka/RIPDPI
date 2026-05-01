use super::support::*;

#[test]
fn tcp_ipv4_fragment_pair_preserves_sequence_ack_and_checksum() {
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

    let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv4 fragments");
    assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);
    assert!(pair.effective_transport_split > TcpHeader::MIN_LEN);

    let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
    let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
    assert_eq!(tcp.sequence_number, spec.sequence_number);
    assert_eq!(tcp.acknowledgment_number, spec.acknowledgment_number);
    assert!(tcp.ack);
    assert!(tcp.psh);
    assert_eq!(tcp_payload, payload);
    assert_eq!(
        tcp.checksum,
        tcp.calc_checksum_ipv4_raw([203, 0, 113, 10], [198, 51, 100, 20], tcp_payload)
            .expect("recalculate tcp checksum")
    );
}

#[test]
fn tcp_ipv6_fragment_pair_preserves_sequence_ack_and_checksum() {
    let spec = TcpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 50000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0x1020_3040,
        sequence_number: 0x0102_0304,
        acknowledgment_number: 0x0506_0708,
        window_size: 4096,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"fragmented tls client hello over ipv6";

    let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv6 fragments");
    let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6 header");
    let (first_fragment, _) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");

    assert_eq!(first_base.next_header, ip_number::IPV6_FRAG);
    assert_eq!(first_fragment.next_header, ip_number::TCP);
    assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);

    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse reassembled tcp transport");
    assert_eq!(tcp.sequence_number, spec.sequence_number);
    assert_eq!(tcp.acknowledgment_number, spec.acknowledgment_number);
    assert!(tcp.ack);
    assert!(tcp.psh);
    assert_eq!(tcp_payload, payload);
    assert_eq!(
        tcp.checksum,
        tcp.calc_checksum_ipv6_raw(
            [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
            [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2],
            tcp_payload
        )
        .expect("recalculate ipv6 tcp checksum")
    );
}

#[test]
fn tcp_ipv4_fragment_pair_serializes_timestamp_option_when_requested() {
    let spec = TcpFragmentSpec {
        src: SocketAddr::from(([203, 0, 113, 10], 50000)),
        dst: SocketAddr::from(([198, 51, 100, 20], 443)),
        ttl: 64,
        identification: 0x1111,
        sequence_number: 0x0102_0304,
        acknowledgment_number: 0x0506_0708,
        window_size: 4096,
        timestamp: Some(TcpTimestampOption { value: 0x1122_3344, echo_reply: 0 }),
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"timestamped payload";

    let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv4 fragments with timestamp");
    assert_eq!(pair.effective_transport_split, 40);

    let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
    let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
    let options = tcp.options_iterator().collect::<Vec<_>>();

    assert_eq!(tcp.header_len(), 32);
    assert_eq!(
        options,
        vec![Ok(TcpOptionElement::Noop), Ok(TcpOptionElement::Noop), Ok(TcpOptionElement::Timestamp(0x1122_3344, 0)),]
    );
    assert_eq!(tcp_payload, payload);
}

#[test]
fn tcp_ipv6_fragment_pair_serializes_timestamp_option_when_requested() {
    let spec = TcpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 50000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0x1020_3040,
        sequence_number: 0x0102_0304,
        acknowledgment_number: 0x0506_0708,
        window_size: 4096,
        timestamp: Some(TcpTimestampOption { value: 0x5566_7788, echo_reply: 0 }),
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"ipv6 timestamped payload";

    let pair = build_tcp_fragment_pair(spec, payload, 3).expect("build tcp ipv6 fragments with timestamp");
    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
    let options = tcp.options_iterator().collect::<Vec<_>>();

    assert_eq!(tcp.header_len(), 32);
    assert_eq!(
        options,
        vec![Ok(TcpOptionElement::Noop), Ok(TcpOptionElement::Noop), Ok(TcpOptionElement::Timestamp(0x5566_7788, 0)),]
    );
    assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);
    assert_eq!(tcp_payload, payload);
}
