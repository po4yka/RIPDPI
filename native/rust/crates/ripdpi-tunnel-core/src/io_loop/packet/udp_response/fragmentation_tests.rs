use std::net::SocketAddr;

use super::build_udp_response_fragments;

fn checksum_sum(bytes: &[u8]) -> u32 {
    bytes.chunks(2).map(|word| u32::from(u16::from_be_bytes([word[0], *word.get(1).unwrap_or(&0)]))).sum()
}

fn checksum_is_valid(mut sum: u32) -> bool {
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    sum == 0xffff
}

fn reassemble_fragments(packets: &[Vec<u8>], src: SocketAddr, dst: SocketAddr, mtu: usize) -> Vec<u8> {
    let ipv6 = src.is_ipv6();
    let mut pieces = std::collections::BTreeMap::new();
    let mut identification = None;
    let mut final_end = None;
    for packet in packets.iter().rev() {
        assert!(packet.len() <= mtu, "packet length {} exceeds MTU {mtu}", packet.len());
        match (src.ip(), dst.ip()) {
            (std::net::IpAddr::V4(src), std::net::IpAddr::V4(dst)) => {
                assert_eq!(&packet[12..16], &src.octets());
                assert_eq!(&packet[16..20], &dst.octets());
            }
            (std::net::IpAddr::V6(src), std::net::IpAddr::V6(dst)) => {
                assert_eq!(&packet[8..24], &src.octets());
                assert_eq!(&packet[24..40], &dst.octets());
            }
            _ => panic!("matching families"),
        }
        let (offset, more, id, data) = if ipv6 {
            assert_eq!(packet[0] >> 4, 6);
            assert_eq!(packet[6], 44, "Fragment header follows IPv6");
            assert_eq!(usize::from(u16::from_be_bytes([packet[4], packet[5]])), packet.len() - 40);
            assert_eq!(packet[40], 17);
            assert_eq!(packet[41], 0);
            let flags = u16::from_be_bytes([packet[42], packet[43]]);
            assert_eq!(flags & 6, 0, "reserved fragment bits");
            (
                usize::from(flags & 0xfff8),
                flags & 1 != 0,
                u32::from_be_bytes(packet[44..48].try_into().expect("fragment id")),
                &packet[48..],
            )
        } else {
            assert_eq!(packet[0], 0x45);
            assert_eq!(packet[9], 17);
            assert_eq!(usize::from(u16::from_be_bytes([packet[2], packet[3]])), packet.len());
            assert!(checksum_is_valid(checksum_sum(&packet[..20])), "IPv4 header checksum");
            let flags = u16::from_be_bytes([packet[6], packet[7]]);
            assert_eq!(flags & 0xc000, 0, "reserved and DF flags must be clear");
            (
                usize::from(flags & 0x1fff) * 8,
                flags & 0x2000 != 0,
                u32::from(u16::from_be_bytes([packet[4], packet[5]])),
                &packet[20..],
            )
        };
        assert_eq!(*identification.get_or_insert(id), id);
        if more {
            assert_eq!(data.len() % 8, 0, "non-final fragment data must align to eight bytes");
        } else {
            assert!(final_end.replace(offset + data.len()).is_none(), "one final fragment");
        }
        assert!(pieces.insert(offset, data).is_none(), "no duplicate fragment offset");
    }
    let mut datagram = Vec::new();
    for (offset, data) in pieces {
        assert_eq!(offset, datagram.len(), "no holes or overlapping fragments");
        datagram.extend_from_slice(data);
    }
    assert_eq!(Some(datagram.len()), final_end);
    datagram
}

fn assert_udp_datagram(src: SocketAddr, dst: SocketAddr, expected: &[u8], datagram: &[u8]) {
    assert_eq!(u16::from_be_bytes([datagram[0], datagram[1]]), src.port());
    assert_eq!(u16::from_be_bytes([datagram[2], datagram[3]]), dst.port());
    assert_eq!(usize::from(u16::from_be_bytes([datagram[4], datagram[5]])), datagram.len());
    assert_ne!(&datagram[6..8], &[0, 0]);
    assert_eq!(&datagram[8..], expected);
    let mut sum = checksum_sum(datagram) + 17;
    match (src.ip(), dst.ip()) {
        (std::net::IpAddr::V4(src), std::net::IpAddr::V4(dst)) => {
            sum += checksum_sum(&src.octets()) + checksum_sum(&dst.octets());
            sum += datagram.len() as u32;
        }
        (std::net::IpAddr::V6(src), std::net::IpAddr::V6(dst)) => {
            sum += checksum_sum(&src.octets()) + checksum_sum(&dst.octets());
            sum += checksum_sum(&(datagram.len() as u32).to_be_bytes());
        }
        _ => panic!("matching address families"),
    }
    assert!(checksum_is_valid(sum), "independent UDP checksum");
}

#[test]
fn large_ipv4_reply_is_fragmented_to_tun_mtu_with_intact_udp_payload() {
    let src = "203.0.113.9:443".parse().expect("source");
    let dst = "10.0.0.2:53000".parse().expect("destination");
    let payload = vec![0x5a; 2000];
    let packets: Vec<_> = build_udp_response_fragments(src, dst, &payload, 1500).collect();
    assert!(packets.len() > 1, "a large reply requires several IP fragments");
    let datagram = reassemble_fragments(&packets, src, dst, 1500);
    assert_udp_datagram(src, dst, &payload, &datagram);
}

#[test]
fn large_ipv6_reply_is_fragmented_to_tun_mtu_with_intact_udp_payload() {
    let src = "[2001:db8::9]:443".parse().expect("source");
    let dst = "[fd00::2]:53000".parse().expect("destination");
    let payload = vec![0xa5; 3000];
    let packets: Vec<_> = build_udp_response_fragments(src, dst, &payload, 1280).collect();
    assert!(packets.len() > 1, "a large reply requires several IP fragments");
    let datagram = reassemble_fragments(&packets, src, dst, 1280);
    assert_udp_datagram(src, dst, &payload, &datagram);
}

#[test]
fn maximum_normal_udp_replies_keep_their_full_payload_after_fragmentation() {
    for (src, dst, length, mtu) in
        [("203.0.113.9:443", "10.0.0.2:53000", 65_507, 1500), ("[2001:db8::9]:443", "[fd00::2]:53000", 65_527, 1280)]
    {
        let src = src.parse().expect("source");
        let dst = dst.parse().expect("destination");
        let payload = vec![0x35; length];
        let packets: Vec<_> = build_udp_response_fragments(src, dst, &payload, mtu).collect();
        let datagram = reassemble_fragments(&packets, src, dst, mtu);
        assert_udp_datagram(src, dst, &payload, &datagram);
    }
}

#[test]
fn minimum_accepted_tun_mtu_preserves_both_address_families() {
    for (src, dst) in [("203.0.113.9:443", "10.0.0.2:53000"), ("[2001:db8::9]:443", "[fd00::2]:53000")] {
        let src = src.parse().expect("source");
        let dst = dst.parse().expect("destination");
        let payload = vec![0x42; 2000];
        let packets: Vec<_> = build_udp_response_fragments(src, dst, &payload, 68).collect();
        let datagram = reassemble_fragments(&packets, src, dst, 68);
        assert_udp_datagram(src, dst, &payload, &datagram);
    }
}

#[test]
fn empty_and_mtu_sized_replies_do_not_gain_a_fragment_header() {
    for (src, dst, header_len, payload_len) in
        [("203.0.113.9:443", "10.0.0.2:53000", 20, 1472), ("[2001:db8::9]:443", "[fd00::2]:53000", 40, 1452)]
    {
        let src: SocketAddr = src.parse().expect("source");
        let dst: SocketAddr = dst.parse().expect("destination");
        for payload in [Vec::new(), vec![0x71; payload_len]] {
            let packets: Vec<_> = build_udp_response_fragments(src, dst, &payload, 1500).collect();
            assert_eq!(packets.len(), 1);
            let packet = &packets[0];
            assert_eq!(packet.len(), header_len + 8 + payload.len());
            assert_eq!(packet[if src.is_ipv4() { 9 } else { 6 }], 17);
            assert_udp_datagram(src, dst, &payload, &packet[header_len..]);
        }
    }
}

#[test]
fn neighboring_fragmented_replies_use_distinct_identifications() {
    for (src, dst, id_range) in
        [("203.0.113.9:443", "10.0.0.2:53000", 4..6), ("[2001:db8::9]:443", "[fd00::2]:53000", 44..48)]
    {
        let src = src.parse().expect("source");
        let dst = dst.parse().expect("destination");
        let first: Vec<_> = build_udp_response_fragments(src, dst, &[0x51; 2000], 1280).collect();
        let second: Vec<_> = build_udp_response_fragments(src, dst, &[0x51; 2000], 1280).collect();
        assert_ne!(&first[0][id_range.clone()], &second[0][id_range]);
    }
}

#[test]
fn impossible_fragment_mtu_and_oversized_payloads_return_no_packets() {
    for (src, dst, mtu, length) in [
        ("203.0.113.9:443", "10.0.0.2:53000", 27, 100),
        ("[2001:db8::9]:443", "[fd00::2]:53000", 55, 100),
        ("203.0.113.9:443", "10.0.0.2:53000", 1500, 65_508),
        ("[2001:db8::9]:443", "[fd00::2]:53000", 1280, 65_528),
    ] {
        let packets: Vec<_> = build_udp_response_fragments(
            src.parse().expect("source"),
            dst.parse().expect("destination"),
            &vec![0; length],
            mtu,
        )
        .collect();
        assert!(packets.is_empty());
    }
}
