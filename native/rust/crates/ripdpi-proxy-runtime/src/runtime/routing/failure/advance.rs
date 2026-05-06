use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime_decision_ports::policy::{ConnectionRoute, TransportProtocol};

use super::cache::advance_cache_route;
use super::feedback::record_failure_feedback;
use super::telemetry::emit_route_advance_telemetry;
use super::trigger::route_advance_trigger;
use crate::runtime::retry::build_retry_selection_penalties;
use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn advance_route_for_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    failure: &ClassifiedFailure,
) -> io::Result<Option<ConnectionRoute>> {
    if !super::should_track_strategy_target(target) {
        return Ok(None);
    }
    let Some(trigger) = route_advance_trigger(state, failure)? else {
        return Ok(None);
    };

    let host_ref = host.as_deref();
    let penalize = record_failure_feedback(state, target, route, host_ref, payload, failure)?;
    let retry_penalties = build_retry_selection_penalties(state, target, host_ref, payload, TransportProtocol::Tcp)?;
    let next = advance_cache_route(state, target, route, host.clone(), payload, trigger, penalize, &retry_penalties)?;
    emit_route_advance_telemetry(state, target, route, next.as_ref(), trigger, failure, host_ref, &retry_penalties);
    Ok(next)
}
