use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;

use super::registry::with_current;
use super::types::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait,
};

pub fn detect_default_ttl() -> Option<u8> {
    with_current(|platform| platform.detect_default_ttl())
}

pub fn seqovl_supported() -> bool {
    with_current(|platform| platform.seqovl_supported())
}

pub fn supports_fake_retransmit() -> bool {
    with_current(|platform| platform.supports_fake_retransmit())
}

pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    with_current(|platform| platform.tcp_segment_hint(stream))
}

pub fn tcp_activation_state(stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    with_current(|platform| platform.tcp_activation_state(stream))
}

pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    with_current(|platform| platform.set_tcp_md5sig(stream, key_len))
}

pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    with_current(|platform| platform.set_tcp_window_clamp(stream, size))
}

pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    with_current(|platform| platform.wait_tcp_stage(stream, wait_send, await_interval))
}

pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| platform.send_fake_rst(stream, default_ttl, protect_path, flags, ip_id_mode))
}

#[allow(clippy::too_many_arguments)]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: FakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            options,
            ip_id_mode,
            wait,
        )
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_ordered_tcp_segments(
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
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode)
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_seqovl_tcp(stream, real_chunk, fake_prefix, default_ttl, protect_path, md5sig, flags, ip_id_mode)
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_ip_fragmented_tcp(
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
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    original_flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_multi_disorder_tcp(
            stream,
            payload,
            segments,
            default_ttl,
            protect_path,
            inter_segment_delay_ms,
            md5sig,
            original_flags,
            ip_id_mode,
        )
    })
}
