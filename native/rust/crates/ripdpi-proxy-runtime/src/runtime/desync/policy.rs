use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::desync_platform::{
    activation_context_from_progress, send_prepared_with_runtime_platform, tcp_activation_state, tcp_segment_hint,
    OutboundSendError, OutboundSendOutcome,
};
use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use ripdpi_proxy_runtime_adapter::model::desync::ActivationTransport;
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use crate::runtime::adaptive::{
    direct_path_capability_for_route, resolve_adaptive_fake_ttl, resolve_tcp_hints_with_evolver,
};
use crate::runtime::morph::{apply_tcp_morph_policy_to_group, emit_morph_hint_applied, tcp_morph_hint_family};
use crate::runtime::state::RuntimeState;

pub(crate) struct DesyncSendRequest<'a> {
    pub(crate) group_index: usize,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) payload: &'a [u8],
    pub(crate) progress: OutboundProgress,
    pub(crate) host: Option<&'a str>,
    pub(crate) target: SocketAddr,
}

pub(crate) fn send_with_group(
    writer: &mut TcpStream,
    state: &RuntimeState,
    request: DesyncSendRequest<'_>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), request.host, request.target);
    let (effective_group, strategy_family_override) =
        ripdpi_proxy_runtime_adapter::desync_platform::apply_tcp_capability_policy(
            request.group,
            capability,
            request.payload,
            request.progress,
        );
    let effective_group = effective_group.as_ref();
    let resolved_fake_ttl =
        resolve_adaptive_fake_ttl(state, request.target, request.group_index, effective_group, request.host)?;
    let adaptive_hints = resolve_tcp_hints_with_evolver(
        state,
        request.target,
        request.group_index,
        effective_group,
        request.host,
        request.payload,
    )?;
    emit_morph_hint_applied(state, request.target, tcp_morph_hint_family(state, request.payload, adaptive_hints));
    let morphed_group = apply_tcp_morph_policy_to_group(state, effective_group, request.payload, adaptive_hints);
    let effective_group = &morphed_group;
    let context = activation_context_from_progress(
        request.progress,
        ActivationTransport::Tcp,
        Some(request.payload),
        tcp_segment_hint(writer),
        tcp_activation_state(writer),
        resolved_fake_ttl,
        adaptive_hints,
    );
    send_prepared_with_runtime_platform(
        writer,
        &state.config,
        effective_group,
        request.payload,
        request.progress,
        context,
        resolved_fake_ttl,
        strategy_family_override,
        &state.ttl_unavailable,
        state.pcap_hook.as_ref(),
    )
}
