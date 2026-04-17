use std::io;
use std::net::TcpStream;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::AsRawFd;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::{
    linux, reserve_ipv4_identifications, reserve_stream_ipv4_identifications, root_helper, FakeTcpOptions,
    OrderedTcpSegment, TcpFlagOverrides, TcpStageWait,
};
#[cfg(not(any(target_os = "linux", target_os = "android")))]
use super::{FakeTcpOptions, OrderedTcpSegment, TcpFlagOverrides, TcpStageWait};

#[cfg(any(target_os = "linux", target_os = "android"))]
fn swap_replacement_fd(target_fd: std::os::fd::RawFd, replacement_fd: std::os::fd::RawFd) -> io::Result<()> {
    linux::dup2_fd(replacement_fd, target_fd)?;
    linux::close_fd(replacement_fd)?;
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst_reserved(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) =
        root_helper::with_root_helper(|h| h.send_fake_rst(stream.as_raw_fd(), default_ttl, flags, ipv4_identification))
    {
        return result;
    }
    linux::send_fake_rst(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst_reserved(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_fake_rst_reserved(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    mut options: FakeTcpOptions<'_>,
    ip_id_mode: Option<IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let supports_ipv4_ids = matches!((source, target), (std::net::SocketAddr::V4(_), std::net::SocketAddr::V4(_)));
    let require_raw_path = supports_ipv4_ids && ip_id_mode.is_some();
    let force_raw_original = matches!(ip_id_mode, Some(IpIdMode::SeqGroup)) && supports_ipv4_ids;
    let packet_count = usize::from(!fake_prefix.is_empty())
        + usize::from(options.secondary_fake_prefix.is_some_and(|payload| !payload.is_empty()))
        + usize::from(force_raw_original || !options.orig_flags.is_empty());
    let ids = if require_raw_path {
        reserve_ipv4_identifications(source, target, ip_id_mode, packet_count)
    } else {
        Vec::new()
    };
    options.require_raw_path = require_raw_path;
    options.force_raw_original = force_raw_original;
    options.ipv4_identifications = ids;
    linux::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, wait)
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _options: FakeTcpOptions<'_>,
    _ip_id_mode: Option<IpIdMode>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ordered_tcp_segments_reserved(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ipv4_identifications: &[u16],
    wait: TcpStageWait,
) -> io::Result<()> {
    linux::send_ordered_tcp_segments(
        stream,
        segments,
        original_payload_len,
        default_ttl,
        protect_path,
        md5sig,
        timestamp_delta_ticks,
        ipv4_identifications,
        wait,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let ipv4_identifications = reserve_ipv4_identifications(source, target, ip_id_mode, segments.len());
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_ordered_tcp_segments(
            stream.as_raw_fd(),
            segments,
            original_payload_len,
            default_ttl,
            md5sig,
            timestamp_delta_ticks,
            &ipv4_identifications,
            wait,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    send_ordered_tcp_segments_reserved(
        stream,
        segments,
        original_payload_len,
        default_ttl,
        protect_path,
        md5sig,
        timestamp_delta_ticks,
        &ipv4_identifications,
        wait,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ordered_tcp_segments_reserved(
    _stream: &TcpStream,
    _segments: &[OrderedTcpSegment<'_>],
    _original_payload_len: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _timestamp_delta_ticks: Option<i32>,
    _ipv4_identifications: &[u16],
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ordered_tcp_segments(
    _stream: &TcpStream,
    _segments: &[OrderedTcpSegment<'_>],
    _original_payload_len: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _timestamp_delta_ticks: Option<i32>,
    _ip_id_mode: Option<IpIdMode>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload_reserved(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res =
            h.send_flagged_tcp_payload(stream.as_raw_fd(), payload, default_ttl, md5sig, flags, ipv4_identification)?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload_reserved(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_flagged_tcp_payload_reserved(stream, payload, default_ttl, protect_path, md5sig, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_seqovl_tcp_reserved(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_seqovl_tcp(
            stream.as_raw_fd(),
            real_chunk,
            fake_prefix,
            default_ttl,
            md5sig,
            flags,
            ipv4_identification,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_seqovl_tcp(
        stream,
        real_chunk,
        fake_prefix,
        default_ttl,
        protect_path,
        md5sig,
        flags,
        ipv4_identification,
    )
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_seqovl_tcp_reserved(
    _stream: &TcpStream,
    _real_chunk: &[u8],
    _fake_prefix: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_seqovl_tcp_reserved(
        stream,
        real_chunk,
        fake_prefix,
        default_ttl,
        protect_path,
        md5sig,
        flags,
        ipv4_identification,
    )
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_seqovl_tcp(
    _stream: &TcpStream,
    _real_chunk: &[u8],
    _fake_prefix: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
