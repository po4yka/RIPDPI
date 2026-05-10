use ripdpi_strategy_udp::{apply_udplen, ipv6_udp_checksum};

#[test]
fn udplen_ipv6_recalculates_udp_checksum() {
    let packet = ipv6_udp_packet(b"hello");

    let modified = apply_udplen(&packet, 4).expect("modify IPv6 UDP packet");
    let expected = ipv6_udp_checksum(&modified).expect("checksum modified packet");

    assert_ne!(&modified[46..48], &[0, 0]);
    assert_eq!(u16::from_be_bytes([modified[46], modified[47]]), expected);
}

fn ipv6_udp_packet(payload: &[u8]) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let mut packet = vec![0u8; 40 + udp_len];
    packet[0] = 0x60;
    packet[4..6].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[6] = 17;
    packet[7] = 64;
    packet[8..24].copy_from_slice(&[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]);
    packet[24..40].copy_from_slice(&[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2]);
    packet[40..42].copy_from_slice(&2222u16.to_be_bytes());
    packet[42..44].copy_from_slice(&443u16.to_be_bytes());
    packet[44..46].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[48..].copy_from_slice(payload);
    let checksum = ipv6_udp_checksum(&packet).expect("checksum original packet");
    packet[46..48].copy_from_slice(&checksum.to_be_bytes());
    packet
}
