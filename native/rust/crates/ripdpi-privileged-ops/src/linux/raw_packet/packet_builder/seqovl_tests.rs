use super::build_seqovl_tcp_packet;
use crate::TcpFlagOverrides;
use crate::linux::tcp_repair::{TcpRepairOptionsSnapshot, TcpRepairSnapshot, TcpRepairWindow, TcpTimestampSnapshot};

fn snapshot(timestamp: bool, mss: Option<u16>) -> TcpRepairSnapshot {
    TcpRepairSnapshot {
        sequence_number: 2,
        acknowledgment_number: 55,
        window_size: 4096,
        repair_window: TcpRepairWindow::default(),
        options: TcpRepairOptionsSnapshot {
            mss,
            timestamp: timestamp.then_some(TcpTimestampSnapshot { value: 99, echo_reply: 77, usec_ts: false }),
            ..TcpRepairOptionsSnapshot::default()
        },
    }
}

fn checksum(bytes: &[u8]) -> u16 {
    let mut sum = bytes
        .chunks(2)
        .map(|chunk| u32::from(u16::from_be_bytes([chunk[0], *chunk.get(1).unwrap_or(&0)])))
        .sum::<u32>();
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[test]
fn seqovl_packet_checks_full_path_mtu_for_both_families_and_options() {
    for (source, target, ip_len) in
        [("192.0.2.10:12345", "192.0.2.20:443", 20), ("[2001:db8::10]:12345", "[2001:db8::20]:443", 40)]
    {
        for timestamp in [false, true] {
            for md5 in [false, true] {
                let expected_len = ip_len + 20 + if timestamp { 12 } else { 0 } + if md5 { 20 } else { 0 } + 1532;
                let packet = build_seqovl_tcp_packet(
                    source.parse().unwrap(),
                    target.parse().unwrap(),
                    64,
                    &snapshot(timestamp, None),
                    &[b'A'; 1500],
                    &[b'X'; 32],
                    md5,
                    TcpFlagOverrides::default(),
                    None,
                    Some(expected_len),
                )
                .expect("exact MTU fits");
                assert_eq!(packet.len(), expected_len as usize);
                let error = build_seqovl_tcp_packet(
                    source.parse().unwrap(),
                    target.parse().unwrap(),
                    64,
                    &snapshot(timestamp, None),
                    &[b'A'; 1500],
                    &[b'X'; 32],
                    md5,
                    TcpFlagOverrides::default(),
                    None,
                    Some(expected_len - 1),
                )
                .expect_err("the complete packet exceeds the path MTU");
                assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
            }
        }
    }
}

#[test]
fn seqovl_packet_counts_fake_prefix_in_negotiated_mss() {
    for mss in [Some(14), None, Some(0)] {
        assert!(
            build_seqovl_tcp_packet(
                "192.0.2.10:12345".parse().unwrap(),
                "192.0.2.20:443".parse().unwrap(),
                64,
                &snapshot(true, mss),
                b"ABCDEFGHIJ",
                b"XYXY",
                true,
                TcpFlagOverrides::default(),
                None,
                Some(1500),
            )
            .is_ok()
        );
    }
    let error = build_seqovl_tcp_packet(
        "192.0.2.10:12345".parse().unwrap(),
        "192.0.2.20:443".parse().unwrap(),
        64,
        &snapshot(true, Some(13)),
        b"ABCDEFGHIJ",
        b"XYXY",
        true,
        TcpFlagOverrides::default(),
        None,
        Some(1500),
    )
    .expect_err("fake prefix shares the segment payload budget");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
}

#[test]
fn seqovl_packet_keeps_flags_payload_wraparound_and_independent_checksums() {
    for (source, target, ip_len) in
        [("192.0.2.10:12345", "192.0.2.20:443", 20), ("[2001:db8::10]:12345", "[2001:db8::20]:443", 40)]
    {
        for timestamp in [false, true] {
            for md5 in [false, true] {
                let packet = build_seqovl_tcp_packet(
                    source.parse().unwrap(),
                    target.parse().unwrap(),
                    64,
                    &snapshot(timestamp, None),
                    b"ABC",
                    b"XYXY",
                    md5,
                    TcpFlagOverrides { set: ripdpi_config::TCP_FLAG_ECE, unset: ripdpi_config::TCP_FLAG_PSH },
                    (ip_len == 20).then_some(0x1234),
                    Some(1500),
                )
                .expect("bounded mixed segment");
                let tcp = &packet[ip_len..];
                assert_eq!(u32::from_be_bytes(tcp[4..8].try_into().unwrap()), 0xffff_fffe);
                assert_eq!(tcp[13], 0x50, "ACK and ECE are set, PSH is clear");
                let tcp_len = usize::from(tcp[12] >> 4) * 4;
                assert_eq!(&tcp[tcp_len..], b"XYXYABC");
                let mut pseudo = Vec::new();
                if ip_len == 20 {
                    assert_eq!(packet[8], 64);
                    assert_eq!(usize::from(u16::from_be_bytes(packet[2..4].try_into().unwrap())), packet.len());
                    assert_eq!(checksum(&packet[..20]), 0);
                    pseudo.extend_from_slice(&packet[12..20]);
                    pseudo.extend_from_slice(&[0, 6]);
                    pseudo.extend_from_slice(&(tcp.len() as u16).to_be_bytes());
                } else {
                    assert_eq!(packet[7], 64);
                    assert_eq!(usize::from(u16::from_be_bytes(packet[4..6].try_into().unwrap())), tcp.len());
                    pseudo.extend_from_slice(&packet[8..40]);
                    pseudo.extend_from_slice(&(tcp.len() as u32).to_be_bytes());
                    pseudo.extend_from_slice(&[0, 0, 0, 6]);
                }
                pseudo.extend_from_slice(tcp);
                assert_eq!(checksum(&pseudo), 0);
            }
        }
    }
}
