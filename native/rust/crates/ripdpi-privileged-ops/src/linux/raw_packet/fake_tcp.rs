//! Fake TCP injection paths using raw sockets or stream writes.
//!
//! Raw paths capture TCP_REPAIR state, craft packets, and restore socket state
//! around privileged packet sends.

use std::io;
use std::net::TcpStream;
use std::os::fd::{AsFd, AsRawFd};

use ripdpi_capabilities::{CapabilityOutcome, CapabilityUnavailable};

use crate::linux::mmap_region::MmapRegion;
use crate::linux::raw_packet::packet_builder::{build_tcp_segment_packet, fragment_identification};
use crate::linux::raw_packet::raw_socket::send_raw_packets;
use crate::linux::socket_options::{get_stream_ttl, set_stream_ttl, set_tcp_md5sig, try_set_stream_ttl_with_outcome};
use crate::linux::tcp_info::wait_tcp_stage_fd;
use crate::linux::tcp_repair::{
    build_replacement_tcp_socket, capture_stream_socket_settings, disable_tcp_repair, set_tcp_repair,
    set_tcp_repair_queue, snapshot_tcp_repair_state, swap_stream_to_replacement, TcpTimestampSnapshot, TCP_NO_QUEUE,
    TCP_REPAIR_ON,
};
use crate::{FakeTcpOptions, TcpStageWait};

pub fn send_fake_tcp(
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
    let mut region = MmapRegion::new(region_len)?;

    let restore_ttl = if default_ttl != 0 { default_ttl } else { get_stream_ttl(stream).unwrap_or(64) };

    let result = (|| {
        region.write(fake_prefix);

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

        let queued = region.vmsplice_to(pipe_w.as_fd(), original_prefix.len())?;
        if queued != original_prefix.len() {
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
    drop(region);
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

pub(crate) fn mutate_fake_timestamp(
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
