use std::net::SocketAddr;

use std::collections::BTreeMap;

use crate::runtime::retry::maybe_emit_candidate_diversification;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeClassifiedFailure, RuntimeConnectionRoute, RuntimeRetrySelectionPenalty};

pub(in crate::runtime) fn emit_failure_classified(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &RuntimeClassifiedFailure,
    host: Option<&str>,
) {
    if !RuntimeState::should_track_strategy_target(target) {
        return;
    }
    state.note_failure_classified(target, failure, host);
}

pub(super) fn emit_route_advance_telemetry(
    state: &RuntimeState,
    target: SocketAddr,
    previous_route: &RuntimeConnectionRoute,
    next_route: Option<&RuntimeConnectionRoute>,
    trigger: u32,
    failure: &RuntimeClassifiedFailure,
    host: Option<&str>,
    retry_penalties: &BTreeMap<usize, RuntimeRetrySelectionPenalty>,
) {
    if let Some(next_route) = next_route {
        maybe_emit_candidate_diversification(state, target, next_route, retry_penalties);
    }
    if let Some(next_route) = next_route {
        state.note_route_advanced(target, previous_route.group_index, next_route.group_index, trigger, host);
        state.note_adaptive_override(
            target,
            next_route.group_index,
            trigger,
            failure.class.as_str(),
            host,
            "route_advance",
        );
    }
}
