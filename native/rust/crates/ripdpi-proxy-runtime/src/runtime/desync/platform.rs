mod capability;
mod conversion;
mod fake_tcp;
mod flagged_payload;
mod fragmentation;
mod multi_disorder;
mod ordered_segments;
mod seq_overlap;
mod socket_options;

use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::{
    platform::{
        FakeTcpOptions as DesyncFakeTcpOptions, OrderedTcpSegment as DesyncOrderedTcpSegment,
        TcpActivationState as DesyncTcpActivationState, TcpDesyncPlatform, TcpFlagOverrides as DesyncTcpFlagOverrides,
        TcpPayloadSegment as DesyncTcpPayloadSegment, TcpStageWait,
    },
    OutboundSendError, OutboundSendOutcome, PcapHook,
};
use ripdpi_session::OutboundProgress;

pub(super) struct RuntimeTcpDesyncPlatform;

impl TcpDesyncPlatform for RuntimeTcpDesyncPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        capability::detect_default_ttl()
    }

    fn seqovl_supported(&self) -> bool {
        capability::seqovl_supported()
    }

    fn supports_fake_retransmit(&self) -> bool {
        capability::supports_fake_retransmit()
    }

    fn tcp_segment_hint(&self, stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
        capability::tcp_segment_hint_result(stream)
    }

    fn tcp_activation_state(&self, stream: &TcpStream) -> io::Result<Option<DesyncTcpActivationState>> {
        capability::tcp_activation_state_result(stream)
    }

    fn set_tcp_md5sig(&self, stream: &TcpStream, key_len: u16) -> io::Result<()> {
        socket_options::set_tcp_md5sig(stream, key_len)
    }

    fn set_tcp_window_clamp(&self, stream: &TcpStream, size: u32) -> io::Result<()> {
        socket_options::set_tcp_window_clamp(stream, size)
    }

    fn wait_tcp_stage(
        &self,
        stream: &TcpStream,
        wait_send: bool,
        await_interval: std::time::Duration,
    ) -> io::Result<()> {
        socket_options::wait_tcp_stage(stream, wait_send, await_interval)
    }

    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        default_ttl: u8,
        protect_path: Option<&str>,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        fake_tcp::send_fake_rst(stream, default_ttl, protect_path, flags, ip_id_mode)
    }

    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        fake_prefix: &[u8],
        ttl: u8,
        md5sig: bool,
        default_ttl: u8,
        options: DesyncFakeTcpOptions<'_>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()> {
        fake_tcp::send_fake_tcp(
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
    }

    fn send_ordered_tcp_segments(
        &self,
        stream: &TcpStream,
        segments: &[DesyncOrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()> {
        ordered_segments::send_ordered_tcp_segments(
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

    fn send_flagged_tcp_payload(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        flagged_payload::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode)
    }

    fn send_seqovl_tcp(
        &self,
        stream: &TcpStream,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        seq_overlap::send_seqovl_tcp(
            stream,
            real_chunk,
            fake_prefix,
            default_ttl,
            protect_path,
            md5sig,
            flags,
            ip_id_mode,
        )
    }

    fn send_ip_fragmented_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        disorder: bool,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        fragmentation::send_ip_fragmented_tcp(
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

    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        segments: &[DesyncTcpPayloadSegment],
        default_ttl: u8,
        protect_path: Option<&str>,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        original_flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        multi_disorder::send_multi_disorder_tcp(
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
    }
}

pub(super) fn tcp_segment_hint(stream: &TcpStream) -> Option<ripdpi_desync::TcpSegmentHint> {
    capability::tcp_segment_hint(stream)
}

pub(super) fn tcp_activation_state(stream: &TcpStream) -> Option<ripdpi_runtime_platform::TcpActivationState> {
    capability::tcp_activation_state(stream)
}

pub(super) fn seqovl_supported() -> bool {
    capability::seqovl_supported()
}

#[allow(clippy::too_many_arguments)]
pub(super) fn send_prepared_with_runtime_platform(
    writer: &mut TcpStream,
    config: &ripdpi_config::RuntimeConfig,
    group: &ripdpi_config::DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    context: ripdpi_desync::ActivationContext,
    resolved_fake_ttl: Option<u8>,
    strategy_family_override: Option<&'static str>,
    ttl_unavailable: &std::sync::atomic::AtomicBool,
    pcap_hook: Option<&PcapHook>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    ripdpi_desync_runtime::send_prepared_with_group(
        writer,
        &RuntimeTcpDesyncPlatform,
        config,
        group,
        payload,
        progress,
        context,
        resolved_fake_ttl,
        strategy_family_override,
        ttl_unavailable,
        pcap_hook,
    )
}
