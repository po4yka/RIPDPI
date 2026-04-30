use std::io;
use std::net::{SocketAddr, TcpStream};
use std::os::fd::AsRawFd;

use etherparse::{ip_number, Ipv4Header, Ipv6FlowLabel, Ipv6Header, TcpHeader, TcpHeaderSlice};
use ripdpi_config::{
    TCP_FLAG_ACK, TCP_FLAG_AE, TCP_FLAG_CWR, TCP_FLAG_ECE, TCP_FLAG_FIN, TCP_FLAG_PSH, TCP_FLAG_R1, TCP_FLAG_R2,
    TCP_FLAG_R3, TCP_FLAG_RST, TCP_FLAG_SYN, TCP_FLAG_URG,
};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::linux::mmap_region::{alloc_region, free_region, write_region};
use crate::linux::socket_options::{
    get_stream_ttl, set_c_int_sockopt, set_stream_ttl, set_tcp_md5sig, setsockopt_raw, try_set_stream_ttl_with_outcome,
};
use crate::linux::tcp_info::wait_tcp_stage_fd;
use crate::linux::tcp_repair::{
    build_replacement_tcp_socket, capture_stream_socket_settings, disable_tcp_repair, sequence_after_payload,
    set_tcp_repair, set_tcp_repair_queue, snapshot_tcp_repair_state, swap_stream_to_replacement, TcpTimestampSnapshot,
    TCP_NO_QUEUE, TCP_REPAIR_ON,
};
use crate::{
    CapabilityOutcome, CapabilityUnavailable, FakeTcpOptions, OrderedTcpSegment, TcpFlagOverrides, TcpStageWait,
};

pub(crate) fn probe_raw_socket(
    domain: Domain,
    protocol: libc::c_int,
    protect_path: Option<&str>,
    level: libc::c_int,
    option_name: libc::c_int,
) -> io::Result<()> {
    let socket = Socket::new(domain, Type::RAW, Some(Protocol::from(protocol)))?;
    crate::protect_socket(&socket, protect_path)?;
    set_c_int_sockopt(socket.as_raw_fd(), level, option_name, 1)
}

pub(crate) fn resolve_raw_ttl(default_ttl: u8) -> u8 {
    if default_ttl != 0 {
        default_ttl
    } else {
        64
    }
}

fn apply_tcp_flag_overrides_to_bytes(flags_bytes: &mut [u8], overrides: TcpFlagOverrides) {
    if overrides.is_empty() {
        return;
    }
    let mut upper = flags_bytes[0] & 0xF0;
    let mut reserved = flags_bytes[0] & 0x0F;
    let mut control = flags_bytes[1];

    apply_single_flag(&mut control, overrides, TCP_FLAG_FIN, 0x01);
    apply_single_flag(&mut control, overrides, TCP_FLAG_SYN, 0x02);
    apply_single_flag(&mut control, overrides, TCP_FLAG_RST, 0x04);
    apply_single_flag(&mut control, overrides, TCP_FLAG_PSH, 0x08);
    apply_single_flag(&mut control, overrides, TCP_FLAG_ACK, 0x10);
    apply_single_flag(&mut control, overrides, TCP_FLAG_URG, 0x20);
    apply_single_flag(&mut control, overrides, TCP_FLAG_ECE, 0x40);
    apply_single_flag(&mut control, overrides, TCP_FLAG_CWR, 0x80);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_AE, 0x01);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R1, 0x02);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R2, 0x04);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R3, 0x08);

    upper |= reserved & 0x0F;
    flags_bytes[0] = upper;
    flags_bytes[1] = control;
}

fn apply_single_flag(byte: &mut u8, overrides: TcpFlagOverrides, flag_mask: u16, wire_bit: u8) {
    if (overrides.set & flag_mask) != 0 {
        *byte |= wire_bit;
    }
    if (overrides.unset & flag_mask) != 0 {
        *byte &= !wire_bit;
    }
}

pub(crate) fn apply_tcp_flag_overrides_to_packet(
    packet: &mut [u8],
    source: SocketAddr,
    target: SocketAddr,
    payload_len: usize,
    overrides: TcpFlagOverrides,
) -> io::Result<()> {
    if overrides.is_empty() {
        return Ok(());
    }
    let tcp_offset = match source {
        SocketAddr::V4(_) => Ipv4Header::MIN_LEN,
        SocketAddr::V6(_) => Ipv6Header::LEN,
    };
    if packet.len() < tcp_offset + 20 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "raw TCP packet too short"));
    }
    let flags = &mut packet[tcp_offset + 12..tcp_offset + 14];
    apply_tcp_flag_overrides_to_bytes(flags, overrides);
    packet[tcp_offset + 16] = 0;
    packet[tcp_offset + 17] = 0;
    let header = TcpHeaderSlice::from_slice(&packet[tcp_offset..]).map_err(io::Error::other)?;
    let payload = &packet[packet.len().saturating_sub(payload_len)..];
    let checksum = match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => header
            .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv4 checksum limits"))?,
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => header
            .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv6 checksum limits"))?,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "raw TCP send requires matching source and destination IP families",
            ));
        }
    };
    packet[tcp_offset + 16..tcp_offset + 18].copy_from_slice(&checksum.to_be_bytes());
    Ok(())
}

pub(crate) fn fragment_identification(source: SocketAddr, target: SocketAddr, payload_len: usize) -> u32 {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().subsec_nanos();
    let source_mix = u32::from(source.port()) << 16;
    let target_mix = u32::from(target.port());
    now ^ source_mix ^ target_mix ^ (payload_len as u32)
}

pub(crate) fn build_error_to_io(error: ripdpi_ipfrag::BuildError) -> io::Error {
    match error {
        ripdpi_ipfrag::BuildError::InvalidSplit { .. } => io::Error::new(io::ErrorKind::InvalidInput, error),
        _ => io::Error::new(io::ErrorKind::InvalidData, error),
    }
}

pub(crate) fn value_too_large_io(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

pub(crate) fn send_raw_fragments(
    target: SocketAddr,
    packets: [&[u8]; 2],
    protect_path: Option<&str>,
) -> io::Result<()> {
    send_raw_packets(target, packets, protect_path)
}

pub(crate) fn send_raw_packets<'a, I>(target: SocketAddr, packets: I, protect_path: Option<&str>) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    let socket = match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            socket
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            socket
        }
    };
    let sockaddr = SockAddr::from(target);
    for packet in packets {
        socket.send_to(packet, &sockaddr)?;
    }
    Ok(())
}

pub(crate) fn send_raw_packets_with_delay<'a, I>(
    target: SocketAddr,
    packets: I,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    if inter_segment_delay_ms == 0 {
        return send_raw_packets(target, packets, protect_path);
    }
    let socket = open_raw_socket(target, protect_path)?;
    let sockaddr = SockAddr::from(target);
    let delay = std::time::Duration::from_millis(u64::from(inter_segment_delay_ms));
    let mut first = true;
    for packet in packets {
        if !first {
            std::thread::sleep(delay);
        }
        socket.send_to(packet, &sockaddr)?;
        first = false;
    }
    Ok(())
}

fn open_raw_socket(target: SocketAddr, protect_path: Option<&str>) -> io::Result<Socket> {
    match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            Ok(socket)
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            Ok(socket)
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn build_tcp_segment_packet(
    source: SocketAddr,
    target: SocketAddr,
    ttl: u8,
    identification: u32,
    sequence_number: u32,
    acknowledgment_number: u32,
    window_size: u16,
    timestamp: Option<TcpTimestampSnapshot>,
    push_flag: bool,
    payload: &[u8],
    inject_md5: bool,
    tcp_flags: TcpFlagOverrides,
) -> io::Result<Vec<u8>> {
    let mut tcp = TcpHeader::new(source.port(), target.port(), sequence_number, window_size);
    tcp.ack = true;
    tcp.psh = push_flag && !payload.is_empty();
    tcp.acknowledgment_number = acknowledgment_number;

    let mut raw_opts: Vec<u8> = Vec::new();
    if let Some(timestamp) = timestamp {
        // Noop, Noop, Timestamp (12 bytes total with padding)
        raw_opts.extend_from_slice(&[1, 1]); // 2x Noop
        raw_opts.push(8); // Kind=Timestamp
        raw_opts.push(10); // Length=10
        raw_opts.extend_from_slice(&timestamp.value.to_be_bytes());
        raw_opts.extend_from_slice(&timestamp.echo_reply.to_be_bytes());
    }
    if inject_md5 {
        // Noop padding before MD5 for 4-byte alignment
        let padding_needed = (4 - (raw_opts.len() % 4)) % 4;
        raw_opts.extend(std::iter::repeat_n(1u8, padding_needed)); // Noop
        raw_opts.push(19); // Kind=MD5 Signature (RFC 2385)
        raw_opts.push(18); // Length=18 (2 header + 16 signature)
                           // Random 16-byte signature (deterministic from seq for reproducibility)
        let seed = sequence_number;
        for i in 0u32..4 {
            raw_opts.extend_from_slice(&seed.wrapping_add(i).wrapping_mul(2654435761).to_be_bytes());
        }
    }
    if !raw_opts.is_empty() {
        // Pad to 4-byte boundary
        while !raw_opts.len().is_multiple_of(4) {
            raw_opts.push(0); // End-of-options
        }
        tcp.set_options_raw(&raw_opts)
            .map_err(|_| value_too_large_io("TCP options exceed supported raw packet size"))?;
    }

    match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv4 checksum limits"))?;
            let payload_length = u16::try_from(tcp.header_len() + payload.len())
                .map_err(|_| value_too_large_io("IPv4 packet too large"))?;
            let mut ip = Ipv4Header::new(payload_length, ttl, ip_number::TCP, src.ip().octets(), dst.ip().octets())
                .map_err(|_| value_too_large_io("IPv4 packet too large"))?;
            ip.identification = identification as u16;
            ip.dont_fragment = false;
            ip.more_fragments = false;
            ip.header_checksum = ip.calc_header_checksum();

            let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + tcp.header_len() + payload.len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            bytes.extend_from_slice(payload);
            apply_tcp_flag_overrides_to_packet(&mut bytes, source, target, payload.len(), tcp_flags)?;
            Ok(bytes)
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv6 checksum limits"))?;
            let payload_length = u16::try_from(tcp.header_len() + payload.len())
                .map_err(|_| value_too_large_io("IPv6 packet too large"))?;
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length,
                next_header: ip_number::TCP,
                hop_limit: ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };

            let mut bytes = Vec::with_capacity(Ipv6Header::LEN + tcp.header_len() + payload.len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            bytes.extend_from_slice(payload);
            apply_tcp_flag_overrides_to_packet(&mut bytes, source, target, payload.len(), tcp_flags)?;
            Ok(bytes)
        }
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "multidisorder raw TCP send requires matching source and destination IP families",
        )),
    }
}

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
