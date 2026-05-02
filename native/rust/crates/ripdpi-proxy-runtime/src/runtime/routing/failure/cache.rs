use std::io;
use std::net::SocketAddr;

use std::collections::BTreeMap;

use ripdpi_runtime_policy::runtime_policy::{ConnectionRoute, RetrySelectionPenalty, RouteAdvance, TransportProtocol};
use ripdpi_runtime_policy::RuntimePolicy;

use crate::runtime::state::{flush_autolearn_updates, RuntimeState};

pub(super) fn advance_cache_route(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    trigger: u32,
    penalize: bool,
    retry_penalties: &BTreeMap<usize, RetrySelectionPenalty>,
) -> io::Result<Option<ConnectionRoute>> {
    with_cache_and_autolearn_flush(state, |cache| {
        cache.advance_route(
            &state.config,
            route,
            RouteAdvance {
                dest: target,
                payload,
                transport: TransportProtocol::Tcp,
                trigger,
                can_reconnect: true,
                host,
                penalize_strategy_failure: penalize,
                retry_penalties: Some(retry_penalties),
            },
        )
    })
}

fn with_cache_and_autolearn_flush<T>(
    state: &RuntimeState,
    operation: impl FnOnce(&mut RuntimePolicy) -> io::Result<T>,
) -> io::Result<T> {
    let mut cache = state.cache.write().map_err(|_| io::Error::other("cache lock poisoned"))?;
    let result = operation(&mut cache)?;
    flush_autolearn_updates(state, &mut cache);
    Ok(result)
}
