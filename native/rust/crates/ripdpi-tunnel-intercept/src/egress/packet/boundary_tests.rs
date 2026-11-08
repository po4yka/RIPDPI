use super::*;
use crate::egress::upstream_vectors::*;

#[test]
fn invalid_ip_lengths_and_checksums_never_reach_packet_metadata() {
    for original in [IPV4_TCP, IPV6_TCP, IPV4_UDP, IPV6_HOP_UDP] {
        let valid = decode(original);
        assert!(PacketMeta::parse(&valid).is_some());
        assert!(PacketMeta::parse(&valid[..valid.len() - 1]).is_none(), "truncated {original}");
        let mut bad_checksum = valid;
        *bad_checksum.last_mut().unwrap() ^= 1;
        assert!(PacketMeta::parse(&bad_checksum).is_none(), "bad transport checksum {original}");
    }
    let mut bad_ip = decode(IPV4_UDP);
    bad_ip[10] ^= 1;
    assert!(PacketMeta::parse(&bad_ip).is_none());
    let mut zero_ipv6_checksum = decode(IPV6_HOP_UDP);
    let endpoint = transport_endpoint(&zero_ipv6_checksum).unwrap();
    zero_ipv6_checksum[endpoint.transport_offset + 6..endpoint.transport_offset + 8].fill(0);
    assert!(PacketMeta::parse(&zero_ipv6_checksum).is_none());
}

#[test]
fn declared_ip_and_udp_payload_ends_exclude_trailing_bytes() {
    let mut packet = decode(IPV4_UDP);
    packet.extend_from_slice(b"tail");
    let meta = PacketMeta::parse(&packet).unwrap();
    assert_eq!(meta.payload(&packet), b"abcdefg");
    // RFC 768 permits an omitted IPv4 checksum. Only three UDP data bytes are declared.
    let udp_offset = transport_endpoint(&packet).unwrap().transport_offset;
    packet[udp_offset + 4..udp_offset + 8].copy_from_slice(&[0, 11, 0, 0]);
    let meta = PacketMeta::parse(&packet).unwrap();
    assert_eq!(meta.payload(&packet), b"abc");
}

#[test]
fn extension_policy_rejects_active_routing_and_discard_options() {
    let valid = decode(IPV6_HOP_TCP);
    let mut routing = valid.clone();
    routing[6] = 43;
    routing[43] = 1;
    assert!(PacketMeta::parse(&routing).is_none());
    let mut discard = valid;
    discard[42] = 0x40;
    assert!(PacketMeta::parse(&discard).is_none());
}

#[test]
fn safe_extension_chains_have_no_eight_iteration_limit() {
    let original = decode(IPV6_TCP);
    let mut packet = original[..40].to_vec();
    packet[6] = 60;
    for index in 0..8 {
        packet.extend_from_slice(&[if index == 7 { 6 } else { 60 }, 0, 1, 4, 0, 0, 0, 0]);
    }
    packet.extend_from_slice(&original[40..]);
    let payload_len = (packet.len() - 40) as u16;
    packet[4..6].copy_from_slice(&payload_len.to_be_bytes());
    assert_eq!(PacketMeta::parse(&packet).unwrap().payload(&packet), b"abcdefg");
}

#[test]
fn low_ttl_copy_preserves_tcp_behind_hop_by_hop_header() {
    let packet = decode(IPV6_HOP_TCP);
    let mut expected = packet.clone();
    expected[7] = 3;
    assert_eq!(low_ttl_tcp_copy(&packet, 3).unwrap(), expected);
}

// Scapy vectors checked by the independent review's wire oracle.
#[test]
fn checksum_negative_zero_encodings_are_accepted() {
    assert!(PacketMeta::parse(&decode("45 00 00 2a 00 01 00 00 40 06 34 c2 0a 00 00 02 cb 00 71 09 9c 40 01 bb 00 00 00 01 00 00 00 01 50 18 20 00 ff ff 00 00 ab c1")).is_some());
    assert!(PacketMeta::parse(&decode("45 00 00 28 34 c5 00 00 40 06 ff ff 0a 00 00 02 cb 00 71 09 9c 40 01 bb 00 00 00 00 00 00 00 00 50 02 20 00 ab db 00 00")).is_some());
}

#[test]
fn zero_ipv6_payload_length_does_not_enable_jumbogram_fallback() {
    for original in [IPV6_TCP, IPV6_HOP_TCP, IPV6_HOP_UDP] {
        let mut packet = decode(original);
        packet[4..6].fill(0);
        assert!(PacketMeta::parse(&packet).is_none());
    }
}
