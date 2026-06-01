//! Crafted TCP payload and control-packet send paths for Linux.
//!
//! Operations snapshot TCP_REPAIR state before emitting raw packets for sequence
//! overlap, flagged payload, or reset behavior.

use std::io;
use std::net::TcpStream;
use std::os::fd::AsRawFd;

use crate::linux::raw_packet::fake_tcp::mutate_fake_timestamp;
use crate::linux::raw_packet::packet_builder::{
    apply_tcp_flag_overrides_to_packet, build_error_to_io, build_tcp_segment_packet, fragment_identification,
    resolve_raw_ttl,
};
use crate::linux::raw_packet::raw_socket::send_raw_packets;
use crate::linux::socket_options::get_stream_ttl;
use crate::linux::tcp_info::wait_tcp_stage_fd;
use crate::linux::tcp_repair::{
    TCP_NO_QUEUE, TCP_REPAIR_ON, build_replacement_tcp_socket, capture_stream_socket_settings, disable_tcp_repair,
    sequence_after_payload, set_tcp_repair, set_tcp_repair_queue, snapshot_tcp_repair_state,
    swap_stream_to_replacement,
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
    let ttl = get_stream_ttl(stream).unwrap_or_else(|_| resolve_raw_ttl(default_ttl));
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
        send_raw_packets(target, [packet.as_slice()], protect_path)
    })();
    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
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
    if segments.is_empty() {
        return Ok(());
    }

    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let fd = stream.as_raw_fd();
    let settings = capture_stream_socket_settings(stream);

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;
        let fake_timestamp = if segments.iter().any(|segment| segment.use_fake_timestamp) {
            mutate_fake_timestamp(snapshot.options.timestamp, timestamp_delta_ticks)?
        } else {
            snapshot.options.timestamp
        };
        let mut packets = Vec::with_capacity(segments.len());
        let mut identifications = ipv4_identifications.iter().copied();
        for segment in segments {
            let sequence_number = sequence_after_payload(snapshot.sequence_number, segment.sequence_offset)?;
            packets.push(build_tcp_segment_packet(
                source,
                target,
                segment.ttl,
                identifications
                    .next()
                    .map_or_else(|| fragment_identification(source, target, segment.payload.len()), u32::from),
                sequence_number,
                snapshot.acknowledgment_number,
                snapshot.window_size,
                if segment.use_fake_timestamp { fake_timestamp } else { snapshot.options.timestamp },
                true,
                segment.payload,
                md5sig,
                segment.flags,
            )?);
        }

        if original_payload_len == 0 {
            send_raw_packets(target, packets.iter().map(Vec::as_slice), protect_path)?;
            set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
            disable_tcp_repair(fd)?;
            return wait_tcp_stage_fd(fd, wait.0, wait.1);
        }

        let replacement = build_replacement_tcp_socket(source, target, original_payload_len, &snapshot, protect_path)?;
        send_raw_packets(target, packets.iter().map(Vec::as_slice), protect_path)?;
        swap_stream_to_replacement(stream, &replacement, settings)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)?;
        wait_tcp_stage_fd(fd, wait.0, wait.1)
    })();
    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
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
    let ttl = get_stream_ttl(stream).unwrap_or_else(|_| resolve_raw_ttl(default_ttl));
    let fd = stream.as_raw_fd();
    let settings = capture_stream_socket_settings(stream);

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
        let replacement = build_replacement_tcp_socket(source, target, payload.len(), &snapshot, protect_path)?;
        send_raw_packets(target, std::iter::once(packet.as_slice()), protect_path)?;
        swap_stream_to_replacement(stream, &replacement, settings)?;
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
    let ttl = get_stream_ttl(stream).unwrap_or_else(|_| resolve_raw_ttl(default_ttl));
    let fd = stream.as_raw_fd();
    let settings = capture_stream_socket_settings(stream);

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
        send_raw_packets(target, std::iter::once(packet.as_slice()), protect_path)?;

        let replacement = build_replacement_tcp_socket(source, target, real_chunk.len(), &snapshot, protect_path)?;
        swap_stream_to_replacement(stream, &replacement, settings)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}
