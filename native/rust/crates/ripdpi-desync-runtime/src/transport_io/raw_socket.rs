use std::io;
use std::net::TcpStream;
use std::time::Duration;

use crate::platform;

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: platform::FakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
) -> io::Result<()> {
    platform::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, ip_id_mode, wait)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[platform::OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
) -> io::Result<()> {
    platform::send_ordered_tcp_segments(
        stream,
        segments,
        original_payload_len,
        default_ttl,
        protect_path,
        md5sig,
        timestamp_delta_ticks,
        ip_id_mode,
        wait,
    )
}

pub(crate) fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    platform::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode)
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
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    platform::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ip_id_mode,
    )
}

pub(crate) fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    platform::set_tcp_md5sig(stream, key_len)
}

pub(crate) fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    platform::wait_tcp_stage(stream, wait_send, await_interval)
}
