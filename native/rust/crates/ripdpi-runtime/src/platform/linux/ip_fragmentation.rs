use std::io;
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;

use crate::platform::{IpFragmentationCapabilities, TcpFlagOverrides, TcpPayloadSegment};

use super::*;

pub(crate) fn probe_ip_fragmentation_capabilities(
    protect_path: Option<&str>,
) -> io::Result<IpFragmentationCapabilities> {
    let raw_ipv4 =
        probe_raw_socket(Domain::IPV4, libc::IPPROTO_RAW, protect_path, libc::IPPROTO_IP, libc::IP_HDRINCL).is_ok();
    let raw_ipv6 =
        probe_raw_socket(Domain::IPV6, libc::IPPROTO_RAW, protect_path, libc::IPPROTO_IPV6, libc::IPV6_HDRINCL).is_ok();
    let tcp_repair = probe_tcp_repair(protect_path).is_ok();
    Ok(IpFragmentationCapabilities { raw_ipv4, raw_ipv6, tcp_repair })
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ip_fragmented_udp(
    upstream: &UdpSocket,
    target: SocketAddr,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    let source = upstream.local_addr()?;
    let ttl = resolve_raw_ttl(default_ttl);
    let pair = build_udp_fragment_pair(
        UdpFragmentSpec {
            src: source,
            dst: target,
            ttl,
            identification: ipv4_identification
                .map_or_else(|| fragment_identification(source, target, payload.len()), u32::from),
            ipv6_ext,
        },
        payload,
        split_offset,
    )
    .map_err(build_error_to_io)?;
    if disorder {
        send_raw_fragments(target, [&pair.second, &pair.first], protect_path)
    } else {
        send_raw_fragments(target, [&pair.first, &pair.second], protect_path)
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if payload.is_empty() {
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

        let pair = build_tcp_fragment_pair(
            TcpFragmentSpec {
                src: source,
                dst: target,
                ttl,
                identification: ipv4_identification
                    .map_or_else(|| fragment_identification(source, target, payload.len()), u32::from),
                sequence_number: snapshot.sequence_number,
                acknowledgment_number: snapshot.acknowledgment_number,
                window_size: snapshot.window_size,
                timestamp: snapshot
                    .options
                    .timestamp
                    .map(|timestamp| TcpTimestampOption { value: timestamp.value, echo_reply: timestamp.echo_reply }),
                tcp_flags_set: flags.set,
                tcp_flags_unset: flags.unset,
                ipv6_ext,
            },
            payload,
            split_offset,
        )
        .map_err(build_error_to_io)?;

        let replacement = build_replacement_tcp_socket(source, target, payload.len(), &snapshot, protect_path)?;
        if disorder {
            send_raw_fragments(target, [&pair.second, &pair.first], protect_path)?;
        } else {
            send_raw_fragments(target, [&pair.first, &pair.second], protect_path)?;
        }
        swap_stream_to_replacement(stream, &replacement, settings)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identifications: &[u16],
) -> io::Result<()> {
    if payload.is_empty() {
        return Ok(());
    }
    if segments.len() < 3 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "multidisorder requires at least three non-empty TCP segments",
        ));
    }

    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let ttl = get_stream_ttl(stream).unwrap_or_else(|_| resolve_raw_ttl(default_ttl));
    let fd = stream.as_raw_fd();
    let settings = capture_stream_socket_settings(stream);

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;
        let packets = build_multi_disorder_packets(
            source,
            target,
            ttl,
            payload,
            segments,
            &snapshot,
            md5sig,
            flags,
            ipv4_identifications,
        )?;
        let replacement = build_replacement_tcp_socket(source, target, payload.len(), &snapshot, protect_path)?;
        send_raw_packets_with_delay(
            target,
            packets.iter().rev().map(Vec::as_slice),
            protect_path,
            inter_segment_delay_ms,
        )?;
        swap_stream_to_replacement(stream, &replacement, settings)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
        disable_tcp_repair(fd)
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}

#[allow(clippy::too_many_arguments)]
pub(super) fn build_multi_disorder_packets(
    source: SocketAddr,
    target: SocketAddr,
    ttl: u8,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    snapshot: &TcpRepairSnapshot,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identifications: &[u16],
) -> io::Result<Vec<Vec<u8>>> {
    let mut cursor = 0usize;
    let base_identification = fragment_identification(source, target, payload.len());
    let mut packets = Vec::with_capacity(segments.len());

    for (index, segment) in segments.iter().enumerate() {
        if segment.start != cursor || segment.end <= segment.start || segment.end > payload.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid multidisorder TCP payload segments"));
        }
        let sequence_number = sequence_after_payload(snapshot.sequence_number, segment.start)?;
        packets.push(build_tcp_segment_packet(
            source,
            target,
            ttl,
            ipv4_identifications
                .get(index)
                .copied()
                .map_or_else(|| base_identification.wrapping_add(index as u32), u32::from),
            sequence_number,
            snapshot.acknowledgment_number,
            snapshot.window_size,
            snapshot.options.timestamp,
            segment.end == payload.len(),
            &payload[segment.start..segment.end],
            md5sig,
            flags,
        )?);
        cursor = segment.end;
    }

    if cursor != payload.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "multidisorder TCP payload segments must cover the full payload",
        ));
    }

    Ok(packets)
}
