use ripdpi_strategy_udp::apply_udplen;

#[test]
fn udplen_ipv4_clears_udp_checksum() {
    let packet = ipv4_udp_packet(b"hello");

    let modified = apply_udplen(&packet, 2).expect("modify IPv4 UDP packet");

    assert_eq!(&modified[26..28], &[0, 0]);
}

fn ipv4_udp_packet(payload: &[u8]) -> Vec<u8> {
    let total_len = 20 + 8 + payload.len();
    let udp_len = 8 + payload.len();
    let mut packet = vec![0u8; total_len];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = 17;
    packet[12..16].copy_from_slice(&[10, 0, 0, 1]);
    packet[16..20].copy_from_slice(&[10, 0, 0, 2]);
    packet[20..22].copy_from_slice(&1111u16.to_be_bytes());
    packet[22..24].copy_from_slice(&443u16.to_be_bytes());
    packet[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[26..28].copy_from_slice(&0x1234u16.to_be_bytes());
    packet[28..].copy_from_slice(payload);
    packet
}
