use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;

use super::types::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait,
};

pub trait TcpPlatformCapabilities {
    fn detect_default_ttl(&self) -> Option<u8>;
    fn seqovl_supported(&self) -> bool;
    fn supports_fake_retransmit(&self) -> bool;
    fn tcp_segment_hint(&self, stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>>;
    fn tcp_activation_state(&self, stream: &TcpStream) -> io::Result<Option<TcpActivationState>>;
}

pub trait TcpSocketOptions {
    fn set_tcp_md5sig(&self, stream: &TcpStream, key_len: u16) -> io::Result<()>;
    fn set_tcp_window_clamp(&self, stream: &TcpStream, size: u32) -> io::Result<()>;
    fn wait_tcp_stage(&self, stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()>;
}

#[allow(clippy::too_many_arguments)]
pub trait TcpFakeSender {
    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        default_ttl: u8,
        protect_path: Option<&str>,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        fake_prefix: &[u8],
        ttl: u8,
        md5sig: bool,
        default_ttl: u8,
        options: FakeTcpOptions<'_>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()>;
}

#[allow(clippy::too_many_arguments)]
pub trait TcpPayloadSender {
    fn send_ordered_tcp_segments(
        &self,
        stream: &TcpStream,
        segments: &[OrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()>;
    fn send_flagged_tcp_payload(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_seqovl_tcp(
        &self,
        stream: &TcpStream,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
}

#[allow(clippy::too_many_arguments)]
pub trait TcpFragmentSender {
    fn send_ip_fragmented_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        disorder: bool,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        segments: &[TcpPayloadSegment],
        default_ttl: u8,
        protect_path: Option<&str>,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        original_flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
}

pub trait TcpDesyncPlatform:
    TcpPlatformCapabilities + TcpSocketOptions + TcpFakeSender + TcpPayloadSender + TcpFragmentSender
{
}

impl<T> TcpDesyncPlatform for T where
    T: TcpPlatformCapabilities + TcpSocketOptions + TcpFakeSender + TcpPayloadSender + TcpFragmentSender
{
}
