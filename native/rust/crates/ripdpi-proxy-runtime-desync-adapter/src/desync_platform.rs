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

pub use ripdpi_desync_runtime::{
    OutboundSendError, OutboundSendOutcome, PcapHook, TcpExecutionReceipt, TcpTerminalReason,
    primary_tcp_strategy_family,
};

#[derive(Clone)]
pub struct TcpDesyncExecutor {
    config: ripdpi_config::RuntimeConfig,
}

pub fn tcp_desync_executor(config: &ripdpi_config::RuntimeConfig) -> TcpDesyncExecutor {
    TcpDesyncExecutor { config: config.clone() }
}

pub struct TcpDesyncExecutionContext<'a> {
    pub executor: &'a TcpDesyncExecutor,
    pub runtime_context: Option<&'a ripdpi_proxy_config::ProxyRuntimeContext>,
    pub telemetry: Option<&'a dyn ripdpi_runtime_api::RuntimeTelemetrySink>,
    pub adaptive_hints: &'a dyn ripdpi_runtime_decision_ports::adaptive_ports::AdaptiveHintPort,
    pub ttl_unavailable: &'a AtomicBool,
    pub pcap_hook: Option<&'a PcapHook>,
}

pub struct DesyncSendRequest<'a> {
    pub group_index: usize,
    pub group_override: Option<&'a ripdpi_config::DesyncGroup>,
    pub payload: &'a [u8],
    pub progress: OutboundProgress,
    pub host: Option<&'a str>,
    pub target: std::net::SocketAddr,
}

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

pub fn send_tcp_desync_payload(
    writer: &mut TcpStream,
    context: TcpDesyncExecutionContext<'_>,
    request: DesyncSendRequest<'_>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    let config = &context.executor.config;
    let selected_group = crate::model::config::selected_desync_group(config, request.group_index)
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "missing desync group"))?;
    let group = request.group_override.unwrap_or(selected_group);
    let capability = ripdpi_runtime_services::decision_helpers::direct_path_capability_for_route(
        context.runtime_context,
        request.host,
        request.target,
    );
    let (effective_group, strategy_family_override) =
        apply_tcp_capability_policy(group, capability, request.payload, request.progress);
    let effective_group = effective_group.as_ref();
    let scope_key = ripdpi_runtime_services::decision_helpers::network_scope_key(config);
    let resolved_fake_ttl = context.adaptive_hints.resolve_fake_ttl(
        scope_key,
        request.group_index,
        request.target,
        request.host,
        effective_group,
    )?;
    let adaptive_hints = context.adaptive_hints.resolve_tcp_hints_with_evolver(
        config,
        context.runtime_context,
        request.group_index,
        request.target,
        request.host,
        effective_group,
        request.payload,
    )?;
    let morph_policy = crate::model::proxy_config::morph_policy(context.runtime_context);
    crate::model::proxy_config::emit_morph_hint_applied(
        context.telemetry,
        morph_policy,
        request.target,
        crate::model::proxy_config::tcp_morph_hint_family(morph_policy, request.payload, adaptive_hints),
    );
    let morphed_group = crate::model::proxy_config::apply_tcp_morph_policy_to_group(
        morph_policy,
        effective_group,
        request.payload,
        adaptive_hints,
    );
    let activation = activation_context_from_progress(
        request.progress,
        ripdpi_desync::ActivationTransport::Tcp,
        Some(request.payload),
        tcp_segment_hint(writer),
        tcp_activation_state(writer),
        resolved_fake_ttl,
        adaptive_hints,
    );
    send_prepared_with_runtime_platform(
        writer,
        config,
        &morphed_group,
        request.payload,
        request.progress,
        activation,
        resolved_fake_ttl,
        strategy_family_override,
        context.ttl_unavailable,
        context.pcap_hook,
    )
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
