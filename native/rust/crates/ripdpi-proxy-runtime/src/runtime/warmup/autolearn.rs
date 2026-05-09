use std::io;
use std::net::SocketAddr;

use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::routing::{advance_route_for_failure, note_route_success};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;

pub(crate) fn flush_updates(state: &RuntimeState) {
    // The policy port handles autolearn flushing internally on every mutating
    // call. A final explicit drain ensures any accumulated events are emitted
    // even when no route mutations occurred during warmup.
    state.flush_autolearn_telemetry();
}

pub(crate) fn record_route_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    domain: &str,
) -> io::Result<()> {
    note_route_success(state, target, route, Some(domain))
}

pub(crate) fn advance_after_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    domain: &str,
    payload: &[u8],
    failure: &RuntimeClassifiedFailure,
) -> io::Result<bool> {
    let advanced = advance_route_for_failure(state, target, route, Some(domain.to_owned()), Some(payload), failure)?;
    Ok(advanced.is_some())
}
