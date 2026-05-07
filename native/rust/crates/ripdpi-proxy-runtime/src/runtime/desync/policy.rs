use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::desync_platform::{OutboundSendError, OutboundSendOutcome};
use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use ripdpi_proxy_runtime_adapter::model::desync::ActivationTransport;
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use super::platform::{
    activation_context_from_progress, send_prepared_with_runtime_platform, tcp_activation_state, tcp_segment_hint,
};
use crate::runtime::adaptive::{
    direct_path_capability_for_route, resolve_adaptive_fake_ttl, resolve_tcp_hints_with_evolver,
};
use crate::runtime::morph::{apply_tcp_morph_policy_to_group, emit_morph_hint_applied, tcp_morph_hint_family};
use crate::runtime::state::RuntimeState;

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
