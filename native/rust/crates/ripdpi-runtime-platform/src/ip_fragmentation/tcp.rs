use std::io;
use std::net::TcpStream;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::AsRawFd;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::ipv4_ids::reserve_stream_ipv4_identifications;
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::root_helper;
use super::super::{TcpFlagOverrides, TcpPayloadSegment};
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::replacement_fd::swap_replacement_fd;

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp_reserved(
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
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_ip_fragmented_tcp(
            stream.as_raw_fd(),
            payload,
            split_offset,
            default_ttl,
            disorder,
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

    ripdpi_privileged_ops::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_tcp_reserved(
    _stream: &TcpStream,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_ip_fragmented_tcp_reserved(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_multi_disorder_tcp_reserved(
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
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_multi_disorder_tcp(
            stream.as_raw_fd(),
            payload,
            segments,
            default_ttl,
            inter_segment_delay_ms,
            md5sig,
            flags,
            ipv4_identifications,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }

    ripdpi_privileged_ops::send_multi_disorder_tcp(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
        ipv4_identifications,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_multi_disorder_tcp_reserved(
    _stream: &TcpStream,
    _payload: &[u8],
    _segments: &[TcpPayloadSegment],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _inter_segment_delay_ms: u32,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identifications: &[u16],
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identifications = reserve_stream_ipv4_identifications(stream, ip_id_mode, segments.len())?;
    send_multi_disorder_tcp_reserved(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
        &ipv4_identifications,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_multi_disorder_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _segments: &[TcpPayloadSegment],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _inter_segment_delay_ms: u32,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
