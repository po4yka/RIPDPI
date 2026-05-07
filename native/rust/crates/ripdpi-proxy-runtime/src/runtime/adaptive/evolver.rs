use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::config::{strategy_evolution_enabled, DesyncGroup};
use ripdpi_proxy_runtime_adapter::model::desync::AdaptivePlannerHints;

use crate::runtime::state::RuntimeState;

use super::hints::resolve_adaptive_udp_hints;

pub(in crate::runtime) fn resolve_tcp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    state.adaptive_hints.resolve_tcp_hints_with_evolver(
        &state.config,
        state.runtime_context.as_ref(),
        group_index,
        target,
        host,
        group,
        payload,
    )
}

pub(in crate::runtime) fn resolve_udp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    // The port impl handles evolver + morph + capability merge for the evolver path.
    // For the non-evolver path we still need to apply the rollback tracking from
    // resolve_adaptive_udp_hints (which merge_udp_hints_with_capability + record_morph_rollback).
    if strategy_evolution_enabled(&state.config) {
        // Try evolver path via port (includes morph + capability merge).
        let result = state.adaptive_hints.resolve_udp_hints_with_evolver(
            &state.config,
            state.runtime_context.as_ref(),
            group_index,
            target,
            host,
            group,
            payload,
        )?;
        // Check if the evolver produced a result by comparing with the plain resolver.
        // We can't know if the evolver fired from the outside, so delegate fully.
        return Ok(result);
    }
    resolve_adaptive_udp_hints(state, target, group_index, group, host, payload)
}

pub(in crate::runtime) fn note_evolver_success(state: &RuntimeState, _latency_ms: u64) {
    state.adaptive_feedback.note_evolver_success();
}

pub(in crate::runtime) fn note_evolver_failure(
    state: &RuntimeState,
    class: ripdpi_proxy_runtime_adapter::failure::FailureClass,
) {
    state.adaptive_feedback.note_evolver_failure(class);
}
