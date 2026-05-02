use std::io;
use std::net::SocketAddr;

use ripdpi_config::DesyncGroup;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_runtime_adaptive::strategy_context::merge_udp_hints_with_capability;

use crate::runtime::morph::{apply_tcp_morph_policy_to_hints, apply_udp_morph_policy_to_hints};
use crate::runtime::state::RuntimeState;

use super::direct_path_capability_for_route;
use super::hints::{record_morph_rollback, resolve_adaptive_tcp_hints, resolve_adaptive_udp_hints};

pub(in crate::runtime) fn resolve_tcp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    if !state.config.adaptive.strategy_evolution {
        return resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload);
    }
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        if let Some(hints) = evolver.tcp_hints(&state.config, state.runtime_context.as_ref(), target, host, payload) {
            return Ok(apply_tcp_morph_policy_to_hints(state, hints));
        }
    }
    resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload)
}

pub(in crate::runtime) fn resolve_udp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    if !state.config.adaptive.strategy_evolution {
        return resolve_adaptive_udp_hints(state, target, group_index, group, host, payload);
    }
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        if let Some(hints) = evolver.udp_hints(&state.config, state.runtime_context.as_ref(), target, host, payload) {
            let hints = apply_udp_morph_policy_to_hints(state, hints);
            let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
            let merged = merge_udp_hints_with_capability(hints, capability);
            record_morph_rollback(state, target, hints, merged);
            return Ok(merged);
        }
    }
    resolve_adaptive_udp_hints(state, target, group_index, group, host, payload)
}

pub(in crate::runtime) fn note_evolver_success(state: &RuntimeState, latency_ms: u64) {
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.record_success(latency_ms);
    }
}

pub(in crate::runtime) fn note_evolver_failure(state: &RuntimeState, class: ripdpi_failure_classifier::FailureClass) {
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.record_failure(class);
    }
}
