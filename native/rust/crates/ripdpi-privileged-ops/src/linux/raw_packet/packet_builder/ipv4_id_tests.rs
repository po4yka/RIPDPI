use std::collections::HashSet;
use std::net::SocketAddr;

use super::*;
use crate::fragmentation::reserve_fragment_identification;
use crate::linux::tcp_repair::{TcpRepairOptionsSnapshot, TcpRepairWindow};

#[test]
fn raw_tcp_packets_share_live_ipv4_ids_with_fragments_across_ports() {
    let source: SocketAddr = "192.0.2.174:40000".parse().unwrap();
    let target: SocketAddr = "198.51.100.174:443".parse().unwrap();
    let fragment_id = reserve_fragment_identification(source, target, 6, Some(101)).unwrap() as u16;
    let snapshot = TcpRepairSnapshot {
        sequence_number: 0x0102_0304,
        acknowledgment_number: 0x0506_0708,
        window_size: 4096,
        repair_window: TcpRepairWindow::default(),
        options: TcpRepairOptionsSnapshot::default(),
    };
    let options = FakeTcpOptions {
        force_raw_original: true,
        secondary_fake_prefix: Some(b"second-fake"),
        ipv4_identifications: vec![fragment_id; 3],
        ..Default::default()
    };
    let mut identifiers = HashSet::from([fragment_id]);
    for port in [40000, 40001] {
        let mut source = source;
        source.set_port(port);
        let packets =
            build_fake_tcp_packets(source, target, 8, 37, &snapshot, b"real-payload", b"first-fake", false, &options)
                .unwrap();
        assert_eq!(packets.len(), 3);
        for (packet, expected_payload) in
            packets.iter().zip([b"first-fake".as_slice(), b"second-fake", b"real-payload"])
        {
            assert_eq!(packet[0], 0x45);
            assert_eq!(packet[9], 6);
            assert_eq!(usize::from(u16::from_be_bytes([packet[2], packet[3]])), packet.len());
            let header_len = usize::from(packet[0] & 15) * 4;
            let identification = u16::from_be_bytes([packet[4], packet[5]]);
            assert_ne!(identification, 0);
            assert!(identifiers.insert(identification), "a live ID must not repeat across TCP ports or fragments");
            assert_eq!(packet[6] & 0x60, 0, "the packet remains eligible for fragmentation");
            assert_eq!(checksum_sum(&packet[..header_len]), 0xffff, "IPv4 checksum");
            let tcp = &packet[header_len..];
            assert_eq!(u16::from_be_bytes([tcp[0], tcp[1]]), port);
            assert_eq!(&tcp[usize::from(tcp[12] >> 4) * 4..], expected_payload);
            let mut pseudo_header = packet[12..20].to_vec();
            pseudo_header.extend_from_slice(&[0, 6]);
            pseudo_header.extend_from_slice(&u16::try_from(tcp.len()).unwrap().to_be_bytes());
            pseudo_header.extend_from_slice(tcp);
            assert_eq!(checksum_sum(&pseudo_header), 0xffff, "TCP checksum");
        }
    }
    assert_eq!(identifiers.len(), 7);
}

fn checksum_sum(bytes: &[u8]) -> u16 {
    let mut sum = bytes
        .chunks(2)
        .map(|word| u32::from(u16::from_be_bytes([word[0], word.get(1).copied().unwrap_or(0)])))
        .sum::<u32>();
    while sum > u32::from(u16::MAX) {
        sum = (sum & u32::from(u16::MAX)) + (sum >> 16);
    }
    sum as u16
}
