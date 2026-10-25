use std::io;
use std::net::SocketAddr;

use etherparse::{Ipv4Header, TcpHeader};

use super::{connected_pair, missing_protect_socket_path, sample_tcp_repair_snapshot};
use crate::TcpFlagOverrides;
use crate::linux::fragmentation;
use crate::linux::raw_packet;
use crate::linux::tcp_repair::{TcpTimestampSnapshot, sequence_after_payload};

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

#[test]
fn send_ip_fragmented_tcp_requires_raw_send_path_for_non_empty_payload() {
    let (client, _server) = connected_pair();
    let protect_path = missing_protect_socket_path("ipfrag-tcp");

    fragmentation::send_ip_fragmented_tcp(
        &client,
        b"fragmented-payload",
        8,
        64,
        Some(&protect_path),
        false,
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        TcpFlagOverrides::default(),
        Some(0x1234),
    )
    .expect_err("non-empty fragmented TCP payload must not be accepted without raw/protect work");
}

#[test]
fn send_multi_disorder_tcp_requires_raw_send_path_for_non_empty_payload() {
    let (client, _server) = connected_pair();
    let protect_path = missing_protect_socket_path("multidisorder-tcp");
    let payload = b"multidisorder";
    let segments = [
        crate::TcpPayloadSegment { start: 0, end: 5 },
        crate::TcpPayloadSegment { start: 5, end: 10 },
        crate::TcpPayloadSegment { start: 10, end: payload.len() },
    ];

    fragmentation::send_multi_disorder_tcp(
        &client,
        payload,
        &segments,
        64,
        Some(&protect_path),
        0,
        false,
        TcpFlagOverrides::default(),
        &[],
    )
    .expect_err("non-empty multidisorder TCP payload must not be accepted without raw/protect work");
}

#[test]
fn send_fake_tcp_stream_path_leaves_the_real_bytes_in_the_spliced_pages() {
    use std::io::Read;
    use std::time::Duration;

    let (client, mut server) = connected_pair();
    let real = b"GET / HTTP/1.1\r\nHost: real.example\r\n\r\n";
    let fake = b"GET / HTTP/1.1\r\nHost: fake.example\r\n\r\n";

    // Loopback hands the sender's spliced pages to the peer without a copy,
    // so the peer reads what the pages hold once the send returns. That is
    // also what a TCP retransmission of the lost fake would carry.
    raw_packet::send_fake_tcp(
        &client,
        real,
        fake,
        8,
        false,
        64,
        crate::FakeTcpOptions::default(),
        (false, Duration::ZERO),
    )
    .expect("stream fake send");

    let mut received = vec![0_u8; real.len()];
    server.read_exact(&mut received).expect("read spliced bytes");
    assert_eq!(received, real, "the queued segment must carry the real bytes after the fake send");
}

#[test]
fn send_fake_tcp_raw_path_leaves_repair_mode_before_the_real_write() {
    use std::io::{Read, Write};
    use std::time::Duration;

    let (client, mut server) = connected_pair();
    let result = raw_packet::send_fake_tcp(
        &client,
        b"real-bytes",
        b"fake-bytes",
        8,
        false,
        64,
        crate::FakeTcpOptions { require_raw_path: true, ..Default::default() },
        (false, Duration::ZERO),
    );
    // Without CAP_NET_ADMIN and CAP_NET_RAW the raw path is refused up front.
    if matches!(&result, Err(error) if error.kind() == io::ErrorKind::PermissionDenied) {
        return;
    }
    result.expect("raw fake followed by the real stream write");

    (&client).write_all(b"tail").expect("stream is usable after the fake send");
    // Loopback delivers the raw fake too, so the peer sees fake-or-real bytes
    // for the first range; only the length and the tail are stable.
    let mut received = [0_u8; 14];
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("set read timeout");
    server.read_exact(&mut received).expect("read payload and tail");
    assert_eq!(&received[10..], b"tail");
}

/// Returns false when the run lacks CAP_NET_ADMIN or CAP_NET_RAW, which the
/// packet-owned paths refuse up front with EPERM.
fn raw_tcp_paths_available(result: &io::Result<()>) -> bool {
    !matches!(result, Err(error) if error.kind() == io::ErrorKind::PermissionDenied)
}

/// Writes "tail" after a raw send and returns the payload-length bytes the
/// peer read before it.
fn peer_bytes_before_tail(
    client: &std::net::TcpStream,
    server: &mut std::net::TcpStream,
    payload_len: usize,
) -> Vec<u8> {
    use std::io::{Read, Write};
    use std::time::Duration;

    // If the kernel did not move the send sequence past the payload, "tail"
    // would reuse the payload's sequence range and the peer would drop it.
    (&*client).write_all(b"tail").expect("stream is usable after the raw send");
    let mut received = vec![0_u8; payload_len + 4];
    server.set_read_timeout(Some(Duration::from_secs(3))).expect("set read timeout");
    server.read_exact(&mut received).expect("read payload and tail");
    assert_eq!(&received[payload_len..], b"tail");
    received.truncate(payload_len);
    received
}

#[test]
fn send_flagged_tcp_payload_advances_the_original_socket_past_the_payload() {
    let (client, mut server) = connected_pair();
    let payload = b"flagged-payload";
    let result = raw_packet::send_flagged_tcp_payload(
        &client,
        payload,
        64,
        None,
        false,
        TcpFlagOverrides { set: 0x08, unset: 0 },
        None,
    );
    if !raw_tcp_paths_available(&result) {
        return;
    }
    result.expect("flagged payload send without a replacement socket");
    assert_eq!(peer_bytes_before_tail(&client, &mut server, payload.len()), payload);
}

#[test]
fn send_ordered_tcp_segments_queues_the_genuine_bytes() {
    use std::time::Duration;

    let (client, mut server) = connected_pair();
    let real = b"real-ordered";
    let segments = [
        crate::OrderedTcpSegment {
            payload: b"fake",
            ttl: 1,
            flags: TcpFlagOverrides::default(),
            sequence_offset: 0,
            use_fake_timestamp: true,
        },
        crate::OrderedTcpSegment {
            payload: &real[..4],
            ttl: 64,
            flags: TcpFlagOverrides { set: 0x08, unset: 0 },
            sequence_offset: 0,
            use_fake_timestamp: false,
        },
        crate::OrderedTcpSegment {
            payload: &real[4..],
            ttl: 64,
            flags: TcpFlagOverrides::default(),
            sequence_offset: 4,
            use_fake_timestamp: false,
        },
    ];
    let result = raw_packet::send_ordered_tcp_segments(
        &client,
        &segments,
        real.len(),
        64,
        None,
        false,
        None,
        &[],
        (false, Duration::ZERO),
    );
    if !raw_tcp_paths_available(&result) {
        return;
    }
    result.expect("ordered segments without a replacement socket");
    // Loopback also delivers the low-TTL fake, so the first range may hold
    // fake bytes; the real bytes stay queued for retransmission.
    let received = peer_bytes_before_tail(&client, &mut server, real.len());
    assert_eq!(&received[4..], &real[4..]);
}

#[test]
fn send_ordered_tcp_segments_rejects_genuine_segments_that_leave_a_gap() {
    use std::time::Duration;

    let (client, _server) = connected_pair();
    let segments = [crate::OrderedTcpSegment {
        payload: b"real",
        ttl: 64,
        flags: TcpFlagOverrides::default(),
        sequence_offset: 0,
        use_fake_timestamp: false,
    }];
    let err = raw_packet::send_ordered_tcp_segments(
        &client,
        &segments,
        8,
        64,
        None,
        false,
        None,
        &[],
        (false, Duration::ZERO),
    )
    .expect_err("the kernel cannot queue bytes the segments do not carry");
    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
}
