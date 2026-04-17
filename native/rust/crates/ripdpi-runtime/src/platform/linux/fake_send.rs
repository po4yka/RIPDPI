use std::io;
use std::net::TcpStream;
use std::os::fd::AsRawFd;

use crate::platform::{FakeTcpOptions, OrderedTcpSegment, TcpFlagOverrides, TcpStageWait};

use super::*;

pub(crate) fn send_fake_rst(
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

pub(crate) fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: FakeTcpOptions<'_>,
    wait: TcpStageWait,
) -> io::Result<()> {
    if original_prefix.is_empty() {
        return Ok(());
    }

    let requires_exact_raw_path =
        !options.fake_flags.is_empty() || !options.orig_flags.is_empty() || options.require_raw_path;
    if requires_exact_raw_path || options.secondary_fake_prefix.is_some() || options.timestamp_delta_ticks.is_some() {
        match send_fake_tcp_via_raw_packets(stream, original_prefix, fake_prefix, ttl, md5sig, options, wait) {
            Ok(()) => return Ok(()),
            Err(error) if requires_exact_raw_path => return Err(error),
            Err(error) if should_fallback_raw_fake_tcp(error.kind()) => {
                tracing::debug!("falling back to stream fake TCP path after raw fake downgrade: {error}");
            }
            Err(error) => return Err(error),
        }
    }

    let fd = stream.as_raw_fd();
    let region_len = original_prefix.len().max(fake_prefix.len());
    let region = alloc_region(region_len)?;

    let restore_ttl = if default_ttl != 0 { default_ttl } else { get_stream_ttl(stream).unwrap_or(64) };

    let result = (|| {
        write_region(region, fake_prefix, region_len);

        let (pipe_r, pipe_w) = nix::unistd::pipe().map_err(io::Error::from)?;

        match try_set_stream_ttl_with_outcome(stream, ttl) {
            CapabilityOutcome::Available(()) => {
                tracing::debug!(ttl = ttl, size = original_prefix.len(), "send_fake_tcp: fake packet with custom TTL");
            }
            CapabilityOutcome::Unavailable { reason, .. } => {
                let os_err = match reason {
                    CapabilityUnavailable::PermissionDenied => libc::EPERM,
                    _ => libc::ENOPROTOOPT,
                };
                tracing::warn!(
                    ttl = ttl,
                    reason = ?reason,
                    "send_fake_tcp: TTL write unavailable on this platform (capability: ttl_write)"
                );
                return Err(io::Error::from_raw_os_error(os_err));
            }
            CapabilityOutcome::ProbeFailed { error, .. } => {
                return Err(io::Error::other(error));
            }
        }
        if md5sig {
            set_tcp_md5sig(stream, 5)?;
        }

        let iov = libc::iovec { iov_base: region.cast(), iov_len: original_prefix.len() };
        let queued = unsafe { libc::vmsplice(pipe_w.as_raw_fd(), &iov, 1, 0) };
        if queued < 0 {
            return Err(io::Error::last_os_error());
        }
        if queued as usize != original_prefix.len() {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "partial vmsplice during fake tcp send"));
        }

        let mut moved = 0usize;
        while moved < original_prefix.len() {
            let chunk = nix::fcntl::splice(
                &pipe_r,
                None,
                stream,
                None,
                original_prefix.len() - moved,
                nix::fcntl::SpliceFFlags::empty(),
            )
            .map_err(io::Error::from)?;
            if chunk == 0 {
                return Err(io::Error::new(io::ErrorKind::WriteZero, "partial splice during fake tcp send"));
            }
            moved += chunk;
        }

        wait_tcp_stage_fd(fd, wait.0, wait.1)?;
        if md5sig {
            set_tcp_md5sig(stream, 0)?;
        }
        set_stream_ttl(stream, restore_ttl)?;
        Ok(())
    })();

    if md5sig {
        let _ = set_tcp_md5sig(stream, 0);
    }
    let _ = set_stream_ttl(stream, restore_ttl);
    free_region(region, region_len);
    result
}

fn should_fallback_raw_fake_tcp(kind: io::ErrorKind) -> bool {
    matches!(
        kind,
        io::ErrorKind::Unsupported
            | io::ErrorKind::PermissionDenied
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::InvalidInput
    )
}

fn send_fake_tcp_via_raw_packets(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    options: FakeTcpOptions<'_>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let fd = stream.as_raw_fd();
    let settings = capture_stream_socket_settings(stream);

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;

        let timestamp = mutate_fake_timestamp(snapshot.options.timestamp, options.timestamp_delta_ticks)?;
        let mut packets = Vec::with_capacity(
            1 + usize::from(options.secondary_fake_prefix.is_some())
                + usize::from(options.force_raw_original || !options.orig_flags.is_empty()),
        );
        let mut identifications = options.ipv4_identifications.iter().copied();
        packets.push(build_tcp_segment_packet(
            source,
            target,
            ttl,
            identifications
                .next()
                .map_or_else(|| fragment_identification(source, target, original_prefix.len()), u32::from),
            snapshot.sequence_number,
            snapshot.acknowledgment_number,
            snapshot.window_size,
            timestamp,
            true,
            fake_prefix,
            md5sig,
            options.fake_flags,
        )?);
        if let Some(secondary_fake_prefix) = options.secondary_fake_prefix.filter(|payload| !payload.is_empty()) {
            packets.push(build_tcp_segment_packet(
                source,
                target,
                ttl,
                identifications
                    .next()
                    .map_or_else(|| fragment_identification(source, target, secondary_fake_prefix.len()), u32::from),
                snapshot.sequence_number,
                snapshot.acknowledgment_number,
                snapshot.window_size,
                timestamp,
                true,
                secondary_fake_prefix,
                md5sig,
                options.fake_flags,
            )?);
        }

        if options.orig_flags.is_empty() && !options.force_raw_original {
            send_raw_packets(target, packets.iter().map(Vec::as_slice), options.protect_path)?;
            use std::io::Write;
            (&*stream).write_all(original_prefix)?;
        } else {
            let original_packet = build_tcp_segment_packet(
                source,
                target,
                ttl,
                identifications
                    .next()
                    .map_or_else(|| fragment_identification(source, target, original_prefix.len()), u32::from),
                snapshot.sequence_number,
                snapshot.acknowledgment_number,
                snapshot.window_size,
                snapshot.options.timestamp,
                true,
                original_prefix,
                md5sig,
                options.orig_flags,
            )?;
            packets.push(original_packet);
            let replacement =
                build_replacement_tcp_socket(source, target, original_prefix.len(), &snapshot, options.protect_path)?;
            send_raw_packets(target, packets.iter().map(Vec::as_slice), options.protect_path)?;
            swap_stream_to_replacement(stream, &replacement, settings)?;
            set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;
            disable_tcp_repair(fd)?;
        }
        wait_tcp_stage_fd(fd, wait.0, wait.1)
    })();
    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ordered_tcp_segments(
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

pub(super) fn mutate_fake_timestamp(
    timestamp: Option<TcpTimestampSnapshot>,
    delta_ticks: Option<i32>,
) -> io::Result<Option<TcpTimestampSnapshot>> {
    let Some(delta_ticks) = delta_ticks else {
        return Ok(timestamp);
    };
    let Some(mut timestamp) = timestamp else {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "fake TCP timestamp corruption requires negotiated TCP timestamps",
        ));
    };
    timestamp.value = if delta_ticks >= 0 {
        timestamp.value.wrapping_add(delta_ticks as u32)
    } else {
        timestamp.value.wrapping_sub(delta_ticks.unsigned_abs())
    };
    Ok(Some(timestamp))
}

pub(crate) fn send_flagged_tcp_payload(
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

pub(crate) fn send_seqovl_tcp(
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
