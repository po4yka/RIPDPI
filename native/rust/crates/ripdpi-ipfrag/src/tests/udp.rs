use super::support::*;

#[test]
fn udp_ipv4_fragment_pair_clears_df_and_preserves_udp_checksum() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([192, 0, 2, 10], 40000)),
        dst: SocketAddr::from(([198, 51, 100, 20], 443)),
        ttl: 64,
        identification: 0x1234,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"quic initial payload";

    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build udp ipv4 fragments");
    assert_eq!(pair.effective_transport_split, 8);

    let (first_header, _) = Ipv4Header::from_slice(&pair.first).expect("parse first ipv4 header");
    let (second_header, _) = Ipv4Header::from_slice(&pair.second).expect("parse second ipv4 header");
    assert!(!first_header.dont_fragment);
    assert!(first_header.more_fragments);
    assert_eq!(u16::from(first_header.fragment_offset), 0);
    assert!(!second_header.more_fragments);
    assert_eq!(second_header.fragment_offset.byte_offset() as usize, 8);
    assert_eq!(first_header.header_checksum, first_header.calc_header_checksum());
    assert_eq!(second_header.header_checksum, second_header.calc_header_checksum());

    let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
    let (udp, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp transport");
    assert_eq!(udp.source_port, 40000);
    assert_eq!(udp.destination_port, 443);
    assert_eq!(udp_payload, payload);
    assert_eq!(
        udp.checksum,
        udp.calc_checksum_ipv4_raw([192, 0, 2, 10], [198, 51, 100, 20], udp_payload).expect("recalculate udp checksum")
    );
}

#[test]
fn udp_ipv6_fragment_pair_adds_fragment_header() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0x1020_3040,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let payload = b"hello over fragmented udp";

    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build udp ipv6 fragments");
    let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6 header");
    let (first_fragment, first_payload) =
        Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");
    let (second_base, second_rest) = Ipv6Header::from_slice(&pair.second).expect("parse second ipv6 header");
    let (second_fragment, second_payload) =
        Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");

    assert_eq!(first_base.next_header, ip_number::IPV6_FRAG);
    assert_eq!(second_base.next_header, ip_number::IPV6_FRAG);
    assert_eq!(first_fragment.next_header, ip_number::UDP);
    assert!(first_fragment.more_fragments);
    assert_eq!(u16::from(first_fragment.fragment_offset), 0);
    assert_eq!(second_fragment.fragment_offset.byte_offset() as usize, pair.effective_transport_split);
    assert!(!second_fragment.more_fragments);
    assert_eq!(first_payload.len(), pair.effective_transport_split);
    assert!(!second_payload.is_empty());

    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (udp, udp_payload) = UdpHeader::from_slice(&transport).expect("parse reassembled udp transport");
    assert_eq!(udp_payload, payload);
    assert_eq!(
        udp.checksum,
        udp.calc_checksum_ipv6_raw(
            [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
            [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2],
            udp_payload
        )
        .expect("recalculate ipv6 udp checksum")
    );
}

#[test]
fn ipv4_ignores_ipv6_ext_headers() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([192, 0, 2, 10], 40000)),
        dst: SocketAddr::from(([198, 51, 100, 20], 443)),
        ttl: 64,
        identification: 0x4321,
        ipv6_ext: Ipv6ExtHeaders {
            hop_by_hop: true,
            dest_opt: true,
            second_frag_next_override: Some(6),
            ..Ipv6ExtHeaders::default()
        },
    };
    let payload = b"ipv4 ignores ipv6 extensions";

    // Should succeed and produce standard IPv4 fragments
    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build ipv4 ignoring v6 ext");
    let (first_header, _) = Ipv4Header::from_slice(&pair.first).expect("parse ipv4");
    assert!(!first_header.dont_fragment);
    assert!(first_header.more_fragments);

    let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
    let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
    assert_eq!(udp_payload, payload);
}
