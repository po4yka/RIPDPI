use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::decision::{
    ConnectionRoute, RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};

use crate::runtime::state::RuntimeState;

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
    state.policy().advance_route(
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
}
