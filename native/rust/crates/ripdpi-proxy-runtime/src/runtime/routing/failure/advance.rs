use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::decision::{ConnectionRoute, TransportProtocol};

use super::cache::advance_cache_route;
use super::feedback::record_failure_feedback;
use super::telemetry::emit_route_advance_telemetry;
use crate::runtime::retry::build_retry_selection_penalties;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeClassifiedFailure;

pub(in crate::runtime) fn advance_route_for_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    failure: &RuntimeClassifiedFailure,
) -> io::Result<Option<ConnectionRoute>> {
    if !RuntimeState::should_track_strategy_target(target) {
        return Ok(None);
    }
    let Some(trigger) = state.retry_trigger_for_failure(failure) else {
        return Ok(None);
    };

    let host_ref = host.as_deref();
    let penalize = record_failure_feedback(state, target, route, host_ref, payload, failure)?;
    let retry_penalties = build_retry_selection_penalties(state, target, host_ref, payload, TransportProtocol::Tcp)?;
    let next = advance_cache_route(state, target, route, host.clone(), payload, trigger, penalize, &retry_penalties)?;
    emit_route_advance_telemetry(state, target, route, next.as_ref(), trigger, failure, host_ref, &retry_penalties);
    Ok(next)
}
