use super::{build_ordered_tcp_packets, validate_ordered_tcp_packet_sizes};
use crate::linux::tcp_repair::{TcpRepairOptionsSnapshot, TcpRepairSnapshot, TcpRepairWindow, TcpTimestampSnapshot};
use crate::{OrderedTcpSegment, TcpFlagOverrides};

fn snapshot(timestamp: bool) -> TcpRepairSnapshot {
    TcpRepairSnapshot {
        sequence_number: u32::MAX - 1,
        acknowledgment_number: 77,
        window_size: 4096,
        repair_window: TcpRepairWindow::default(),
        options: TcpRepairOptionsSnapshot {
            mss: Some(1460),
            timestamp: timestamp.then_some(TcpTimestampSnapshot { value: 99, echo_reply: 55, usec_ts: false }),
            ..TcpRepairOptionsSnapshot::default()
        },
    }
}

fn segment(payload: &[u8], fake: bool) -> OrderedTcpSegment<'_> {
    OrderedTcpSegment {
        payload,
        ttl: if fake { 7 } else { 64 },
        flags: TcpFlagOverrides::default(),
        sequence_offset: if fake { 0 } else { 4 },
        use_fake_timestamp: fake,
    }
}

fn checksum(bytes: &[u8]) -> u16 {
    let mut sum =
        bytes.chunks(2).map(|part| u32::from(u16::from_be_bytes([part[0], *part.get(1).unwrap_or(&0)]))).sum::<u32>();
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[test]
fn ordered_batch_rejects_a_later_mss_overflow_before_admission() {
    let large = [b'R'; 4096];
    let segments = [segment(b"fake", true), segment(&large, false)];
    let base_snapshot = snapshot(false);
    let packets = build_ordered_tcp_packets(
        "192.0.2.10:12345".parse().unwrap(),
        "198.51.100.20:443".parse().unwrap(),
        &base_snapshot,
        &segments,
        None,
        false,
        &[11, 12],
    )
    .expect("serialize complete batch");
    assert_eq!(packets.iter().map(Vec::len).collect::<Vec<_>>(), [44, 4136]);
    for (mss, mtu, limit) in [(1460, 65535, "MSS"), (6000, 1500, "MTU")] {
        let mut snapshot = snapshot(false);
        snapshot.options.mss = Some(mss);
        let error = validate_ordered_tcp_packet_sizes(&segments, &packets, &snapshot, mtu)
            .expect_err("a short first packet must not admit a later oversized span");
        assert_eq!(error.kind(), std::io::ErrorKind::Unsupported);
        assert!(error.to_string().contains(limit));
    }
}

#[test]
fn ordered_batch_checks_complete_packet_mtu_and_preserves_wire_fields() {
    let mut ipv4_identifications = std::collections::HashSet::new();
    for (source, target, ip_len) in
        [("192.0.2.10:12345", "198.51.100.20:443", 20), ("[2001:db8::10]:12345", "[2001:db8::20]:443", 40)]
    {
        for timestamp in [false, true] {
            for md5 in [false, true] {
                let mut snapshot = snapshot(timestamp);
                snapshot.options.mss = Some(if md5 { 24 } else { 4 });
                let segments = [segment(b"fake", true), segment(b"REAL", false)];
                let packets = build_ordered_tcp_packets(
                    source.parse().unwrap(),
                    target.parse().unwrap(),
                    &snapshot,
                    &segments,
                    snapshot.options.timestamp,
                    md5,
                    &[11, 12],
                )
                .expect("serialize valid packets");
                let limit = ip_len + 20 + 4 + if timestamp { 12 } else { 0 } + if md5 { 20 } else { 0 };
                validate_ordered_tcp_packet_sizes(&segments, &packets, &snapshot, limit as u32)
                    .expect("exact MSS and complete-packet MTU fit");
                let error = validate_ordered_tcp_packet_sizes(&segments, &packets, &snapshot, limit as u32 - 1)
                    .expect_err("options count against path MTU");
                assert_eq!(error.kind(), std::io::ErrorKind::Unsupported);
                assert!(error.to_string().contains("MTU"));
                for (index, packet) in packets.iter().enumerate() {
                    let tcp = &packet[ip_len..];
                    let tcp_len = usize::from(tcp[12] >> 4) * 4;
                    assert_eq!(&tcp[tcp_len..], if index == 0 { b"fake" } else { b"REAL" });
                    assert_eq!(u32::from_be_bytes(tcp[4..8].try_into().unwrap()), [u32::MAX - 1, 2][index]);
                    assert_eq!(u32::from_be_bytes(tcp[8..12].try_into().unwrap()), 77);
                    assert_eq!(tcp[13], 0x18);
                    let mut pseudo = Vec::new();
                    if ip_len == 20 {
                        let identification = u16::from_be_bytes(packet[4..6].try_into().unwrap());
                        assert_ne!(identification, 0);
                        assert!(ipv4_identifications.insert(identification), "live IPv4 IDs must not repeat");
                        assert_eq!(packet[6] & 0x60, 0, "packets remain eligible for fragmentation");
                        assert_eq!(packet[8], [7, 64][index]);
                        assert_eq!(checksum(&packet[..20]), 0);
                        assert_eq!(usize::from(u16::from_be_bytes(packet[2..4].try_into().unwrap())), packet.len());
                        pseudo.extend_from_slice(&packet[12..20]);
                        pseudo.extend_from_slice(&[0, 6]);
                        pseudo.extend_from_slice(&(tcp.len() as u16).to_be_bytes());
                    } else {
                        assert_eq!(packet[7], [7, 64][index]);
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
    assert_eq!(ipv4_identifications.len(), 8);
}

#[test]
fn ordered_batch_refuses_unknown_limits() {
    let segments = [segment(b"real", false)];
    let packets = build_ordered_tcp_packets(
        "192.0.2.10:12345".parse().unwrap(),
        "198.51.100.20:443".parse().unwrap(),
        &snapshot(false),
        &segments,
        None,
        false,
        &[11],
    )
    .expect("serialize a valid packet");
    for (mss, mtu) in [(None, 1500), (Some(0), 1500), (Some(1460), 0), (Some(1460), 43)] {
        let mut snapshot = snapshot(false);
        snapshot.options.mss = mss;
        assert_eq!(
            validate_ordered_tcp_packet_sizes(&segments, &packets, &snapshot, mtu)
                .expect_err("unknown or insufficient bounds must not admit a batch")
                .kind(),
            std::io::ErrorKind::Unsupported
        );
    }
}

#[test]
fn ordered_batch_counts_extra_fake_options_against_stream_mss() {
    for timestamp in [false, true] {
        let mut snapshot = snapshot(timestamp);
        snapshot.options.mss = Some(24);
        let segments = [segment(b"fake!", true)];
        let packets = build_ordered_tcp_packets(
            "192.0.2.10:12345".parse().unwrap(),
            "198.51.100.20:443".parse().unwrap(),
            &snapshot,
            &segments,
            snapshot.options.timestamp,
            true,
            &[11],
        )
        .expect("serialize extra MD5 options");
        let error = validate_ordered_tcp_packet_sizes(&segments, &packets, &snapshot, 1500)
            .expect_err("five data bytes plus twenty extra option bytes exceed the stream MSS");
        assert_eq!(error.kind(), std::io::ErrorKind::Unsupported);
        assert!(error.to_string().contains("MSS"));
        snapshot.options.mss = Some(25);
        validate_ordered_tcp_packet_sizes(&segments, &packets, &snapshot, 1500)
            .expect("known timestamp space must not be subtracted twice");
    }
}
