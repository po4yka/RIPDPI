use std::net::TcpStream;
use std::time::Duration;

use crate::platform;
use crate::strategy_family::restore_ttl_action_name;
use crate::tcp_lowering::{write_payload_with_android_ttl_fallback, TcpLoweringCapabilities};
use crate::types::OutboundSendError;

use super::errors::{strategy_result, transport_result};
use super::progress::write_strategy_payload_named;
use super::raw_socket;
use super::socket_options::send_out_of_band;

#[allow(clippy::too_many_arguments)]
pub(crate) fn write_strategy_payload_with_optional_flags_named(
    stream: &mut TcpStream,
    bytes: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    if flags.is_empty() {
        write_strategy_payload_named(stream, bytes, action, strategy_family, fallback, bytes_committed)
    } else {
        send_flagged_tcp_payload_action_named(
            stream,
            bytes,
            default_ttl,
            protect_path,
            md5sig,
            flags,
            ip_id_mode,
            action,
            strategy_family,
            fallback,
            bytes_committed,
        )
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn write_ttl_sensitive_payload_with_optional_flags_named(
    lowering: &mut TcpLoweringCapabilities,
    writer: &mut TcpStream,
    bytes: &[u8],
    ttl_modified: bool,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(bool, usize), OutboundSendError> {
    if flags.is_empty() {
        write_payload_with_android_ttl_fallback(
            lowering,
            writer,
            bytes,
            ttl_modified,
            action,
            restore_ttl_action_name(strategy_family),
            strategy_family,
            fallback,
            bytes_committed,
        )
    } else {
        let committed = write_strategy_payload_with_optional_flags_named(
            writer,
            bytes,
            default_ttl,
            protect_path,
            md5sig,
            flags,
            ip_id_mode,
            action,
            strategy_family,
            fallback,
            bytes_committed,
        )?;
        Ok((ttl_modified, committed))
    }
}

pub(crate) fn send_oob_action_named(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(send_out_of_band(writer, prefix, urgent_byte), action, strategy_family, fallback, bytes_committed)
        .map(|()| bytes_committed + prefix.len() + 1)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_fake_tcp_action_named(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: platform::FakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        raw_socket::send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            options,
            ip_id_mode,
            wait,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_prefix.len())
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ordered_fake_segments_action_named(
    stream: &TcpStream,
    segments: &[platform::OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        raw_socket::send_ordered_tcp_segments(
            stream,
            segments,
            original_payload_len,
            default_ttl,
            protect_path,
            md5sig,
            timestamp_delta_ticks,
            ip_id_mode,
            wait,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_payload_len)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_flagged_tcp_payload_action_named(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        raw_socket::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + payload.len())
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ip_fragmented_tcp_action_named(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        raw_socket::send_ip_fragmented_tcp(
            stream,
            payload,
            split_offset,
            default_ttl,
            protect_path,
            disorder,
            ipv6_ext,
            flags,
            ip_id_mode,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + payload.len())
}

pub(crate) fn set_md5sig_transport_action(stream: &TcpStream, key_len: u16) -> Result<(), OutboundSendError> {
    transport_result(raw_socket::set_tcp_md5sig(stream, key_len))
}

pub(crate) fn set_md5sig_action_named(
    stream: &TcpStream,
    key_len: u16,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(raw_socket::set_tcp_md5sig(stream, key_len), action, strategy_family, fallback, bytes_committed)
}

pub(crate) fn await_writable_action_named(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(
        raw_socket::wait_tcp_stage(stream, wait_send, await_interval),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
}

pub(crate) fn await_transport_writable_action(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
) -> Result<(), OutboundSendError> {
    transport_result(raw_socket::wait_tcp_stage(stream, wait_send, await_interval))
}
