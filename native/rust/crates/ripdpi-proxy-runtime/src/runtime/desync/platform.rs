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

use ripdpi_runtime_platform as runtime_platform;

pub(super) struct RuntimeTcpDesyncPlatform;

fn to_runtime_flags(flags: DesyncTcpFlagOverrides) -> runtime_platform::TcpFlagOverrides {
    runtime_platform::TcpFlagOverrides { set: flags.set, unset: flags.unset }
}

fn to_runtime_fake_options<'a>(options: DesyncFakeTcpOptions<'a>) -> runtime_platform::FakeTcpOptions<'a> {
    runtime_platform::FakeTcpOptions {
        secondary_fake_prefix: options.secondary_fake_prefix,
        timestamp_delta_ticks: options.timestamp_delta_ticks,
        protect_path: options.protect_path,
        fake_flags: to_runtime_flags(options.fake_flags),
        orig_flags: to_runtime_flags(options.orig_flags),
        require_raw_path: options.require_raw_path,
        force_raw_original: options.force_raw_original,
        ipv4_identifications: options.ipv4_identifications,
    }
}

impl TcpDesyncPlatform for RuntimeTcpDesyncPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        runtime_platform::detect_default_ttl().ok()
    }

    fn seqovl_supported(&self) -> bool {
        runtime_platform::seqovl_supported()
    }

    fn supports_fake_retransmit(&self) -> bool {
        runtime_platform::supports_fake_retransmit()
    }

    fn tcp_segment_hint(&self, stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
        runtime_platform::tcp_segment_hint(stream)
    }

    fn tcp_activation_state(&self, stream: &TcpStream) -> io::Result<Option<DesyncTcpActivationState>> {
        runtime_platform::tcp_activation_state(stream).map(|state| {
            state.map(|state| DesyncTcpActivationState {
                has_timestamp: state.has_timestamp,
                window_size: state.window_size,
                mss: state.mss,
            })
        })
    }

    fn set_tcp_md5sig(&self, stream: &TcpStream, key_len: u16) -> io::Result<()> {
        runtime_platform::set_tcp_md5sig(stream, key_len)
    }

    fn set_tcp_window_clamp(&self, stream: &TcpStream, size: u32) -> io::Result<()> {
        runtime_platform::set_tcp_window_clamp(stream, size)
    }

    fn wait_tcp_stage(
        &self,
        stream: &TcpStream,
        wait_send: bool,
        await_interval: std::time::Duration,
    ) -> io::Result<()> {
        runtime_platform::wait_tcp_stage(stream, wait_send, await_interval)
    }

    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        default_ttl: u8,
        protect_path: Option<&str>,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        runtime_platform::send_fake_rst(stream, default_ttl, protect_path, to_runtime_flags(flags), ip_id_mode)
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
        runtime_platform::send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            to_runtime_fake_options(options),
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
        let runtime_segments = segments
            .iter()
            .map(|segment| runtime_platform::OrderedTcpSegment {
                payload: segment.payload,
                ttl: segment.ttl,
                flags: to_runtime_flags(segment.flags),
                sequence_offset: segment.sequence_offset,
                use_fake_timestamp: segment.use_fake_timestamp,
            })
            .collect::<Vec<_>>();
        runtime_platform::send_ordered_tcp_segments(
            stream,
            &runtime_segments,
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
        runtime_platform::send_flagged_tcp_payload(
            stream,
            payload,
            default_ttl,
            protect_path,
            md5sig,
            to_runtime_flags(flags),
            ip_id_mode,
        )
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
        runtime_platform::send_seqovl_tcp(
            stream,
            real_chunk,
            fake_prefix,
            default_ttl,
            protect_path,
            md5sig,
            to_runtime_flags(flags),
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
        runtime_platform::send_ip_fragmented_tcp(
            stream,
            payload,
            split_offset,
            default_ttl,
            protect_path,
            disorder,
            ipv6_ext,
            to_runtime_flags(flags),
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
        let runtime_segments = segments
            .iter()
            .map(|segment| runtime_platform::TcpPayloadSegment { start: segment.start, end: segment.end })
            .collect::<Vec<_>>();
        runtime_platform::send_multi_disorder_tcp(
            stream,
            payload,
            &runtime_segments,
            default_ttl,
            protect_path,
            inter_segment_delay_ms,
            md5sig,
            to_runtime_flags(original_flags),
            ip_id_mode,
        )
    }
}

pub(super) fn tcp_segment_hint(stream: &TcpStream) -> Option<ripdpi_desync::TcpSegmentHint> {
    runtime_platform::tcp_segment_hint(stream).ok().flatten()
}

pub(super) fn tcp_activation_state(stream: &TcpStream) -> Option<runtime_platform::TcpActivationState> {
    runtime_platform::tcp_activation_state(stream).ok().flatten()
}

pub(super) fn seqovl_supported() -> bool {
    runtime_platform::seqovl_supported()
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
