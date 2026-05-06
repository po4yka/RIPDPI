use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime_decision_ports::policy::ConnectionRoute;

use crate::runtime::routing::{advance_route_for_failure, note_route_success};
use crate::runtime::state::RuntimeState;

pub(crate) fn flush_updates(state: &RuntimeState) {
    // The policy port handles autolearn flushing internally on every mutating
    // call. A final explicit drain ensures any accumulated events are emitted
    // even when no route mutations occurred during warmup.
    if let Some(telemetry) = &state.telemetry {
        let autolearn = state.policy.autolearn_state(&state.config);
        telemetry.on_host_autolearn_state(
            autolearn.enabled,
            autolearn.learned_host_count,
            autolearn.penalized_host_count,
            autolearn.blocked_host_count,
            autolearn.last_block_signal.as_deref(),
            autolearn.last_block_provider.as_deref(),
        );
        for event in state.policy.drain_autolearn_events() {
            telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
        }
    } else {
        let _ = state.policy.drain_autolearn_events();
    }
}

pub(crate) fn record_route_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    domain: &str,
) -> io::Result<()> {
    note_route_success(state, target, route, Some(domain))
}

pub(crate) fn advance_after_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    domain: &str,
    payload: &[u8],
    failure: &ClassifiedFailure,
) -> io::Result<bool> {
    let advanced = advance_route_for_failure(state, target, route, Some(domain.to_owned()), Some(payload), failure)?;
    Ok(advanced.is_some())
}
