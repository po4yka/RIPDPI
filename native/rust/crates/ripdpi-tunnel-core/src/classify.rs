use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use etherparse::{IpNumber, NetSlice, SlicedPacket, TransportSlice};

mod checksum;

#[derive(Debug)]
pub enum IpClass<'a> {
    TcpOrOther,
    Icmp,
    UdpDns { src: SocketAddr, dst: SocketAddr, payload: &'a [u8] },
    Udp { src: SocketAddr, dst: SocketAddr, payload: &'a [u8] },
}

/// Classify a raw IPv4 or IPv6 packet.
///
/// When `mapdns` is `None`, DNS interception is disabled and all UDP packets
/// are returned as `IpClass::Udp`. Otherwise IPv4 UDP to the MapDNS port is
/// `IpClass::UdpDns` when it targets the MapDNS network or a public address.
pub fn classify_ip_packet<'a>(pkt: &'a [u8], mapdns: Option<(u32, u32, u16)>) -> IpClass<'a> {
    classify_ip_packet_with_parse_status(pkt, mapdns).0
}

pub(crate) fn classify_ip_packet_with_parse_status<'a>(
    pkt: &'a [u8],
    mapdns: Option<(u32, u32, u16)>,
) -> (IpClass<'a>, bool) {
    let Ok(parsed) = SlicedPacket::from_ip(pkt) else {
        return (IpClass::TcpOrOther, false);
    };

    let Some(net) = parsed.net else {
        return (IpClass::TcpOrOther, false);
    };

    let valid = match parsed.transport.as_ref() {
        Some(TransportSlice::Udp(udp)) => checksum::udp_checksum_is_valid(&net, udp),
        _ => true,
    };
    (classify_parsed_packet(net, parsed.transport, mapdns), valid)
}

fn classify_parsed_packet<'a>(
    net: NetSlice<'a>,
    transport: Option<TransportSlice<'a>>,
    mapdns: Option<(u32, u32, u16)>,
) -> IpClass<'a> {
    let is_icmp = match &net {
        NetSlice::Ipv4(ipv4) => ipv4.payload_ip_number() == IpNumber::ICMP,
        NetSlice::Ipv6(ipv6) => ipv6.payload().ip_number == IpNumber::IPV6_ICMP,
        NetSlice::Arp(_) => false,
    };
    if is_icmp {
        return IpClass::Icmp;
    }

    let Some(transport) = transport else {
        return IpClass::TcpOrOther;
    };

    let udp = match transport {
        TransportSlice::Icmpv4(_) | TransportSlice::Icmpv6(_) | TransportSlice::Igmp(_) => {
            return IpClass::TcpOrOther;
        }
        TransportSlice::Udp(udp) => udp,
        TransportSlice::Tcp(_) => return IpClass::TcpOrOther,
    };

    let src_port = udp.source_port();
    let dst_port = udp.destination_port();
    let payload = udp.payload();

    match net {
        NetSlice::Ipv4(ipv4) => {
            let src_ip = ipv4.header().source_addr();
            let dst_ip = ipv4.header().destination_addr();
            let src = SocketAddr::new(IpAddr::V4(src_ip), src_port);
            let dst = SocketAddr::new(IpAddr::V4(dst_ip), dst_port);

            if let Some((mapdns_net, mapdns_mask, mapdns_port)) = mapdns
                && dst_port == mapdns_port
                && (u32::from(dst_ip) & mapdns_mask == mapdns_net || (is_public_ipv4(dst_ip) && is_dns_query(payload)))
            {
                return IpClass::UdpDns { src, dst, payload };
            }

            IpClass::Udp { src, dst, payload }
        }
        NetSlice::Ipv6(ipv6) => {
            let src_ip = ipv6.header().source_addr();
            let dst_ip = ipv6.header().destination_addr();

            IpClass::Udp {
                src: SocketAddr::new(IpAddr::V6(src_ip), src_port),
                dst: SocketAddr::new(IpAddr::V6(dst_ip), dst_port),
                payload,
            }
        }
        NetSlice::Arp(_) => IpClass::TcpOrOther,
    }
}

/// Whether DNS sent to `ip` would leave the device for a public resolver and must be intercepted.
pub(crate) fn is_public_ipv4(ip: Ipv4Addr) -> bool {
    let [a, b, ..] = ip.octets();
    // 100.64.0.0/10 is carrier-grade NAT; 198.18.0.0/15 is benchmarking and the default MapDNS synthetic range.
    let cgnat = a == 100 && (64..128).contains(&b);
    let benchmarking = a == 198 && (b & 0xFE) == 18;
    !(a == 0
        || a >= 240
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_multicast()
        || cgnat
        || benchmarking)
}

/// Whether `payload` is a DNS query with a well-formed first question, checked without allocating.
pub(crate) fn is_dns_query(payload: &[u8]) -> bool {
    const HEADER_LEN: usize = 12;
    const QR_AND_OPCODE: u8 = 0xF8;
    let Some(header) = payload.get(..HEADER_LEN) else {
        return false;
    };
    if header[2] & QR_AND_OPCODE != 0 || u16::from_be_bytes([header[4], header[5]]) == 0 {
        return false;
    }
    // A query carries no answer or authority records; random tunnel bytes almost never match.
    if header[6..10] != [0; 4] {
        return false;
    }
    let mut pos = HEADER_LEN;
    loop {
        let Some(&len) = payload.get(pos) else {
            return false;
        };
        pos += 1;
        if len == 0 {
            break;
        }
        // Compression pointers and reserved label types never start a question name.
        if len & 0xC0 != 0 || pos - HEADER_LEN + usize::from(len) > 255 {
            return false;
        }
        pos += usize::from(len);
    }
    // QTYPE and QCLASS follow the name.
    payload.len() >= pos + 4
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv6Addr;

    const MAPDNS_NET: u32 = 0xC612_0000;
    const MAPDNS_MASK: u32 = 0xFFFE_0000;
    const MAPDNS_PORT: u16 = 53;

    fn ipv4_udp(src_ip: [u8; 4], dst_ip: [u8; 4], src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let total_len = 20 + udp_len;
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x45;
        pkt[2] = (total_len >> 8) as u8;
        pkt[3] = total_len as u8;
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
        pkt[28..28 + payload.len()].copy_from_slice(payload);
        pkt
    }

    fn ipv4_tcp(src_ip: [u8; 4], dst_ip: [u8; 4], src_port: u16, dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[2] = 0;
        pkt[3] = 40;
        pkt[8] = 64;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x02;
        pkt[34] = 0xff;
        pkt[35] = 0xff;
        pkt
    }

    fn ipv4_igmp() -> Vec<u8> {
        let mut pkt = vec![0u8; 28];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 2;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[224, 0, 0, 1]);
        pkt[20] = 0x11;
        pkt[21] = 0x64;
        pkt
    }

    fn ipv6_udp(src_ip: Ipv6Addr, dst_ip: Ipv6Addr, src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let payload_len = udp_len as u16;
        let mut pkt = vec![0u8; 40 + udp_len];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&payload_len.to_be_bytes());
        pkt[6] = 17;
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&src_ip.octets());
        pkt[24..40].copy_from_slice(&dst_ip.octets());
        pkt[40..42].copy_from_slice(&src_port.to_be_bytes());
        pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
        pkt[44..46].copy_from_slice(&payload_len.to_be_bytes());
        pkt[48..48 + payload.len()].copy_from_slice(payload);
        let checksum = etherparse::UdpHeader {
            source_port: src_port,
            destination_port: dst_port,
            length: payload_len,
            checksum: 0,
        }
        .calc_checksum_ipv6_raw(src_ip.octets(), dst_ip.octets(), payload)
        .expect("fixture UDP checksum");
        pkt[46..48].copy_from_slice(&checksum.to_be_bytes());
        pkt
    }

    fn ipv4_fragment(protocol: u8, first_fragment: bool) -> Vec<u8> {
        let mut pkt = vec![0u8; 28];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
        pkt[6..8].copy_from_slice(&(if first_fragment { 0x2000_u16 } else { 0x0001_u16 }).to_be_bytes());
        pkt[8] = 64;
        pkt[9] = protocol;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        pkt
    }

    fn ipv6_fragment(next_header: u8, first_fragment: bool) -> Vec<u8> {
        let mut pkt = vec![0u8; 56];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&16u16.to_be_bytes());
        pkt[6] = 44; // Fragment extension header
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[40] = next_header;
        pkt[42..44].copy_from_slice(&(if first_fragment { 0x0001_u16 } else { 0x0008_u16 }).to_be_bytes());
        pkt
    }

    #[test]
    fn udp_to_mapdns_is_dns_when_enabled() {
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 54321, 53, b"query");
        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));
        assert!(matches!(class, IpClass::UdpDns { .. }));
    }

    #[test]
    fn udp_to_dns_is_plain_udp_when_intercept_disabled() {
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, b"query");
        let class = classify_ip_packet(&pkt, None);
        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn tcp_is_tcp_or_other() {
        let pkt = ipv4_tcp([10, 0, 0, 1], [1, 1, 1, 1], 12345, 80);
        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));
        assert!(matches!(class, IpClass::TcpOrOther));
    }

    #[test]
    fn igmp_is_tcp_or_other() {
        let pkt = ipv4_igmp();
        let class = classify_ip_packet(&pkt, None);
        assert!(matches!(class, IpClass::TcpOrOther));
    }

    #[test]
    fn wrong_port_does_not_trigger_dns_intercept() {
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 12345, 80, b"data");
        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));
        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn malformed_packet_is_tcp_or_other() {
        assert!(matches!(classify_ip_packet(&[0u8; 5], None), IpClass::TcpOrOther));
    }

    #[test]
    fn malformed_packet_reports_parse_failure_status_without_changing_legacy_class() {
        let (class, parsed_ip) = classify_ip_packet_with_parse_status(&[0u8; 5], None);

        assert!(matches!(class, IpClass::TcpOrOther));
        assert!(!parsed_ip);
    }

    #[test]
    fn ipv6_udp_is_classified_as_udp() {
        let pkt = ipv6_udp(Ipv6Addr::LOCALHOST, Ipv6Addr::LOCALHOST, 5353, 443, b"quic");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn ipv4_udp_extracts_correct_addresses_and_ports() {
        let pkt = ipv4_udp([192, 168, 1, 100], [10, 0, 0, 1], 54321, 8080, b"data");

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { src, dst, .. } => {
                assert_eq!(src.ip(), IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 1, 100)));
                assert_eq!(src.port(), 54321);
                assert_eq!(dst.ip(), IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 1)));
                assert_eq!(dst.port(), 8080);
            }
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn ipv6_udp_extracts_correct_addresses_and_ports() {
        let src_ip = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);
        let dst_ip = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 2);
        let pkt = ipv6_udp(src_ip, dst_ip, 9000, 443, b"quic");

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { src, dst, .. } => {
                assert_eq!(src.ip(), IpAddr::V6(src_ip));
                assert_eq!(src.port(), 9000);
                assert_eq!(dst.ip(), IpAddr::V6(dst_ip));
                assert_eq!(dst.port(), 443);
            }
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn udp_payload_is_preserved_zero_copy() {
        let payload = b"hello world dns query";
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, payload);

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { payload: p, .. } => assert_eq!(p, payload),
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn dns_intercept_payload_is_preserved() {
        let payload = b"\x00\x01dns query data";
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 54321, 53, payload);

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        match class {
            IpClass::UdpDns { payload: p, .. } => assert_eq!(p, payload),
            other => panic!("expected IpClass::UdpDns, got {other:?}"),
        }
    }

    #[test]
    fn empty_payload_udp_is_classified() {
        let pkt = ipv4_udp([10, 0, 0, 1], [10, 0, 0, 2], 1234, 5678, b"");

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { payload, .. } => assert!(payload.is_empty()),
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn empty_input_is_tcp_or_other() {
        assert!(matches!(classify_ip_packet(&[], None), IpClass::TcpOrOther));
    }

    #[test]
    fn ipv6_tcp_is_tcp_or_other() {
        // IPv6 TCP packet: version=6, next_header=6 (TCP)
        let mut pkt = vec![0u8; 60];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
        pkt[6] = 6; // TCP
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[52] = 0x50;
        pkt[53] = 0x02;

        assert!(matches!(classify_ip_packet(&pkt, None), IpClass::TcpOrOther));
    }

    #[test]
    fn ipv4_icmp_is_classified_as_icmp() {
        let mut pkt = vec![0u8; 28];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 1; // ICMP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);

        assert!(matches!(classify_ip_packet(&pkt, None), IpClass::Icmp));
    }

    #[test]
    fn ipv6_icmp_is_classified_as_icmp() {
        let mut pkt = vec![0u8; 48];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&8u16.to_be_bytes());
        pkt[6] = 58; // ICMPv6
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[40] = 128; // Echo Request

        assert!(matches!(classify_ip_packet(&pkt, None), IpClass::Icmp));
    }

    #[test]
    fn fragmented_icmp_is_classified_from_final_ip_protocol() {
        for pkt in [ipv4_fragment(1, true), ipv4_fragment(1, false), ipv6_fragment(58, true), ipv6_fragment(58, false)]
        {
            assert!(matches!(classify_ip_packet(&pkt, None), IpClass::Icmp));
        }
    }

    #[test]
    fn fragmented_tcp_and_udp_are_not_misclassified_as_icmp() {
        for pkt in [ipv4_fragment(6, true), ipv4_fragment(17, false), ipv6_fragment(6, true), ipv6_fragment(17, false)]
        {
            assert!(matches!(classify_ip_packet(&pkt, None), IpClass::TcpOrOther));
        }
    }

    #[test]
    fn wrong_netmask_does_not_trigger_dns_intercept() {
        // 172.16.0.1 is outside 198.18.0.0/15 (MAPDNS_NET/MAPDNS_MASK)
        let pkt = ipv4_udp([10, 0, 0, 1], [172, 16, 0, 1], 12345, 53, b"query");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn dns_intercept_requires_both_mask_and_port() {
        // Correct netmask range but wrong port
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 12345, 5353, b"query");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn dns_intercept_preserves_kernel_visible_tuple() {
        let pkt = ipv4_udp([10, 0, 0, 99], [198, 18, 0, 0], 54321, 53, b"q");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        match class {
            IpClass::UdpDns { src, dst, .. } => {
                assert_eq!(src.ip(), IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 99)));
                assert_eq!(src.port(), 54321);
                assert_eq!(dst.ip(), IpAddr::V4(std::net::Ipv4Addr::new(198, 18, 0, 0)));
                assert_eq!(dst.port(), 53);
            }
            other => panic!("expected IpClass::UdpDns, got {other:?}"),
        }
    }

    #[test]
    fn public_ipv4_excludes_local_and_special_ranges() {
        let not_public = [
            [0, 0, 0, 0],
            [0, 1, 2, 3],
            [240, 0, 0, 1],
            [254, 1, 2, 3],
            [127, 0, 0, 1],
            [10, 1, 2, 3],
            [172, 16, 0, 1],
            [172, 31, 255, 254],
            [192, 168, 1, 1],
            [169, 254, 0, 1],
            [100, 64, 0, 1],
            [100, 127, 255, 254],
            [224, 0, 0, 251],
            [239, 255, 255, 250],
            [255, 255, 255, 255],
            [198, 18, 0, 53],
            [198, 19, 255, 254],
        ];
        for octets in not_public {
            assert!(!is_public_ipv4(Ipv4Addr::from(octets)), "{octets:?} must not be public");
        }
        let public = [[8, 8, 8, 8], [1, 1, 1, 1], [9, 9, 9, 9], [172, 32, 0, 1], [100, 128, 0, 1], [198, 20, 0, 1]];
        for octets in public {
            assert!(is_public_ipv4(Ipv4Addr::from(octets)), "{octets:?} must be public");
        }
    }

    /// A standard A query for example.com.
    const DNS_QUERY: &[u8] = &[
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x', b'a', b'm', b'p',
        b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
    ];

    #[test]
    fn dns_query_check_accepts_only_well_formed_queries() {
        assert!(is_dns_query(DNS_QUERY));
        let mut response = DNS_QUERY.to_vec();
        response[2] |= 0x80;
        assert!(!is_dns_query(&response), "QR bit set");
        let mut no_question = DNS_QUERY.to_vec();
        no_question[5] = 0;
        assert!(!is_dns_query(&no_question), "QDCOUNT zero");
        assert!(!is_dns_query(&DNS_QUERY[..DNS_QUERY.len() - 1]), "truncated question");
        assert!(!is_dns_query(&DNS_QUERY[..11]), "truncated header");
        let mut pointer = DNS_QUERY[..12].to_vec();
        pointer.extend_from_slice(&[0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01]);
        assert!(!is_dns_query(&pointer), "compression pointer in question");
        assert!(!is_dns_query(&[0x45; 64]), "non-DNS datagram");
    }

    #[test]
    fn udp_to_public_dns_port_is_intercepted_when_enabled() {
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, DNS_QUERY);

        match classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT))) {
            IpClass::UdpDns { dst, .. } => assert_eq!(dst, "8.8.8.8:53".parse::<SocketAddr>().expect("addr")),
            other => panic!("expected IpClass::UdpDns, got {other:?}"),
        }
    }

    #[test]
    fn udp_to_private_dns_port_is_not_intercepted() {
        for dst in [[192, 168, 1, 1], [10, 0, 0, 2]] {
            let pkt = ipv4_udp([10, 0, 0, 1], dst, 12345, 53, DNS_QUERY);

            let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

            assert!(matches!(class, IpClass::Udp { .. }), "{dst:?}");
        }
    }

    #[test]
    fn udp_to_public_address_on_other_port_is_not_intercepted() {
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 443, DNS_QUERY);

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn non_dns_or_response_to_public_dns_port_stays_udp() {
        let mut response = DNS_QUERY.to_vec();
        response[2] |= 0x80;
        for payload in [&[0x01, 0x00, 0x00, 0x00, 0xAA, 0xBB, 0xCC, 0xDD][..], &[0u8; 148][..], &response[..]] {
            let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, payload);

            let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

            assert!(matches!(class, IpClass::Udp { .. }), "{payload:?}");
        }
    }

    #[test]
    fn synthetic_address_outside_exact_mapdns_host_is_not_intercepted() {
        // The runtime classifies with an exact-host mask; other 198.18.0.0/15 addresses are mapped hosts.
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 7], 12345, 53, DNS_QUERY);

        let class = classify_ip_packet(&pkt, Some((u32::from(Ipv4Addr::new(198, 18, 0, 10)), u32::MAX, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }
}

#[cfg(test)]
mod checksum_tests;
