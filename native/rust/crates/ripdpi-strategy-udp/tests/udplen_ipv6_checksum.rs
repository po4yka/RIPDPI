use ripdpi_strategy_udp::{apply_udplen, ipv6_udp_checksum};

#[test]
fn udplen_ipv6_recalculates_udp_checksum() {
    let packet = ipv6_udp_packet();

    let modified = apply_udplen(&packet, 4).expect("modify IPv6 UDP packet");
    // Independent complete-packet oracle: hello plus four zero bytes has checksum 0x561c.
    let expected = 0x561c;
    assert_eq!(ipv6_udp_checksum(&modified), Some(expected));

    assert_ne!(&modified[46..48], &[0, 0]);
    assert_eq!(u16::from_be_bytes([modified[46], modified[47]]), expected);
}

fn ipv6_udp_packet() -> Vec<u8> {
    let payload = b"hello";
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
    // Independently built IPv6/UDP hello packet, also verified by TShark.
    packet[46..48].copy_from_slice(&0x5624u16.to_be_bytes());
    packet
}

#[test]
fn udplen_ipv6_walks_safe_option_headers() {
    for headers in [vec![0], vec![60], vec![0, 60]] {
        let mut packet = ipv6_udp_packet();
        let udp = packet.split_off(40);
        packet[6] = headers[0];
        for (index, _) in headers.iter().enumerate() {
            let next = headers.get(index + 1).copied().unwrap_or(17);
            packet.extend_from_slice(&[next, 0, 1, 4, 0, 0, 0, 0]);
        }
        packet.extend_from_slice(&udp);
        let length = (packet.len() - 40) as u16;
        packet[4..6].copy_from_slice(&length.to_be_bytes());
        let original = packet.clone();
        let offset = 40 + 8 * headers.len();
        let modified = apply_udplen(&packet, 4).expect("safe IPv6 options");
        assert_eq!(&modified[..offset + 4], &packet[..offset + 4]);
        assert_eq!(&modified[offset + 4..offset + 6], &17u16.to_be_bytes());
        assert_eq!(&modified[offset + 6..offset + 8], &0x561cu16.to_be_bytes());
        assert_eq!(&modified[offset + 8..], b"hello");
        assert_eq!(packet, original);
    }
}

#[test]
fn udplen_ipv6_rejects_unsupported_and_malformed_headers() {
    for next in [43, 44, 50, 51, 59] {
        let mut packet = ipv6_udp_packet();
        packet[6] = next;
        assert!(apply_udplen(&packet, 4).is_none());
    }
    let mut jumbo = ipv6_udp_packet();
    jumbo[4..6].copy_from_slice(&[0, 0]);
    assert!(apply_udplen(&jumbo, 4).is_none());
    for header in [[17, 255, 1, 4, 0, 0, 0, 0], [17, 0, 0x40, 4, 0, 0, 0, 0], [17, 0, 1, 7, 0, 0, 0, 0]] {
        let mut packet = ipv6_udp_packet();
        packet[6] = 0;
        packet.splice(40..40, header);
        let length = (packet.len() - 40) as u16;
        packet[4..6].copy_from_slice(&length.to_be_bytes());
        assert!(apply_udplen(&packet, 4).is_none());
    }
}

#[test]
fn ipv6_option_parser_never_panics_on_bounded_generated_bytes() {
    let mut seed = 0x6d34_u64;
    for length in 0usize..512 {
        for next in [0, 17, 43, 44, 51, 60] {
            let mut packet = vec![0; length];
            for byte in &mut packet {
                seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
                *byte = (seed >> 32) as u8;
            }
            if let Some(first) = packet.first_mut() {
                *first = 0x60;
            }
            if let Some(payload_length) = packet.get_mut(4..6) {
                payload_length.copy_from_slice(&(length.saturating_sub(40) as u16).to_be_bytes());
            }
            if let Some(header) = packet.get_mut(6) {
                *header = next;
            }
            let original = packet.clone();
            let _ = ipv6_udp_checksum(&packet);
            if let Some(modified) = apply_udplen(&packet, 1) {
                assert_eq!(modified.len(), packet.len());
                assert_eq!(&modified[..40], &packet[..40]);
            }
            assert_eq!(packet, original);
        }
    }
}
