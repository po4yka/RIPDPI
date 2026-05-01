use std::io;
use std::net::TcpStream;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::{raw_path_ids, root_helper_dispatch};
use crate::{OrderedTcpSegment, TcpStageWait};

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
    ripdpi_privileged_ops::send_ordered_tcp_segments(
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
    let ipv4_identifications = raw_path_ids::reserve_for_addresses(source, target, ip_id_mode, segments.len());
    if let Some(result) = root_helper_dispatch::send_ordered_tcp_segments(
        stream,
        segments,
        original_payload_len,
        default_ttl,
        md5sig,
        timestamp_delta_ticks,
        &ipv4_identifications,
        wait,
    ) {
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
