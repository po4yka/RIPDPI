use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_config::DesyncGroup;
use ripdpi_desync::{ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints};
pub(crate) use ripdpi_desync_runtime::{primary_tcp_strategy_family, OutboundSendError, OutboundSendOutcome, PcapHook};
use ripdpi_session::OutboundProgress;

use crate::platform;

use super::adaptive::{direct_path_capability_for_route, resolve_adaptive_fake_ttl, resolve_tcp_hints_with_evolver};
use super::morph::{apply_tcp_morph_policy_to_group, emit_morph_hint_applied, tcp_morph_hint_family};
use super::state::RuntimeState;

struct RuntimeTcpDesyncPlatform;

fn to_runtime_flags(flags: ripdpi_desync_runtime::platform::TcpFlagOverrides) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides { set: flags.set, unset: flags.unset }
}

fn to_runtime_fake_options<'a>(
    options: ripdpi_desync_runtime::platform::FakeTcpOptions<'a>,
) -> platform::FakeTcpOptions<'a> {
    platform::FakeTcpOptions {
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

impl ripdpi_desync_runtime::platform::TcpDesyncPlatform for RuntimeTcpDesyncPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        platform::detect_default_ttl().ok()
    }

    fn seqovl_supported(&self) -> bool {
        platform::seqovl_supported()
    }

    fn supports_fake_retransmit(&self) -> bool {
        platform::supports_fake_retransmit()
    }

    fn tcp_segment_hint(&self, stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
        platform::tcp_segment_hint(stream)
    }

    fn tcp_activation_state(
        &self,
        stream: &TcpStream,
    ) -> io::Result<Option<ripdpi_desync_runtime::platform::TcpActivationState>> {
        platform::tcp_activation_state(stream).map(|state| {
            state.map(|state| ripdpi_desync_runtime::platform::TcpActivationState {
                has_timestamp: state.has_timestamp,
                window_size: state.window_size,
                mss: state.mss,
            })
        })
    }

    fn set_tcp_md5sig(&self, stream: &TcpStream, key_len: u16) -> io::Result<()> {
        platform::set_tcp_md5sig(stream, key_len)
    }

    fn set_tcp_window_clamp(&self, stream: &TcpStream, size: u32) -> io::Result<()> {
        platform::set_tcp_window_clamp(stream, size)
    }

    fn wait_tcp_stage(
        &self,
        stream: &TcpStream,
        wait_send: bool,
        await_interval: std::time::Duration,
    ) -> io::Result<()> {
        platform::wait_tcp_stage(stream, wait_send, await_interval)
    }

    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        default_ttl: u8,
        protect_path: Option<&str>,
        flags: ripdpi_desync_runtime::platform::TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        platform::send_fake_rst(stream, default_ttl, protect_path, to_runtime_flags(flags), ip_id_mode)
    }

    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        fake_prefix: &[u8],
        ttl: u8,
        md5sig: bool,
        default_ttl: u8,
        options: ripdpi_desync_runtime::platform::FakeTcpOptions<'_>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: ripdpi_desync_runtime::platform::TcpStageWait,
    ) -> io::Result<()> {
        platform::send_fake_tcp(
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
        segments: &[ripdpi_desync_runtime::platform::OrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: ripdpi_desync_runtime::platform::TcpStageWait,
    ) -> io::Result<()> {
        let runtime_segments = segments
            .iter()
            .map(|segment| platform::OrderedTcpSegment {
                payload: segment.payload,
                ttl: segment.ttl,
                flags: to_runtime_flags(segment.flags),
                sequence_offset: segment.sequence_offset,
                use_fake_timestamp: segment.use_fake_timestamp,
            })
            .collect::<Vec<_>>();
        platform::send_ordered_tcp_segments(
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
        flags: ripdpi_desync_runtime::platform::TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        platform::send_flagged_tcp_payload(
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
        flags: ripdpi_desync_runtime::platform::TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        platform::send_seqovl_tcp(
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
        flags: ripdpi_desync_runtime::platform::TcpFlagOverrides,
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
            to_runtime_flags(flags),
            ip_id_mode,
        )
    }

    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        segments: &[ripdpi_desync_runtime::platform::TcpPayloadSegment],
        default_ttl: u8,
        protect_path: Option<&str>,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        original_flags: ripdpi_desync_runtime::platform::TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        let runtime_segments = segments
            .iter()
            .map(|segment| platform::TcpPayloadSegment { start: segment.start, end: segment.end })
            .collect::<Vec<_>>();
        platform::send_multi_disorder_tcp(
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

pub(super) fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    payload: Option<&[u8]>,
    tcp_segment_hint: Option<ripdpi_desync::TcpSegmentHint>,
    tcp_activation_state: Option<platform::TcpActivationState>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    let has_ech = payload.and_then(ripdpi_packets::tls_marker_info).and_then(|markers| markers.ech_ext_start).is_some();
    let tcp_state = tcp_activation_state.map_or(
        ActivationTcpState { has_ech: Some(has_ech), ..ActivationTcpState::default() },
        |state| ActivationTcpState {
            has_timestamp: state.has_timestamp,
            has_ech: Some(has_ech),
            window_size: state.window_size,
            mss: state.mss.or_else(|| tcp_segment_hint.and_then(|hint| hint.snd_mss.or(hint.advmss))),
        },
    );
    ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        seqovl_supported: platform::seqovl_supported(),
        transport,
        tcp_segment_hint,
        tcp_state,
        resolved_fake_ttl,
        adaptive,
    }
}

pub(super) fn send_with_group(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    group: &DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    host: Option<&str>,
    target: SocketAddr,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let (effective_group, strategy_family_override) =
        ripdpi_desync_runtime::apply_tcp_capability_policy(group, capability, payload, progress);
    let effective_group = effective_group.as_ref();
    let resolved_fake_ttl = resolve_adaptive_fake_ttl(state, target, group_index, effective_group, host)?;
    let adaptive_hints = resolve_tcp_hints_with_evolver(state, target, group_index, effective_group, host, payload)?;
    emit_morph_hint_applied(state, target, tcp_morph_hint_family(state, payload, adaptive_hints));
    let morphed_group = apply_tcp_morph_policy_to_group(state, effective_group, payload, adaptive_hints);
    let effective_group = &morphed_group;
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        platform::tcp_segment_hint(writer).ok().flatten(),
        platform::tcp_activation_state(writer).ok().flatten(),
        resolved_fake_ttl,
        adaptive_hints,
    );
    ripdpi_desync_runtime::send_prepared_with_group(
        writer,
        &RuntimeTcpDesyncPlatform,
        &state.config,
        effective_group,
        payload,
        progress,
        context,
        resolved_fake_ttl,
        strategy_family_override,
        &state.ttl_unavailable,
        state.pcap_hook.as_ref(),
    )
}
