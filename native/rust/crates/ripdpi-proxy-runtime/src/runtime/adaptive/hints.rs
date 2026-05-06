use std::io;
use std::net::SocketAddr;

use ripdpi_config::DesyncGroup;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_runtime_decision_ports::adaptive::strategy_context::merge_udp_hints_with_capability;

use crate::runtime::morph::emit_morph_rollback;
use crate::runtime::state::RuntimeState;

use super::{direct_path_capability_for_route, network_scope_key};

pub(in crate::runtime) fn resolve_adaptive_udp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    // AdaptiveHintPort::resolve_udp_hints applies morph internally; we still need to
    // merge with direct-path capability and track rollbacks here.
    let hints = state.adaptive_hints.resolve_udp_hints(
        network_scope_key(&state.config),
        group_index,
        target,
        host,
        group,
        payload,
    )?;
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let merged = merge_udp_hints_with_capability(hints, capability);
    record_morph_rollback(state, target, hints, merged);
    Ok(merged)
}

pub(in crate::runtime) fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback.note_tcp_success(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback.note_tcp_failure(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback.note_udp_success(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback.note_udp_failure(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn record_morph_rollback(
    state: &RuntimeState,
    target: SocketAddr,
    before: AdaptivePlannerHints,
    after: AdaptivePlannerHints,
) {
    if before.udp_burst_profile != after.udp_burst_profile || before.quic_fake_profile != after.quic_fake_profile {
        emit_morph_rollback(state, target, "direct_path_capability_downgrade");
    }
}
