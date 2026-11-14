//! Crafted TCP payload and control-packet send paths for Linux.
//!
//! Operations snapshot TCP_REPAIR state before emitting raw packets for sequence
//! overlap, flagged payload, or reset behavior.

use std::io;
use std::net::TcpStream;
use std::os::fd::{AsFd, AsRawFd};

use crate::detect_default_ttl;
use crate::fragmentation::finish_fragment_send;
use crate::linux::raw_packet::fake_tcp::mutate_fake_timestamp;
use crate::linux::raw_packet::packet_builder::{
    apply_tcp_flag_overrides_to_packet, build_error_to_io, build_ordered_tcp_packets, build_seqovl_tcp_packet,
    build_tcp_segment_packet, fragment_identification, resolve_raw_ttl, validate_explicit_ipv4_identifications,
    validate_ordered_tcp_packet_sizes,
};
use crate::linux::raw_packet::raw_socket::{open_raw_socket, raw_send_target, send_raw_packets};
use crate::linux::socket_options::{get_stream_ttl, get_u32_sockopt};
use crate::linux::tcp_info::{read_tcp_info, wait_tcp_stage_fd};
use crate::linux::tcp_repair::{
    TCP_NO_QUEUE, TCP_REPAIR_ON, disable_tcp_repair, finish_tcp_repair, queue_payload_as_sent, set_tcp_repair,
    set_tcp_repair_queue, snapshot_tcp_repair_state,
};
use crate::{OrderedTcpSegment, TcpFlagOverrides, TcpStageWait};

pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    validate_explicit_ipv4_identifications(source, target, ipv4_identification.as_slice())?;
    let ttl = fake_rst_ttl(get_stream_ttl(stream)?, default_ttl)?;
    let fd = stream.as_raw_fd();

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;
        let mut packet = ripdpi_ipfrag::build_fake_rst_packet(&ripdpi_ipfrag::TcpFragmentSpec {
            src: source,
            dst: target,
            ttl,
            identification: ipv4_identification.map_or_else(|| fragment_identification(source, target, 0), u32::from),
            sequence_number: snapshot.sequence_number,
            acknowledgment_number: snapshot.acknowledgment_number,
            window_size: 0,
            timestamp: None,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        })
        .map_err(build_error_to_io)?;
        apply_tcp_flag_overrides_to_packet(&mut packet, source, target, 0, flags)?;
        send_raw_packets(target, [packet.as_slice()], protect_path, stream.as_fd())
    })();
    let queue_restoration = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let repair_restoration = disable_tcp_repair(fd);
    finish_fragment_send(result, queue_restoration.and(repair_restoration))
}

/// The RST carries the exact live sequence numbers, so a copy that reaches
/// the server resets the connection. Refuse when the caller did not lower the
/// socket TTL below the default. A lowered TTL does not prove the reset expires
/// in transit: on a short path it can still reach the server.
fn fake_rst_ttl(socket_ttl: u8, default_ttl: u8) -> io::Result<u8> {
    let default_ttl = if default_ttl != 0 { default_ttl } else { detect_default_ttl().unwrap_or(64) };
    if socket_ttl >= default_ttl {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "fake RST needs a socket TTL below the default; refusing a reset sent at the default TTL",
        ));
    }
    Ok(socket_ttl)
}

#[allow(clippy::too_many_arguments)]
pub fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    _default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ipv4_identifications: &[u16],
    wait: TcpStageWait,
) -> io::Result<()> {
    let genuine_payload = genuine_ordered_payload(segments, original_payload_len)?;
    if segments.is_empty() {
        return Ok(());
    }

    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    validate_explicit_ipv4_identifications(source, target, ipv4_identifications)?;
    let fd = stream.as_raw_fd();
    let socket = open_raw_socket(target, protect_path, stream.as_fd(), false)?;
    let destination = raw_send_target(target);
    socket.connect(&destination)?;
    let (level, option) =
        if target.is_ipv4() { (libc::IPPROTO_IP, libc::IP_MTU) } else { (libc::IPPROTO_IPV6, libc::IPV6_MTU) };
    let path_mtu = get_u32_sockopt(socket.as_raw_fd(), level, option)?;

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;
        let fake_timestamp = if segments.iter().any(|segment| segment.use_fake_timestamp) {
            mutate_fake_timestamp(snapshot.options.timestamp, timestamp_delta_ticks)?
        } else {
            snapshot.options.timestamp
        };
        let packets = build_ordered_tcp_packets(
            source,
            target,
            &snapshot,
            segments,
            fake_timestamp,
            md5sig,
            ipv4_identifications,
        )?;
        // Keep exact packet boundaries and IDs; reject the whole batch before its first send.
        validate_ordered_tcp_packet_sizes(segments, &packets, &snapshot, path_mtu)?;

        // Once a raw send is attempted, an error cannot prove that no packet left.
        (|| -> io::Result<()> {
            for packet in &packets {
                socket.send_to(packet, &destination)?;
            }
            if original_payload_len != 0 {
                queue_payload_as_sent(stream, &genuine_payload)?;
            }
            set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
            disable_tcp_repair(fd)?;
            wait_tcp_stage_fd(fd, wait.0, wait.1)
        })()
        .map_err(io::Error::other)
    })();
    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    finish_tcp_repair(result, disable_tcp_repair(fd))
}

/// Rebuilds the real `[0, len)` bytes from the genuine segments, the ones
/// without the fake timestamp, so the kernel can queue them for
/// retransmission.
fn genuine_ordered_payload(segments: &[OrderedTcpSegment<'_>], len: usize) -> io::Result<Vec<u8>> {
    let mut available = 0_usize;
    for segment in segments.iter().filter(|segment| !segment.use_fake_timestamp) {
        if segment.sequence_offset.checked_add(segment.payload.len()).is_none_or(|end| end > len) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "genuine TCP segment exceeds the original payload",
            ));
        }
        available = available
            .checked_add(segment.payload.len())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "genuine TCP payload length overflow"))?;
    }
    if len > available {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "genuine TCP segments must cover the original payload",
        ));
    }

    // The advertised length cannot allocate more memory than the received genuine bytes.
    let mut payload = Vec::new();
    let mut covered = Vec::new();
    payload.try_reserve_exact(len).and_then(|()| covered.try_reserve_exact(len)).map_err(io::Error::other)?;
    payload.resize(len, 0_u8);
    covered.resize(len, false);
    for segment in segments.iter().filter(|segment| !segment.use_fake_timestamp) {
        let end = segment.sequence_offset + segment.payload.len();
        payload[segment.sequence_offset..end].copy_from_slice(segment.payload);
        covered[segment.sequence_offset..end].fill(true);
    }
    if covered.contains(&false) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "genuine TCP segments must cover the original payload",
        ));
    }
    Ok(payload)
}

pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if payload.is_empty() {
        return Ok(());
    }
    if flags.is_empty() {
        use std::io::Write;
        (&*stream).write_all(payload)?;
        return Ok(());
    }

    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    validate_explicit_ipv4_identifications(source, target, ipv4_identification.as_slice())?;
    let ttl = get_stream_ttl(stream).unwrap_or_else(|_| resolve_raw_ttl(default_ttl));
    let fd = stream.as_raw_fd();

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;
        let packet = build_tcp_segment_packet(
            source,
            target,
            ttl,
            ipv4_identification.map_or_else(|| fragment_identification(source, target, payload.len()), u32::from),
            snapshot.sequence_number,
            snapshot.acknowledgment_number,
            snapshot.window_size,
            snapshot.options.timestamp,
            true,
            payload,
            md5sig,
            flags,
        )?;
        send_raw_packets(target, std::iter::once(packet.as_slice()), protect_path, stream.as_fd())?;
        queue_payload_as_sent(stream, payload)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}

pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if real_chunk.is_empty() {
        return Ok(());
    }

    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    validate_explicit_ipv4_identifications(source, target, ipv4_identification.as_slice())?;
    let ttl = get_stream_ttl(stream).unwrap_or_else(|_| resolve_raw_ttl(default_ttl));
    let fd = stream.as_raw_fd();

    let path_mtu = read_tcp_info(fd)?.and_then(|info| (info.tcpi_pmtu != 0).then_some(info.tcpi_pmtu));
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let mut emission_possible = false;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;

        let packet = build_seqovl_tcp_packet(
            source,
            target,
            ttl,
            &snapshot,
            real_chunk,
            fake_prefix,
            md5sig,
            flags,
            ipv4_identification,
            path_mtu,
        )?;
        let socket = open_raw_socket(target, protect_path, stream.as_fd(), false)?;
        let destination = raw_send_target(target);
        emission_possible = true;
        socket.send_to(&packet, &destination).map_err(|error| seqovl_send_error(error, &mut emission_possible))?;
        queue_payload_as_sent(stream, real_chunk)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    finish_seqovl_send(result, disable_tcp_repair(fd), emission_possible)
}

fn finish_seqovl_send(
    operation: io::Result<()>,
    repair_restoration: io::Result<()>,
    emission_possible: bool,
) -> io::Result<()> {
    // A raw attempt or repair-queue write can leave genuine bytes committed.
    let operation = if emission_possible { operation.map_err(io::Error::other) } else { operation };
    finish_tcp_repair(operation, repair_restoration)
}

fn seqovl_send_error(error: io::Error, emission_possible: &mut bool) -> io::Error {
    if error.raw_os_error() == Some(libc::EMSGSIZE) {
        // This single datagram was not emitted; queue state is still untouched.
        *emission_possible = false;
        io::Error::new(io::ErrorKind::InvalidInput, error)
    } else {
        error
    }
}

#[cfg(test)]
mod tests {
    use super::{build_ordered_tcp_packets, finish_seqovl_send, genuine_ordered_payload, seqovl_send_error};
    use crate::linux::tcp_repair::{TcpRepairOptionsSnapshot, TcpRepairSnapshot, TcpRepairWindow};
    use crate::{OrderedTcpSegment, TcpFlagOverrides};

    fn segment(payload: &[u8], sequence_offset: usize, use_fake_timestamp: bool) -> OrderedTcpSegment<'_> {
        OrderedTcpSegment { payload, ttl: 64, flags: TcpFlagOverrides::default(), sequence_offset, use_fake_timestamp }
    }

    fn checksum(bytes: &[u8]) -> u16 {
        let mut sum = bytes
            .chunks(2)
            .map(|part| u32::from(u16::from_be_bytes([part[0], *part.get(1).unwrap_or(&0)])))
            .sum::<u32>();
        while sum > 0xffff {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        !(sum as u16)
    }

    #[test]
    fn build_ordered_tcp_packets_keeps_md5_off_genuine_segments() {
        let snapshot = TcpRepairSnapshot {
            sequence_number: 100,
            acknowledgment_number: 200,
            window_size: 1024,
            repair_window: TcpRepairWindow::default(),
            options: TcpRepairOptionsSnapshot::default(),
        };
        let segments = [segment(b"fake", 0, true), segment(b"real", 0, false)];
        for (source, target) in [("192.0.2.1:12345", "198.51.100.2:443"), ("[2001:db8::1]:12345", "[2001:db8::2]:443")]
        {
            for md5 in [false, true] {
                let packets = build_ordered_tcp_packets(
                    source.parse().unwrap(),
                    target.parse().unwrap(),
                    &snapshot,
                    &segments,
                    None,
                    md5,
                    &[1, 2],
                )
                .expect("build ordered packets");
                for (index, packet) in packets.iter().enumerate() {
                    let ipv4 = packet[0] >> 4 == 4;
                    let ip_len = if ipv4 { usize::from(packet[0] & 15) * 4 } else { 40 };
                    let tcp = &packet[ip_len..];
                    let tcp_header_len = usize::from(tcp[12] >> 4) * 4;
                    let mut option = 20;
                    let mut has_md5 = false;
                    while option < tcp_header_len && tcp[option] != 0 {
                        if tcp[option] == 1 {
                            option += 1;
                            continue;
                        }
                        if tcp[option] == 19 {
                            assert_eq!(tcp[option + 1], 18);
                            has_md5 = true;
                        }
                        option += usize::from(tcp[option + 1]);
                    }
                    assert_eq!(has_md5, md5 && index == 0, "genuine packets must not carry fake MD5");
                    assert_eq!(&tcp[tcp_header_len..], if index == 0 { b"fake" } else { b"real" });
                    assert_eq!(u32::from_be_bytes(tcp[4..8].try_into().unwrap()), 100);
                    let mut pseudo = Vec::new();
                    if ipv4 {
                        assert_eq!(checksum(&packet[..ip_len]), 0);
                        assert_eq!(usize::from(u16::from_be_bytes(packet[2..4].try_into().unwrap())), packet.len());
                        pseudo.extend_from_slice(&packet[12..20]);
                        pseudo.extend_from_slice(&[0, 6]);
                        pseudo.extend_from_slice(&(tcp.len() as u16).to_be_bytes());
                    } else {
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

    #[test]
    fn seqovl_post_attempt_errors_are_terminal_even_after_successful_restore() {
        for kind in [
            std::io::ErrorKind::WouldBlock,
            std::io::ErrorKind::InvalidInput,
            std::io::ErrorKind::PermissionDenied,
            std::io::ErrorKind::Unsupported,
        ] {
            let error = finish_seqovl_send(Err(std::io::Error::new(kind, "send or repair queue failed")), Ok(()), true)
                .expect_err("an attempted send cannot be replayed");
            assert_eq!(error.kind(), std::io::ErrorKind::Other);
            let original =
                error.get_ref().and_then(|error| error.downcast_ref::<std::io::Error>()).expect("original send error");
            assert_eq!(original.kind(), kind);
            assert_eq!(original.to_string(), "send or repair queue failed");
        }
    }

    #[test]
    fn seqovl_failed_restore_blocks_preflight_fallback_and_keeps_both_errors() {
        let error = finish_seqovl_send(
            Err(std::io::Error::new(std::io::ErrorKind::WouldBlock, "inbound queue is not empty")),
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "repair off denied")),
            false,
        )
        .expect_err("a socket with unknown repair state cannot accept a fallback write");
        assert_eq!(error.kind(), std::io::ErrorKind::Other);
        assert!(error.to_string().contains("inbound queue is not empty"));
        assert!(error.to_string().contains("repair off denied"));
    }

    #[test]
    fn seqovl_restored_preflight_refusal_remains_fallback_safe() {
        let error = finish_seqovl_send(Err(std::io::Error::from(std::io::ErrorKind::WouldBlock)), Ok(()), false)
            .expect_err("preflight refused before any raw send");
        assert_eq!(error.kind(), std::io::ErrorKind::WouldBlock);
        assert!(finish_seqovl_send(Ok(()), Ok(()), true).is_ok());
        assert!(
            finish_seqovl_send(Ok(()), Err(std::io::Error::from(std::io::ErrorKind::PermissionDenied)), true,).is_err()
        );
    }

    #[test]
    fn seqovl_atomic_size_refusal_is_safe_only_after_successful_restore() {
        let mut emission_possible = true;
        let error = seqovl_send_error(std::io::Error::from_raw_os_error(libc::EMSGSIZE), &mut emission_possible);
        assert!(!emission_possible);
        let error = finish_seqovl_send(Err(error), Ok(()), emission_possible).expect_err("size refusal");
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        let mut emission_possible = true;
        let error = seqovl_send_error(std::io::Error::from_raw_os_error(libc::EMSGSIZE), &mut emission_possible);
        let error = finish_seqovl_send(
            Err(error),
            Err(std::io::Error::from(std::io::ErrorKind::PermissionDenied)),
            emission_possible,
        )
        .expect_err("repair failure remains terminal");
        assert_eq!(error.kind(), std::io::ErrorKind::Other);
    }

    #[test]
    fn seqovl_unknown_send_error_never_becomes_safe() {
        for error in [
            std::io::Error::from_raw_os_error(libc::EPERM),
            std::io::Error::from(std::io::ErrorKind::WouldBlock),
            std::io::Error::other(std::io::Error::from_raw_os_error(libc::EMSGSIZE)),
        ] {
            let mut emission_possible = true;
            let error = seqovl_send_error(error, &mut emission_possible);
            assert!(emission_possible);
            let error = finish_seqovl_send(Err(error), Ok(()), emission_possible).expect_err("uncertain send");
            assert_eq!(error.kind(), std::io::ErrorKind::Other);
        }
    }

    #[test]
    fn genuine_ordered_payload_keeps_overlaps_and_ignores_fake_segments() {
        let segments = [segment(b"ab", 0, false), segment(b"fake", usize::MAX, true), segment(b"bc", 1, false)];

        assert_eq!(genuine_ordered_payload(&segments, 3).expect("covered genuine bytes"), b"abc");
    }

    #[test]
    fn genuine_ordered_payload_accepts_empty_and_fake_only_zero_length_batches() {
        assert!(genuine_ordered_payload(&[], 0).expect("empty batch").is_empty());
        let segments = [segment(b"fake", usize::MAX, true)];
        assert!(genuine_ordered_payload(&segments, 0).expect("fake-only batch").is_empty());
    }
}
