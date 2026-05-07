use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::config::DesyncGroup;
use ripdpi_proxy_runtime_adapter::desync_model::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, TcpSegmentHint,
};
use ripdpi_proxy_runtime_adapter::desync_platform::{OutboundSendError, OutboundSendOutcome};
use ripdpi_proxy_runtime_adapter::protocol_payload;
use ripdpi_proxy_runtime_adapter::session::OutboundProgress;

use super::platform::{send_prepared_with_runtime_platform, seqovl_supported, tcp_activation_state, tcp_segment_hint};
use crate::runtime::adaptive::{
    direct_path_capability_for_route, resolve_adaptive_fake_ttl, resolve_tcp_hints_with_evolver,
};
use crate::runtime::morph::{apply_tcp_morph_policy_to_group, emit_morph_hint_applied, tcp_morph_hint_family};
use crate::runtime::state::RuntimeState;

pub(crate) fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    payload: Option<&[u8]>,
    tcp_segment_hint: Option<TcpSegmentHint>,
    tcp_activation_state: Option<ripdpi_proxy_runtime_adapter::platform::tcp::TcpActivationState>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    let has_ech = payload.is_some_and(protocol_payload::payload_has_ech);
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
        seqovl_supported: seqovl_supported(),
        transport,
        tcp_segment_hint,
        tcp_state,
        resolved_fake_ttl,
        adaptive,
    }
}

pub(crate) fn send_with_group(
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
        ripdpi_proxy_runtime_adapter::desync_platform::apply_tcp_capability_policy(
            group, capability, payload, progress,
        );
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
        tcp_segment_hint(writer),
        tcp_activation_state(writer),
        resolved_fake_ttl,
        adaptive_hints,
    );
    send_prepared_with_runtime_platform(
        writer,
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
