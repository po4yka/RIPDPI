//! Crafted TCP payload and control-packet send paths for Linux.
//!
//! Operations snapshot TCP_REPAIR state before emitting raw packets for sequence
//! overlap, flagged payload, or reset behavior.

use std::io;
use std::net::TcpStream;
use std::os::fd::{AsFd, AsRawFd};

use crate::detect_default_ttl;
use crate::linux::raw_packet::fake_tcp::mutate_fake_timestamp;
use crate::linux::raw_packet::packet_builder::{
    apply_tcp_flag_overrides_to_packet, build_error_to_io, build_ordered_tcp_packets, build_tcp_segment_packet,
    fragment_identification, resolve_raw_ttl, validate_explicit_ipv4_identifications,
};
use crate::linux::raw_packet::raw_socket::send_raw_packets;
use crate::linux::socket_options::get_stream_ttl;
use crate::linux::tcp_info::wait_tcp_stage_fd;
use crate::linux::tcp_repair::{
    TCP_NO_QUEUE, TCP_REPAIR_ON, disable_tcp_repair, queue_payload_as_sent, set_tcp_repair, set_tcp_repair_queue,
    snapshot_tcp_repair_state,
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
    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
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

        // Once a raw send is attempted, an error cannot prove that no packet left.
        (|| -> io::Result<()> {
            send_raw_packets(target, packets.iter().map(Vec::as_slice), protect_path, stream.as_fd())?;
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
    finish_ordered_send(result, disable_tcp_repair(fd))
}

#[derive(Debug)]
struct OrderedSendRestoreError {
    operation: Option<io::Error>,
    restoration: io::Error,
}

impl std::fmt::Display for OrderedSendRestoreError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "TCP_REPAIR restoration failed: {}", self.restoration)?;
        if let Some(operation) = &self.operation {
            write!(formatter, "; ordered send failed: {operation}")?;
        }
        Ok(())
    }
}

impl std::error::Error for OrderedSendRestoreError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.operation.as_ref().unwrap_or(&self.restoration))
    }
}

fn finish_ordered_send(operation: io::Result<()>, repair_restoration: io::Result<()>) -> io::Result<()> {
    match repair_restoration {
        Ok(()) => operation,
        Err(restoration) => Err(io::Error::other(OrderedSendRestoreError { operation: operation.err(), restoration })),
    }
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

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;

        let overlap_seq = snapshot.sequence_number.wrapping_sub(fake_prefix.len() as u32);
        let mut overlap_payload = Vec::with_capacity(fake_prefix.len() + real_chunk.len());
        overlap_payload.extend_from_slice(fake_prefix);
        overlap_payload.extend_from_slice(real_chunk);

        let identification = ipv4_identification.map_or(snapshot.sequence_number, u32::from);
        let packet = build_tcp_segment_packet(
            source,
            target,
            ttl,
            identification,
            overlap_seq,
            snapshot.acknowledgment_number,
            snapshot.window_size,
            snapshot.options.timestamp,
            true,
            &overlap_payload,
            md5sig,
            flags,
        )?;
        send_raw_packets(target, std::iter::once(packet.as_slice()), protect_path, stream.as_fd())?;
        queue_payload_as_sent(stream, real_chunk)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}

#[cfg(test)]
mod tests {
    use super::{build_ordered_tcp_packets, finish_ordered_send, genuine_ordered_payload};
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
    fn ordered_send_restoration_failure_is_terminal_and_keeps_original_error() {
        let error = finish_ordered_send(
            Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "raw preflight unavailable")),
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "repair off denied")),
        )
        .expect_err("uncertain repair state must prevent fallback");
        assert_eq!(error.kind(), std::io::ErrorKind::Other);
        assert!(error.to_string().contains("repair off denied"));
        assert!(error.to_string().contains("raw preflight unavailable"));
        assert_eq!(
            std::error::Error::source(&error).expect("original operation error").to_string(),
            "raw preflight unavailable"
        );
    }

    #[test]
    fn ordered_send_restoration_success_preserves_preflight_kind() {
        let error = finish_ordered_send(Err(std::io::Error::from(std::io::ErrorKind::WouldBlock)), Ok(()))
            .expect_err("preflight refused");
        assert_eq!(error.kind(), std::io::ErrorKind::WouldBlock);
        assert!(finish_ordered_send(Ok(()), Ok(())).is_ok());
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
