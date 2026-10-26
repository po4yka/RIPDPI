use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::{Duration, Instant};

use etherparse::{IpFragOffset, IpNumber, Ipv4Header, Ipv6FragmentHeader, Ipv6Header, UdpHeader};

use super::{IpDefrag, MAX_REASSEMBLIES, REASSEMBLY_TIMEOUT, Reassembly};
use crate::IpClass;
use crate::classify::classify_ip_packet;

const SRC4: [u8; 4] = [10, 0, 0, 2];
const DST4: [u8; 4] = [203, 0, 113, 7];
const SRC6: [u8; 16] = [0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2];
const DST6: [u8; 16] = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7];

fn udp_datagram_v4(data: &[u8]) -> Vec<u8> {
    let header =
        UdpHeader::with_ipv4_checksum(40000, 4500, &Ipv4Header::new(0, 64, IpNumber::UDP, SRC4, DST4).unwrap(), data)
            .unwrap();
    [header.to_bytes().as_slice(), data].concat()
}

fn udp_datagram_v6(data: &[u8]) -> Vec<u8> {
    let ip = Ipv6Header {
        traffic_class: 0,
        flow_label: etherparse::Ipv6FlowLabel::ZERO,
        payload_length: 0,
        next_header: IpNumber::UDP,
        hop_limit: 64,
        source: SRC6,
        destination: DST6,
    };
    let header = UdpHeader::with_ipv6_checksum(40000, 4500, &ip, data).unwrap();
    [header.to_bytes().as_slice(), data].concat()
}

/// One IPv4 fragment of `datagram` at byte `offset` (a multiple of 8).
fn fragment_v4(id: u16, datagram: &[u8], offset: usize, len: usize) -> Vec<u8> {
    let part = &datagram[offset..(offset + len).min(datagram.len())];
    let mut header = Ipv4Header::new(part.len() as u16, 64, IpNumber::UDP, SRC4, DST4).unwrap();
    header.identification = id;
    header.dont_fragment = false;
    header.more_fragments = offset + len < datagram.len();
    header.fragment_offset = IpFragOffset::try_new((offset / 8) as u16).unwrap();
    header.header_checksum = header.calc_header_checksum();
    [header.to_bytes().as_slice(), part].concat()
}

fn fragment_v6(id: u32, datagram: &[u8], offset: usize, len: usize) -> Vec<u8> {
    let part = &datagram[offset..(offset + len).min(datagram.len())];
    let ip = Ipv6Header {
        traffic_class: 0,
        flow_label: etherparse::Ipv6FlowLabel::ZERO,
        payload_length: (Ipv6FragmentHeader::LEN + part.len()) as u16,
        next_header: IpNumber::IPV6_FRAGMENTATION_HEADER,
        hop_limit: 64,
        source: SRC6,
        destination: DST6,
    };
    let frag = Ipv6FragmentHeader::new(
        IpNumber::UDP,
        IpFragOffset::try_new((offset / 8) as u16).unwrap(),
        offset + len < datagram.len(),
        id,
    );
    [ip.to_bytes().as_slice(), frag.to_bytes().as_slice(), part].concat()
}

fn assert_udp_payload(packet: &[u8], expected: &[u8], src: IpAddr, dst: IpAddr) {
    match classify_ip_packet(packet, None) {
        IpClass::Udp { src: s, dst: d, payload } => {
            assert_eq!(s, SocketAddr::new(src, 40000));
            assert_eq!(d, SocketAddr::new(dst, 4500));
            assert_eq!(payload, expected);
        }
        other => panic!("the reassembled packet must route as UDP, got {other:?}"),
    }
}

#[test]
fn ipv4_udp_fragments_reassemble_into_one_udp_packet_in_any_order() {
    let data: Vec<u8> = (0..3000u32).map(|i| i as u8).collect();
    let datagram = udp_datagram_v4(&data);
    let mut defrag = IpDefrag::default();
    let now = Instant::now();

    let second = fragment_v4(7, &datagram, 1480, 1480);
    let third = fragment_v4(7, &datagram, 2960, 1480);
    let first = fragment_v4(7, &datagram, 0, 1480);
    assert!(matches!(defrag.accept(&second, now), Reassembly::Held));
    assert!(matches!(defrag.accept(&third, now), Reassembly::Held));
    let Reassembly::Complete(packet) = defrag.accept(&first, now) else {
        panic!("the last missing fragment must complete the datagram");
    };

    assert_udp_payload(&packet, &data, IpAddr::V4(Ipv4Addr::from(SRC4)), IpAddr::V4(Ipv4Addr::from(DST4)));
}

#[test]
fn ipv6_udp_fragments_reassemble_into_one_udp_packet() {
    let data: Vec<u8> = (0..2500u32).map(|i| (i * 7) as u8).collect();
    let datagram = udp_datagram_v6(&data);
    let mut defrag = IpDefrag::default();
    let now = Instant::now();

    assert!(matches!(defrag.accept(&fragment_v6(9, &datagram, 0, 1232), now), Reassembly::Held));
    assert!(matches!(defrag.accept(&fragment_v6(9, &datagram, 1232, 1232), now), Reassembly::Held));
    let Reassembly::Complete(packet) = defrag.accept(&fragment_v6(9, &datagram, 2464, 1232), now) else {
        panic!("the last missing fragment must complete the datagram");
    };

    assert_udp_payload(&packet, &data, IpAddr::V6(Ipv6Addr::from(SRC6)), IpAddr::V6(Ipv6Addr::from(DST6)));
}

#[test]
fn an_unfragmented_packet_passes_through() {
    let datagram = udp_datagram_v4(b"small");
    let whole = fragment_v4(1, &datagram, 0, datagram.len());
    assert!(matches!(IpDefrag::default().accept(&whole, Instant::now()), Reassembly::NotFragment));
}

#[test]
fn reassembly_is_bounded_in_count_and_time() {
    let datagram = udp_datagram_v4(&[0u8; 2000]);
    let mut defrag = IpDefrag::default();
    let start = Instant::now();
    for id in 0..MAX_REASSEMBLIES as u16 {
        assert!(matches!(defrag.accept(&fragment_v4(id, &datagram, 0, 1480), start), Reassembly::Held));
    }

    // A full pool drops the fragments of a new datagram, so it never completes.
    let extra = MAX_REASSEMBLIES as u16;
    assert!(matches!(defrag.accept(&fragment_v4(extra, &datagram, 0, 1480), start), Reassembly::Held));
    assert!(matches!(defrag.accept(&fragment_v4(extra, &datagram, 1480, 1480), start), Reassembly::Held));

    // After the timeout the stale datagrams leave the pool and a new one can complete.
    let later = start + REASSEMBLY_TIMEOUT + Duration::from_secs(1);
    assert!(matches!(defrag.accept(&fragment_v4(extra, &datagram, 0, 1480), later), Reassembly::Held));
    assert!(matches!(defrag.accept(&fragment_v4(extra, &datagram, 1480, 1480), later), Reassembly::Complete(_)));
}
