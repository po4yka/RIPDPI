mod capability;
mod conversion;
mod fake_tcp;
mod flagged_payload;
mod fragmentation;
mod multi_disorder;
mod ordered_segments;
mod payload_sender;
mod seq_overlap;
mod socket_options;

use std::net::TcpStream;

use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;

use crate::sync::AtomicBool;

pub use ripdpi_desync_runtime::{primary_tcp_strategy_family, OutboundSendError, OutboundSendOutcome, PcapHook};

pub struct RuntimeTcpDesyncPlatform;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TcpActivationProbe {
    pub has_timestamp: Option<bool>,
    pub window_size: Option<i64>,
    pub mss: Option<i64>,
}

pub fn tcp_segment_hint(stream: &TcpStream) -> Option<ripdpi_desync::TcpSegmentHint> {
    capability::tcp_segment_hint(stream)
}

pub fn tcp_activation_state(stream: &TcpStream) -> Option<TcpActivationProbe> {
    capability::tcp_activation_state(stream)
}

pub fn seqovl_supported() -> bool {
    capability::seqovl_supported()
}

#[allow(clippy::too_many_arguments)]
pub fn send_prepared_with_runtime_platform(
    writer: &mut TcpStream,
    config: &ripdpi_config::RuntimeConfig,
    group: &ripdpi_config::DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    context: ripdpi_desync::ActivationContext,
    resolved_fake_ttl: Option<u8>,
    strategy_family_override: Option<&'static str>,
    ttl_unavailable: &AtomicBool,
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

pub fn apply_tcp_capability_policy<'a>(
    group: &'a ripdpi_config::DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
    payload: &[u8],
    progress: ripdpi_session::OutboundProgress,
) -> (std::borrow::Cow<'a, ripdpi_config::DesyncGroup>, Option<&'static str>) {
    ripdpi_desync_runtime::apply_tcp_capability_policy(group, capability, payload, progress)
}

pub fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ripdpi_desync::ActivationTransport,
    payload: Option<&[u8]>,
    tcp_segment_hint: Option<ripdpi_desync::TcpSegmentHint>,
    tcp_activation_state: Option<TcpActivationProbe>,
    resolved_fake_ttl: Option<u8>,
    adaptive: ripdpi_desync::AdaptivePlannerHints,
) -> ripdpi_desync::ActivationContext {
    let has_ech = payload.is_some_and(crate::protocol_payload::payload_has_ech);
    let tcp_state = tcp_activation_state.map_or(
        ripdpi_desync::ActivationTcpState { has_ech: Some(has_ech), ..ripdpi_desync::ActivationTcpState::default() },
        |state| ripdpi_desync::ActivationTcpState {
            has_timestamp: state.has_timestamp,
            has_ech: Some(has_ech),
            window_size: state.window_size,
            mss: state.mss.or_else(|| tcp_segment_hint.and_then(|hint| hint.snd_mss.or(hint.advmss))),
        },
    );
    ripdpi_desync::ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        seqovl_supported: seqovl_supported(),
        transport,
        tcp_segment_hint,
        tcp_state,
        resolved_fake_ttl,
        adaptive,
    }
}
