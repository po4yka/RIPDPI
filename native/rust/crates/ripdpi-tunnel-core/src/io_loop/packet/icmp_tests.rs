use super::*;

// Independent Scapy 2.6.1 vectors from the VPN-04 audit; header values differ from serializer defaults.
const ORIGINALS: [&str; 5] = [
    "45b8002c432140002511cbdc0a000002cb007109cf0801bb00181a1830313233343536373839414243444546",
    "46000030432100004011ee8f0a000002cb00710901010100cf0801bb00181a1830313233343536373839414243444546",
    "4500002c00010000401134b50a000002cb007109cf0801bb0018000030313233343536373839414243444546",
    "6b81234500181125fd00000000000000000000000000000220010db8000000000000000000000009cf0801bb0018355f30313233343536373839414243444546",
    "6000000000203c40fd00000000000000000000000000000220010db80000000000000000000000091100010400000000cf0801bb0018355f30313233343536373839414243444546",
];

fn assert_checksums(packet: &[u8], source: IpAddr, destination: IpAddr) {
    match (source, destination) {
        (IpAddr::V4(_), IpAddr::V4(_)) => {
            assert!(smoltcp::wire::Ipv4Packet::new_checked(packet).unwrap().verify_checksum());
            assert!(smoltcp::wire::Icmpv4Packet::new_checked(&packet[20..]).unwrap().verify_checksum());
        }
        (IpAddr::V6(source), IpAddr::V6(destination)) => {
            assert!(
                smoltcp::wire::Icmpv6Packet::new_checked(&packet[40..]).unwrap().verify_checksum(&source, &destination)
            );
        }
        _ => panic!("same family"),
    }
}

#[test]
fn quotes_preserve_original_fields_options_extensions_and_zero_udp_checksum() {
    for original in ORIGINALS.map(|input| hex::decode(input).unwrap()) {
        let quote = UdpErrorQuote::capture(&original).expect("valid independent input");
        let response = quote.build(quote.destination(), UdpErrorReason::PortUnreachable).unwrap();
        let offset = if original[0] >> 4 == 4 { 28 } else { 48 };
        assert_eq!(&response[offset..], &original[..response.len() - offset]);
        assert_checksums(&response, quote.destination(), quote.source());
    }
}

#[test]
fn local_error_source_and_codes_do_not_claim_remote_port_failure() {
    for input in [ORIGINALS[0], ORIGINALS[3]] {
        let original = hex::decode(input).unwrap();
        let quote = UdpErrorQuote::capture(&original).unwrap();
        let source: IpAddr = if quote.source().is_ipv4() { "10.0.0.1" } else { "fd00::1" }.parse().unwrap();
        let response = quote.build(source, UdpErrorReason::LocalForwardingFailure).unwrap();
        let offset = if source.is_ipv4() { 20 } else { 40 };
        assert_eq!(&response[offset..offset + 2], if source.is_ipv4() { &[3, 1] } else { &[1, 3] });
        assert_checksums(&response, source, quote.source());
        let other: IpAddr = if source.is_ipv4() { "fd00::1" } else { "10.0.0.1" }.parse().unwrap();
        assert!(quote.build(other, UdpErrorReason::LocalForwardingFailure).is_none());
    }
}

#[test]
fn truncated_lengths_corrupt_checksums_and_zero_udp_length_are_rejected() {
    for input in [ORIGINALS[0], ORIGINALS[3]] {
        let original = hex::decode(input).unwrap();
        for length in 0..original.len() {
            assert!(UdpErrorQuote::capture(&original[..length]).is_none());
        }
        let udp_offset = if original[0] >> 4 == 4 { 20 } else { 40 };
        let mut bad = original.clone();
        bad[udp_offset + 4..udp_offset + 6].fill(0);
        assert!(UdpErrorQuote::capture(&bad).is_none());
        let mut bad = original.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert!(UdpErrorQuote::capture(&bad).is_none());
    }
    let mut bad_header = hex::decode(ORIGINALS[0]).unwrap();
    bad_header[1] ^= 1;
    assert!(UdpErrorQuote::capture(&bad_header).is_none());
    let mut zero_ipv6_checksum = hex::decode(ORIGINALS[3]).unwrap();
    zero_ipv6_checksum[46..48].fill(0);
    assert!(UdpErrorQuote::capture(&zero_ipv6_checksum).is_none());
}

#[test]
fn capture_excludes_trailing_tun_padding() {
    for input in [ORIGINALS[0], ORIGINALS[3]] {
        let original = hex::decode(input).unwrap();
        let mut padded = original.clone();
        padded.extend_from_slice(&[0xaa; 128]);
        let response = build_udp_port_unreachable(&padded);
        let offset = if original[0] >> 4 == 4 { 28 } else { 48 };
        assert_eq!(&response[offset..], &original[..response.len() - offset]);
    }
}

#[test]
fn forbidden_addresses_and_ipv6_routing_headers_do_not_originate_errors() {
    for (source, destination) in [
        ([0, 0, 0, 0], [203, 0, 113, 9]),
        ([10, 0, 0, 2], [224, 0, 0, 1]),
        ([224, 0, 0, 1], [10, 0, 0, 2]),
        ([10, 0, 0, 2], [255; 4]),
    ] {
        let mut input = Vec::new();
        PacketBuilder::ipv4(source, destination, 64).udp(53000, 443).write(&mut input, b"test").unwrap();
        assert!(UdpErrorQuote::capture(&input).is_none());
    }
    for (source, destination) in [("::", "2001:db8::9"), ("fd00::2", "ff02::1"), ("ff02::1", "fd00::2")] {
        let source: std::net::Ipv6Addr = source.parse().unwrap();
        let destination: std::net::Ipv6Addr = destination.parse().unwrap();
        let mut input = Vec::new();
        PacketBuilder::ipv6(source.octets(), destination.octets(), 64)
            .udp(53000, 443)
            .write(&mut input, b"test")
            .unwrap();
        assert!(UdpErrorQuote::capture(&input).is_none());
    }
    let mut routing = hex::decode(ORIGINALS[4]).unwrap();
    routing[6] = 43;
    assert!(UdpErrorQuote::capture(&routing).is_none());
}

#[test]
fn fragments_and_invalid_outer_sources_are_rejected() {
    let original = hex::decode(ORIGINALS[0]).unwrap();
    let quote = UdpErrorQuote::capture(&original).unwrap();
    for source in ["0.0.0.0", "224.0.0.1", "255.255.255.255"] {
        assert!(quote.build(source.parse().unwrap(), UdpErrorReason::LocalForwardingFailure).is_none());
    }
    let mut header = etherparse::Ipv4HeaderSlice::from_slice(&original).unwrap().to_header();
    header.more_fragments = true;
    let mut fragment = Vec::new();
    header.write(&mut fragment).unwrap();
    fragment.extend_from_slice(&original[20..]);
    assert!(UdpErrorQuote::capture(&fragment).is_none());
    let original = hex::decode(ORIGINALS[3]).unwrap();
    let quote = UdpErrorQuote::capture(&original).unwrap();
    for source in ["::", "ff02::1"] {
        assert!(quote.build(source.parse().unwrap(), UdpErrorReason::LocalForwardingFailure).is_none());
    }
    let mut fragment = original[..40].to_vec();
    fragment[4..6].copy_from_slice(&32u16.to_be_bytes());
    fragment[6] = 44;
    fragment.extend_from_slice(&[17, 0, 0, 1, 0x12, 0x34, 0x56, 0x78]);
    fragment.extend_from_slice(&original[40..]);
    assert!(UdpErrorQuote::capture(&fragment).is_none());
}

#[test]
fn ipv6_errors_quote_up_to_minimum_mtu_budget() {
    for original_len in [48usize, 49, 1231, 1232, 1233, 65575] {
        let source: std::net::Ipv6Addr = "fd00::2".parse().unwrap();
        let destination: std::net::Ipv6Addr = "2001:db8::9".parse().unwrap();
        let mut original = Vec::new();
        PacketBuilder::ipv6(source.octets(), destination.octets(), 37)
            .udp(53000, 443)
            .write(&mut original, &vec![0xa5; original_len - 48])
            .unwrap();
        let quote = UdpErrorQuote::capture(&original).unwrap();
        let response = quote.build(destination.into(), UdpErrorReason::PortUnreachable).unwrap();
        assert_eq!(response.len(), 48 + original_len.min(1232));
        assert_eq!(&response[48..], &original[..original_len.min(1232)]);
        assert_checksums(&response, destination.into(), source.into());
    }
}

#[test]
fn remote_types_codes_mtu_and_original_quotes_have_independent_checksums() {
    use ripdpi_socks5_core::udp_feedback::RemoteError;
    for (input, source, cases) in [
        (ORIGINALS[1], "192.0.2.1", vec![(3, 3, 0), (3, 4, 1400), (3, 13, 0), (11, 0, 0)]),
        (ORIGINALS[4], "2001:db8::1", vec![(1, 4, 0), (1, 1, 0), (2, 0, 1200), (3, 1, 0)]),
    ] {
        let original = hex::decode(input).unwrap();
        let quote = UdpErrorQuote::capture(&original).unwrap();
        let source: IpAddr = source.parse().unwrap();
        let offset = if source.is_ipv4() { 20 } else { 40 };
        for (icmp_type, icmp_code, info) in cases {
            let error = RemoteError {
                source,
                target: std::net::SocketAddr::new(quote.destination(), 443),
                icmp_type,
                icmp_code,
                info,
            };
            let raw = quote.build_remote(&error).unwrap();
            assert_eq!(&raw[offset..offset + 2], &[icmp_type, icmp_code]);
            assert_eq!(&raw[offset + 4..offset + 8], &info.to_be_bytes());
            assert_eq!(&raw[offset + 8..], &original[..raw.len() - offset - 8]);
            assert_checksums(&raw, source, quote.source());
        }
        let invalid = RemoteError {
            source,
            target: std::net::SocketAddr::new(quote.destination(), 443),
            icmp_type: if source.is_ipv4() { 12 } else { 4 },
            icmp_code: 0,
            info: 0,
        };
        assert!(quote.build_remote(&invalid).is_none());
    }
}
