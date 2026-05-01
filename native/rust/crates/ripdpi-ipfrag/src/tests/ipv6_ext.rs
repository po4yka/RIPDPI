use super::support::*;

#[test]
fn ipv6_hop_by_hop_extension_header_is_inserted_before_fragment() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0xAABB,
        ipv6_ext: Ipv6ExtHeaders { hop_by_hop: true, ..Ipv6ExtHeaders::default() },
    };
    let payload = b"hello over fragmented udp with hbh";

    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with hop-by-hop");
    let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse ipv6");

    // IPv6 next_header should be HOPOPTS (0), not IPV6_FRAG (44)
    assert_eq!(first_base.next_header, IpNumber(0));

    // HBH header's next_header should be IPV6_FRAG (44)
    assert_eq!(first_rest[0], 44);
    assert_eq!(first_rest[1], 0); // hdr_ext_len = 0 -> 8 bytes

    // Fragment header follows at offset 8
    let (frag, _) = Ipv6FragmentHeader::from_slice(&first_rest[8..]).expect("parse fragment header");
    assert_eq!(frag.next_header, ip_number::UDP);

    // Reassembly still works
    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
    assert_eq!(udp_payload, payload);
}

#[test]
fn ipv6_dest_opt_unfragmentable_is_inserted_before_fragment() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0xCCDD,
        ipv6_ext: Ipv6ExtHeaders { dest_opt: true, ..Ipv6ExtHeaders::default() },
    };
    let payload = b"hello with dest opt unfrag";

    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with dest_opt");
    let (first_base, _) = Ipv6Header::from_slice(&pair.first).expect("parse ipv6");
    assert_eq!(first_base.next_header, IpNumber(60)); // DSTOPTS

    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
    assert_eq!(udp_payload, payload);
}

#[test]
fn ipv6_multiple_extension_headers_chain_correctly() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0xEEFF,
        ipv6_ext: Ipv6ExtHeaders { hop_by_hop: true, dest_opt: true, routing: true, ..Ipv6ExtHeaders::default() },
    };
    let payload = b"hello with all unfrag extensions";

    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with all extensions");
    let (first_base, rest) = Ipv6Header::from_slice(&pair.first).expect("parse ipv6");

    // Chain: IPv6(next=0) -> HBH(next=60) -> DestOpt(next=43) -> Routing(next=44) -> Frag(next=17)
    assert_eq!(first_base.next_header, IpNumber(0)); // HOPOPTS
    assert_eq!(rest[0], 60); // HBH -> DSTOPTS
    assert_eq!(rest[8], 43); // DSTOPTS -> ROUTING
    assert_eq!(rest[16], 44); // ROUTING -> IPV6_FRAG

    let (frag, _) = Ipv6FragmentHeader::from_slice(&rest[24..]).expect("parse frag header");
    assert_eq!(frag.next_header, ip_number::UDP);

    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
    assert_eq!(udp_payload, payload);
}

#[test]
fn ipv6_second_frag_next_override_forges_protocol() {
    let spec = UdpFragmentSpec {
        src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
        dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
        ttl: 48,
        identification: 0x5678,
        ipv6_ext: Ipv6ExtHeaders { second_frag_next_override: Some(6), ..Ipv6ExtHeaders::default() }, // forge as TCP
    };
    let payload = b"hello with forged next header";

    let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with forged next");

    // First fragment's Fragment Header should have correct next_header (UDP=17)
    let (_, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6");
    let (first_frag, _) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first frag");
    assert_eq!(first_frag.next_header, ip_number::UDP);

    // Second fragment's Fragment Header should have forged next_header (TCP=6)
    let (_, second_rest) = Ipv6Header::from_slice(&pair.second).expect("parse second ipv6");
    let (second_frag, _) = Ipv6FragmentHeader::from_slice(second_rest).expect("parse second frag");
    assert_eq!(second_frag.next_header, IpNumber(6)); // Forged as TCP

    // Reassembly still produces valid UDP (OS uses first frag's next_header)
    let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
    let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
    assert_eq!(udp_payload, payload);
}
