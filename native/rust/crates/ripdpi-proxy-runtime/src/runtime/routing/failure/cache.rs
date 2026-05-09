use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use crate::runtime::state::RuntimeState;
use crate::runtime::types::{
    RuntimeConnectionRoute, RuntimeRetrySelectionPenalty, RuntimeRouteAdvance, RuntimeTransportProtocol,
};

pub(super) fn advance_cache_route(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    trigger: u32,
    penalize: bool,
    retry_penalties: &BTreeMap<usize, RuntimeRetrySelectionPenalty>,
) -> io::Result<Option<RuntimeConnectionRoute>> {
    state.advance_route(
        route,
        RuntimeRouteAdvance {
            dest: target,
            payload,
            transport: RuntimeTransportProtocol::Tcp,
            trigger,
            can_reconnect: true,
            host,
            penalize_strategy_failure: penalize,
            retry_penalties: Some(retry_penalties),
        },
    )
}
