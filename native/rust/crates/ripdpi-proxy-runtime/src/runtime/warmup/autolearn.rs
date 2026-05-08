use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;

use crate::runtime::routing::{advance_route_for_failure, note_route_success};
use crate::runtime::state::RuntimeState;

pub(crate) fn flush_updates(state: &RuntimeState) {
    // The policy port handles autolearn flushing internally on every mutating
    // call. A final explicit drain ensures any accumulated events are emitted
    // even when no route mutations occurred during warmup.
    state.flush_autolearn_telemetry();
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
