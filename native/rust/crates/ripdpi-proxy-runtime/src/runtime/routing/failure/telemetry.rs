use std::net::SocketAddr;

use std::collections::BTreeMap;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime_decision_ports::policy::{ConnectionRoute, RetrySelectionPenalty};

use crate::runtime::retry::maybe_emit_candidate_diversification;
use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn emit_failure_classified(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &ClassifiedFailure,
    host: Option<&str>,
) {
    if !super::should_track_strategy_target(target) {
        return;
    }
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_failure_classified(target, failure, host);
    }
}

pub(super) fn emit_route_advance_telemetry(
    state: &RuntimeState,
    target: SocketAddr,
    previous_route: &ConnectionRoute,
    next_route: Option<&ConnectionRoute>,
    trigger: u32,
    failure: &ClassifiedFailure,
    host: Option<&str>,
    retry_penalties: &BTreeMap<usize, RetrySelectionPenalty>,
) {
    if let Some(next_route) = next_route {
        maybe_emit_candidate_diversification(state, target, next_route, retry_penalties);
    }
    if let (Some(telemetry), Some(next_route)) = (&state.telemetry, next_route) {
        telemetry.on_route_advanced(target, previous_route.group_index, next_route.group_index, trigger, host);
        telemetry.on_adaptive_override(
            target,
            next_route.group_index,
            trigger,
            failure.class.as_str(),
            host,
            "route_advance",
        );
    }
}
