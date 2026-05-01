use super::*;
use std::net::SocketAddr;

use etherparse::{
    ip_number, IpNumber, Ipv4Header, Ipv4HeaderSlice, Ipv6FragmentHeader, Ipv6Header, TcpHeader, TcpOptionElement,
    UdpHeader,
};

use crate::split::IP_FRAGMENT_ALIGNMENT_BYTES;

fn reassemble_ipv4_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
    let first_header = Ipv4HeaderSlice::from_slice(first).expect("parse first ipv4 header");
    let second_header = Ipv4HeaderSlice::from_slice(second).expect("parse second ipv4 header");
    let mut transport = Vec::new();
    transport.extend_from_slice(&first[first_header.slice().len()..]);
    transport.extend_from_slice(&second[second_header.slice().len()..]);
    transport
}

/// Skip over any extension headers between the IPv6 base header and the Fragment Header.
fn skip_to_fragment_header(mut data: &[u8], mut next_header: IpNumber) -> (&[u8], IpNumber) {
    // Walk extension headers until we find the Fragment Header (44).
    while next_header != ip_number::IPV6_FRAG {
        // All extension headers have: next_header(1) + hdr_ext_len(1) + data
        assert!(data.len() >= 2, "extension header too short");
        let nh = IpNumber(data[0]);
        let hdr_len = (usize::from(data[1]) + 1) * 8;
        assert!(data.len() >= hdr_len, "extension header length exceeds data");
        data = &data[hdr_len..];
        next_header = nh;
    }
    (data, next_header)
}

fn reassemble_ipv6_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
    let (first_base, first_rest) = Ipv6Header::from_slice(first).expect("parse first ipv6 header");
    let (first_rest, _) = skip_to_fragment_header(first_rest, first_base.next_header);
    let (_first_frag, first_payload) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");

    let (second_base, second_rest) = Ipv6Header::from_slice(second).expect("parse second ipv6 header");
    let (second_rest, _) = skip_to_fragment_header(second_rest, second_base.next_header);
    let (_second_frag, second_payload) =
        Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");
    assert_eq!(first_base.destination, second_base.destination);

    let mut transport = Vec::new();
    transport.extend_from_slice(first_payload);
    transport.extend_from_slice(second_payload);
    transport
}

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

#[test]
fn build_fake_rst_packet_ipv4_has_rst_flag_and_correct_seq() {
    let spec = TcpFragmentSpec {
        src: "1.2.3.4:12345".parse().unwrap(),
        dst: "5.6.7.8:443".parse().unwrap(),
        ttl: 3,
        identification: 0x1234,
        sequence_number: 1000,
        acknowledgment_number: 2000,
        window_size: 0,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let packet = build_fake_rst_packet(&spec).expect("build fake rst");
    let (ip_header, remaining) = Ipv4Header::from_slice(&packet).expect("parse ipv4");
    assert_eq!(ip_header.time_to_live, 3);
    assert!(ip_header.dont_fragment);
    let (tcp_header, payload) = TcpHeader::from_slice(remaining).expect("parse tcp");
    assert!(tcp_header.rst);
    assert!(tcp_header.ack);
    assert_eq!(tcp_header.sequence_number, 1000);
    assert_eq!(tcp_header.acknowledgment_number, 2000);
    assert_eq!(tcp_header.window_size, 0);
    assert!(payload.is_empty());
}

#[test]
fn build_fake_rst_packet_ipv6_has_rst_flag() {
    let spec = TcpFragmentSpec {
        src: "[::1]:12345".parse().unwrap(),
        dst: "[::2]:443".parse().unwrap(),
        ttl: 5,
        identification: 0,
        sequence_number: 3000,
        acknowledgment_number: 4000,
        window_size: 0,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let packet = build_fake_rst_packet(&spec).expect("build fake rst v6");
    let (ip_header, _) = Ipv6Header::from_slice(&packet).expect("parse ipv6");
    assert_eq!(ip_header.hop_limit, 5);
    let tcp_offset = Ipv6Header::LEN;
    let (tcp_header, payload) = TcpHeader::from_slice(&packet[tcp_offset..]).expect("parse tcp");
    assert!(tcp_header.rst);
    assert!(tcp_header.ack);
    assert_eq!(tcp_header.sequence_number, 3000);
    assert_eq!(tcp_header.acknowledgment_number, 4000);
    assert!(payload.is_empty());
}

#[test]
fn build_fake_rst_packet_rejects_mixed_address_families() {
    let spec = TcpFragmentSpec {
        src: "1.2.3.4:12345".parse().unwrap(),
        dst: "[::2]:443".parse().unwrap(),
        ttl: 3,
        identification: 0,
        sequence_number: 0,
        acknowledgment_number: 0,
        window_size: 0,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    assert_eq!(build_fake_rst_packet(&spec).unwrap_err(), BuildError::AddressFamilyMismatch);
}
