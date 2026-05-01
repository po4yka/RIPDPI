use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use super::*;

fn ipv4_tcp_rst(ip_id: u16) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45; // IPv4, IHL=5
    pkt[3] = 40; // total length
    pkt[4] = (ip_id >> 8) as u8; // IP ID high
    pkt[5] = (ip_id & 0xFF) as u8; // IP ID low
    pkt[8] = 64; // TTL
    pkt[9] = 6; // TCP
    pkt[12..16].copy_from_slice(&[10, 0, 0, 1]); // src IP
    pkt[16..20].copy_from_slice(&[10, 0, 0, 2]); // dst IP
    pkt[32] = 0x50; // TCP data offset = 5
    pkt[33] = 0x04; // RST flag
    pkt
}

fn ipv4_tcp_syn() -> Vec<u8> {
    ipv4_tcp_syn_with_ports(12345, 443)
}

fn ipv4_tcp_syn_with_ports(src_port: u16, dst_port: u16) -> Vec<u8> {
    build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 1), Ipv4Addr::new(10, 0, 0, 2), src_port, dst_port)
}

fn ipv4_tcp_ack(dst_port: u16) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45;
    pkt[3] = 40;
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
    pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
    pkt[20..22].copy_from_slice(&12345u16.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[32] = 0x50;
    pkt[33] = 0x10; // ACK
    pkt
}

fn ipv6_tcp_syn(dst_port: u16) -> Vec<u8> {
    ipv6_tcp_syn_with_ports(12345, dst_port)
}

fn ipv6_tcp_syn_with_ports(src_port: u16, dst_port: u16) -> Vec<u8> {
    build_ipv6_tcp_syn_packet(Ipv6Addr::LOCALHOST, Ipv6Addr::LOCALHOST, src_port, dst_port)
}

#[test]
fn injected_rst_with_ip_id_zero_is_detected() {
    assert!(is_injected_rst(&ipv4_tcp_rst(0x0000)));
}

#[test]
fn injected_rst_with_ip_id_one_is_detected() {
    assert!(is_injected_rst(&ipv4_tcp_rst(0x0001)));
}

#[test]
fn real_rst_with_normal_ip_id_is_not_injected() {
    assert!(!is_injected_rst(&ipv4_tcp_rst(0x1234)));
}

#[test]
fn tcp_syn_is_not_injected_rst() {
    assert!(!is_injected_rst(&ipv4_tcp_syn()));
}

#[test]
fn short_packet_is_not_injected_rst() {
    assert!(!is_injected_rst(&[0x45, 0x00, 0x00]));
}

#[test]
fn packet_with_zero_ihl_is_not_injected_rst() {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x40; // IPv4, IHL=0 (malformed)
    pkt[3] = 40;
    pkt[9] = 6; // TCP
    pkt[33] = 0x04; // RST flag (at byte 33, which is IHL+13 only if IHL=20)
    assert!(!is_injected_rst(&pkt), "malformed IHL=0 packet should not be detected as injected RST");
}

#[test]
fn tcp_syn_detects_ipv6_packets() {
    assert!(is_tcp_syn(&ipv6_tcp_syn(443)));
}

#[test]
fn ipv4_transport_helpers_reject_wrong_protocol_and_extract_ports() {
    assert_eq!(tcp_dst_port(&ipv4_tcp_syn()), Some(443));
    assert_eq!(tcp_dst_port(&ipv4_tcp_ack(8443)), Some(8443));
    assert!(!is_tcp_syn(&ipv4_tcp_ack(8443)));
}

#[test]
fn tcp_dst_port_extracts_ipv6_destination_port() {
    assert_eq!(tcp_dst_port(&ipv6_tcp_syn(8443)), Some(8443));
}

#[test]
fn tcp_syn_flow_key_extracts_ipv4_endpoints() {
    let key = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51000, 443)).expect("ipv4 syn flow key");

    assert_eq!(key.src, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 51000));
    assert_eq!(key.dst, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 443));
}

#[test]
fn tcp_syn_flow_key_distinguishes_parallel_https_flows() {
    let first = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51000, 443)).expect("first flow");
    let second = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51001, 443)).expect("second flow");

    assert_ne!(first, second);
}

#[test]
fn tcp_syn_flow_key_extracts_ipv6_endpoints() {
    let key = tcp_syn_flow_key(&ipv6_tcp_syn_with_ports(51000, 443)).expect("ipv6 syn flow key");

    assert_eq!(key.src, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 51000));
    assert_eq!(key.dst, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443));
}

#[test]
fn build_udp_response_supports_ipv4() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 53);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 5353);
    let payload = b"dns";

    let pkt = build_udp_response(src, dst, payload);

    assert_eq!(pkt.len(), 20 + 8 + payload.len());
    assert_eq!(pkt[0] >> 4, 4);
    assert_eq!(pkt[9], 17);
    assert_eq!(u16::from_be_bytes([pkt[20], pkt[21]]), 53);
    assert_eq!(u16::from_be_bytes([pkt[22], pkt[23]]), 5353);
    assert_ne!(u16::from_be_bytes([pkt[26], pkt[27]]), 0);
    assert_eq!(&pkt[28..], payload);
}

#[test]
fn build_udp_response_supports_ipv6() {
    let src = SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 53);
    let dst = SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 5353);
    let payload = b"dns";

    let pkt = build_udp_response(src, dst, payload);

    assert_eq!(pkt.len(), 40 + 8 + payload.len());
    assert_eq!(pkt[0] >> 4, 6);
    assert_eq!(pkt[6], 17);
    assert_eq!(u16::from_be_bytes([pkt[40], pkt[41]]), 53);
    assert_eq!(u16::from_be_bytes([pkt[42], pkt[43]]), 5353);
    assert_ne!(u16::from_be_bytes([pkt[46], pkt[47]]), 0);
    assert_eq!(&pkt[48..], payload);
}

#[test]
fn build_udp_response_rejects_oversized_payloads() {
    let payload = vec![0u8; usize::from(u16::MAX)];
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 53);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 5353);

    assert!(build_udp_response(src, dst, &payload).is_empty());
}

#[test]
fn build_udp_port_unreachable_supports_ipv4() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(157, 240, 229, 174)), 443);
    let expected_src = match dst.ip() {
        IpAddr::V4(value) => value.octets(),
        IpAddr::V6(_) => panic!("expected ipv4"),
    };
    let expected_dst = match src.ip() {
        IpAddr::V4(value) => value.octets(),
        IpAddr::V6(_) => panic!("expected ipv4"),
    };

    let pkt = build_udp_port_unreachable(src, dst, b"quic");

    assert_eq!(pkt[0] >> 4, 4);
    assert_eq!(pkt[9], 1);
    assert_eq!(pkt[20], 3);
    assert_eq!(pkt[21], 3);
    assert_eq!(&pkt[12..16], &expected_src);
    assert_eq!(&pkt[16..20], &expected_dst);
    assert!(!pkt[28..].is_empty());
}

#[test]
fn build_udp_port_unreachable_supports_ipv6() {
    let src = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 53000);
    let dst = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443);

    let pkt = build_udp_port_unreachable(src, dst, b"quic");

    assert_eq!(pkt[0] >> 4, 6);
    assert_eq!(pkt[6], 58);
    assert_eq!(pkt[40], 1);
    assert_eq!(pkt[41], 4);
    assert!(!pkt[48..].is_empty());
}

#[test]
fn is_tcp_syn_returns_false_for_udp_packet() {
    // IPv4 UDP packet -- not TCP at all
    let mut pkt = vec![0u8; 28];
    pkt[0] = 0x45;
    pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
    pkt[9] = 17; // UDP
    pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
    pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);

    assert!(!is_tcp_syn(&pkt));
}

#[test]
fn is_tcp_syn_returns_false_for_empty_input() {
    assert!(!is_tcp_syn(&[]));
}

#[test]
fn is_tcp_syn_returns_false_for_syn_ack() {
    // SYN+ACK has flags 0x12 (SYN=1, ACK=1)
    let mut pkt = ipv4_tcp_syn();
    pkt[33] = 0x12; // SYN+ACK
    assert!(!is_tcp_syn(&pkt));
}

#[test]
fn is_tcp_syn_detects_ipv4_syn() {
    assert!(is_tcp_syn(&ipv4_tcp_syn()));
}

#[test]
fn ipv6_rst_is_not_injected() {
    // is_injected_rst is IPv4-only; IPv6 RST should return false
    let mut pkt = vec![0u8; 60];
    pkt[0] = 0x60; // IPv6
    pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
    pkt[6] = 6; // TCP
    pkt[7] = 64;
    pkt[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
    pkt[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
    pkt[52] = 0x50;
    pkt[53] = 0x04; // RST

    assert!(!is_injected_rst(&pkt));
}

#[test]
fn is_injected_rst_returns_false_for_empty_input() {
    assert!(!is_injected_rst(&[]));
}

#[test]
fn tcp_syn_flow_key_returns_none_for_ack() {
    assert!(tcp_syn_flow_key(&ipv4_tcp_ack(443)).is_none());
}

#[test]
fn tcp_syn_flow_key_returns_none_for_rst() {
    assert!(tcp_syn_flow_key(&ipv4_tcp_rst(0x1234)).is_none());
}

#[test]
fn tcp_syn_flow_key_returns_none_for_empty_input() {
    assert!(tcp_syn_flow_key(&[]).is_none());
}

#[test]
fn tcp_dst_port_returns_none_for_udp() {
    let mut pkt = vec![0u8; 28];
    pkt[0] = 0x45;
    pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
    pkt[9] = 17; // UDP
    assert_eq!(tcp_dst_port(&pkt), None);
}

#[test]
fn tcp_dst_port_returns_none_for_empty_input() {
    assert_eq!(tcp_dst_port(&[]), None);
}

#[test]
fn build_udp_response_mismatched_families_returns_empty() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 53);
    let dst = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 5353);

    assert!(build_udp_response(src, dst, b"data").is_empty());
}

#[test]
fn build_udp_response_empty_payload_ipv4() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 53);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 5353);

    let pkt = build_udp_response(src, dst, b"");

    assert_eq!(pkt.len(), 20 + 8); // IP header + UDP header, no payload
    assert_eq!(pkt[9], 17); // UDP protocol
    assert_eq!(&pkt[28..], b""); // empty payload
}

#[test]
fn build_udp_response_empty_payload_ipv6() {
    let src = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 53);
    let dst = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 5353);

    let pkt = build_udp_response(src, dst, b"");

    assert_eq!(pkt.len(), 40 + 8);
    assert_eq!(pkt[6], 17);
    assert_eq!(&pkt[48..], b"");
}

#[test]
fn build_udp_response_ipv4_round_trip_parses() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 1)), 53);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 5353);
    let payload = b"round trip test data";

    let pkt = build_udp_response(src, dst, payload);

    // Re-parse with etherparse to verify structural correctness
    let parsed = etherparse::SlicedPacket::from_ip(&pkt).expect("parse built packet");
    let net = parsed.net.expect("has net layer");
    let ipv4 = match net {
        etherparse::NetSlice::Ipv4(v4) => v4,
        other => panic!("expected Ipv4, got {other:?}"),
    };
    assert_eq!(ipv4.header().source_addr(), Ipv4Addr::new(192, 168, 1, 1));
    assert_eq!(ipv4.header().destination_addr(), Ipv4Addr::new(10, 0, 0, 2));
    assert_eq!(ipv4.header().protocol(), etherparse::IpNumber::UDP);
    assert_eq!(ipv4.header().ttl(), 64);

    let udp = match parsed.transport.expect("has transport") {
        etherparse::TransportSlice::Udp(u) => u,
        other => panic!("expected Udp, got {other:?}"),
    };
    assert_eq!(udp.source_port(), 53);
    assert_eq!(udp.destination_port(), 5353);
    assert_eq!(udp.payload(), payload);
}

#[test]
fn build_udp_response_ipv6_round_trip_parses() {
    let src_ip = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);
    let dst_ip = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 2);
    let src = SocketAddr::new(IpAddr::V6(src_ip), 53);
    let dst = SocketAddr::new(IpAddr::V6(dst_ip), 5353);
    let payload = b"ipv6 round trip";

    let pkt = build_udp_response(src, dst, payload);

    let parsed = etherparse::SlicedPacket::from_ip(&pkt).expect("parse built packet");
    let net = parsed.net.expect("has net layer");
    let ipv6 = match net {
        etherparse::NetSlice::Ipv6(v6) => v6,
        other => panic!("expected Ipv6, got {other:?}"),
    };
    assert_eq!(ipv6.header().source_addr(), src_ip);
    assert_eq!(ipv6.header().destination_addr(), dst_ip);
    assert_eq!(ipv6.header().hop_limit(), 64);

    let udp = match parsed.transport.expect("has transport") {
        etherparse::TransportSlice::Udp(u) => u,
        other => panic!("expected Udp, got {other:?}"),
    };
    assert_eq!(udp.source_port(), 53);
    assert_eq!(udp.destination_port(), 5353);
    assert_eq!(udp.payload(), payload);
}

#[test]
fn build_udp_response_ipv4_has_valid_checksums() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 1234);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 5678);

    let pkt = build_udp_response(src, dst, b"checksum test");

    // IP header checksum: sum of all 16-bit words in header should be 0xFFFF
    let ip_check = checksum_sum(&pkt[..20]);
    assert_eq!(finalize_checksum(ip_check), 0, "IP header checksum should validate to zero");
}

#[test]
fn build_udp_response_ipv6_oversized_rejects() {
    // UDP payload that would overflow u16 payload_length
    let payload = vec![0u8; usize::from(u16::MAX)];
    let src = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 53);
    let dst = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 5353);

    assert!(build_udp_response(src, dst, &payload).is_empty());
}
