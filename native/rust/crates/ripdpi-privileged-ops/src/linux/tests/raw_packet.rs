use std::io;
use std::net::SocketAddr;

use etherparse::{Ipv4Header, TcpHeader};

use super::sample_tcp_repair_snapshot;
use crate::linux::fragmentation;
use crate::linux::raw_packet;
use crate::linux::tcp_repair::{sequence_after_payload, TcpTimestampSnapshot};
use crate::TcpFlagOverrides;

#[test]
fn mutate_fake_timestamp_applies_signed_delta_with_wrapping() {
    let original = Some(TcpTimestampSnapshot { value: 10, echo_reply: 20, usec_ts: false });

    let increased = raw_packet::mutate_fake_timestamp(original, Some(7)).expect("increase timestamp");
    assert_eq!(increased.unwrap().value, 17);

    let decreased = raw_packet::mutate_fake_timestamp(original, Some(-15)).expect("decrease timestamp");
    assert_eq!(decreased.unwrap().value, u32::MAX - 4);
}

#[test]
fn mutate_fake_timestamp_requires_negotiated_timestamp_option() {
    let err = raw_packet::mutate_fake_timestamp(None, Some(1)).expect_err("missing negotiated timestamp should fail");
    assert_eq!(err.kind(), io::ErrorKind::Unsupported);
}

#[test]
fn build_multi_disorder_packets_preserves_payload_ranges_sequence_numbers_and_flags() {
    let source = SocketAddr::from(([203, 0, 113, 10], 50_000));
    let target = SocketAddr::from(([198, 51, 100, 20], 443));
    let payload = b"multidisorder-payload";
    let segments = [
        crate::TcpPayloadSegment { start: 0, end: 5 },
        crate::TcpPayloadSegment { start: 5, end: 14 },
        crate::TcpPayloadSegment { start: 14, end: payload.len() },
    ];
    let snapshot = sample_tcp_repair_snapshot();

    let packets = fragmentation::build_multi_disorder_packets(
        source,
        target,
        37,
        payload,
        &segments,
        &snapshot,
        false,
        TcpFlagOverrides::default(),
        &[],
    )
    .expect("build multidisorder packets");

    assert_eq!(packets.len(), 3);

    let mut identifications = Vec::new();
    for (index, (packet, segment)) in packets.iter().zip(segments.iter()).enumerate() {
        let (ip, transport) = Ipv4Header::from_slice(packet).expect("parse ipv4 packet");
        let (tcp, tcp_payload) = TcpHeader::from_slice(transport).expect("parse tcp packet");

        identifications.push(ip.identification);
        assert_eq!(ip.time_to_live, 37);
        assert_eq!(tcp.sequence_number, sequence_after_payload(snapshot.sequence_number, segment.start).expect("seq"));
        assert_eq!(tcp.acknowledgment_number, snapshot.acknowledgment_number);
        assert_eq!(tcp.window_size, snapshot.window_size);
        assert!(tcp.ack);
        assert_eq!(tcp.psh, index == segments.len() - 1);
        assert!(tcp.header_len() > TcpHeader::MIN_LEN);
        assert_eq!(tcp_payload, &payload[segment.start..segment.end]);
    }

    assert_eq!(identifications[1], identifications[0].wrapping_add(1));
    assert_eq!(identifications[2], identifications[1].wrapping_add(1));
}

#[test]
fn build_multi_disorder_packets_rejects_non_contiguous_segment_ranges() {
    let source = SocketAddr::from(([203, 0, 113, 10], 50_000));
    let target = SocketAddr::from(([198, 51, 100, 20], 443));
    let payload = b"multidisorder";
    let segments =
        [crate::TcpPayloadSegment { start: 0, end: 4 }, crate::TcpPayloadSegment { start: 5, end: payload.len() }];

    let err = fragmentation::build_multi_disorder_packets(
        source,
        target,
        37,
        payload,
        &segments,
        &sample_tcp_repair_snapshot(),
        false,
        TcpFlagOverrides::default(),
        &[],
    )
    .expect_err("reject gapped segments");

    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    assert!(err.to_string().contains("invalid multidisorder TCP payload segments"));
}

#[test]
fn build_multi_disorder_packets_rejects_partial_payload_coverage() {
    let source = SocketAddr::from(([203, 0, 113, 10], 50_000));
    let target = SocketAddr::from(([198, 51, 100, 20], 443));
    let payload = b"multidisorder";
    let segments = [
        crate::TcpPayloadSegment { start: 0, end: 4 },
        crate::TcpPayloadSegment { start: 4, end: 8 },
        crate::TcpPayloadSegment { start: 8, end: 11 },
    ];

    let err = fragmentation::build_multi_disorder_packets(
        source,
        target,
        37,
        payload,
        &segments,
        &sample_tcp_repair_snapshot(),
        false,
        TcpFlagOverrides::default(),
        &[],
    )
    .expect_err("reject truncated coverage");

    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    assert!(err.to_string().contains("multidisorder TCP payload segments must cover the full payload"));
}
