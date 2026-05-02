use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime_policy::runtime_policy::ConnectionRoute;

use crate::runtime::routing::{advance_route_for_failure, note_route_success};
use crate::runtime::state::{flush_autolearn_updates, RuntimeState};

pub(crate) fn flush_updates(state: &RuntimeState) {
    if let Ok(mut cache) = state.cache.write() {
        flush_autolearn_updates(state, &mut cache);
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
