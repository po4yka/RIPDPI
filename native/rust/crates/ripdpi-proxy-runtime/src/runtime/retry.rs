use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_proxy_runtime_adapter::model::decision::{ConnectionRoute, RetrySelectionPenalty, TransportProtocol};

use super::state::RuntimeState;

pub(super) fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
}

pub(super) fn note_retry_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<()> {
    state.note_retry_success(target, group_index, host, payload, transport)
}

pub(super) fn note_retry_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<()> {
    state.note_retry_failure(target, group_index, host, payload, transport, now_millis())
}

/// Builds retry selection penalties for all groups.
pub(super) fn build_retry_selection_penalties(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>> {
    state.build_retry_penalties(target, host, payload, transport, now_millis())
}

pub(super) fn maybe_emit_candidate_diversification(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    penalties: &BTreeMap<usize, RetrySelectionPenalty>,
) {
    let Some(selected_penalty) = penalties.get(&route.group_index).copied() else {
        return;
    };
    let cooled_alternative_exists = penalties.iter().any(|(group_index, penalty)| {
        *group_index != route.group_index && (penalty.same_signature_cooldown_ms > 0 || penalty.family_cooldown_ms > 0)
    });
    if !cooled_alternative_exists
        || (selected_penalty.same_signature_cooldown_ms > 0 && selected_penalty.family_cooldown_ms > 0)
    {
        return;
    }
    state.note_retry_paced(target, route.group_index, "candidate_order_diversified", 0);
}

pub(super) fn apply_retry_pacing_before_connect(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
    payload: Option<&[u8]>,
) -> io::Result<()> {
    state.apply_retry_pacing(target, route, host, payload, now_millis())
}
