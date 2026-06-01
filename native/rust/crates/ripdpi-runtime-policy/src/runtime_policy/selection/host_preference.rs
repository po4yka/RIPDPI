use std::collections::BTreeMap;
use std::net::SocketAddr;

use ripdpi_config::{DETECT_RECONN, RuntimeConfig};

use crate::runtime_policy::autolearn::{
    host_has_active_block, host_penalty_active_for_group, record_has_learned_winner,
};
use crate::runtime_policy::matching::group_matches_with_geo;
use crate::runtime_policy::selection::retry_penalty::select_best_candidate;
use crate::runtime_policy::types::LearnedHostRecord;
use crate::runtime_policy::{
    ConnectionRoute, GeoMatcher, RetrySelectionPenalty, RuntimePolicy, TransportProtocol, now_millis,
};

pub(crate) fn select_host_route(
    policy: &RuntimePolicy,
    config: &RuntimeConfig,
    host: &str,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
    geo: Option<&dyn GeoMatcher>,
) -> Option<ConnectionRoute> {
    let record = policy.learned_hosts(config).get(host)?;
    if host_has_active_block(record, now_millis())
        && !record_has_learned_winner(record)
        && let Some(route) =
            select_blocked_host_route(policy, config, record, dest, payload, allow_unknown_payload, transport, geo)
    {
        return Some(route);
    }
    if let Some(route) = preferred_host_candidate(
        policy,
        config,
        record,
        dest,
        payload,
        allow_unknown_payload,
        transport,
        0,
        true,
        false,
        None,
        geo,
    ) {
        return Some(route);
    }

    let attempted_mask = record
        .preferred_groups
        .iter()
        .fold(0u64, |mask, &index| mask | config.groups.get(index).map_or(0, |group| group.bit));
    let mut rejected_mask = attempted_mask;
    let mut eligible = Vec::new();
    for &idx in policy.ordered_indices() {
        let group = config.groups.get(idx)?;
        if rejected_mask & group.bit != 0 || policy.detect_for(config, idx) != 0 {
            continue;
        }
        if group_matches_with_geo(config, group, dest, payload, allow_unknown_payload, transport, geo) {
            eligible.push(idx);
        } else {
            rejected_mask |= group.bit;
        }
    }
    if let Some(route) = select_best_candidate(policy, &eligible, rejected_mask, None) {
        return Some(route);
    }

    preferred_host_candidate(
        policy,
        config,
        record,
        dest,
        payload,
        allow_unknown_payload,
        transport,
        rejected_mask,
        true,
        true,
        None,
        geo,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn select_host_route_after(
    policy: &RuntimePolicy,
    config: &RuntimeConfig,
    route: &ConnectionRoute,
    host: &str,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
    trigger: u32,
    can_reconnect: bool,
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    geo: Option<&dyn GeoMatcher>,
) -> Option<ConnectionRoute> {
    let record = policy.learned_hosts(config).get(host)?;
    let mut attempted_mask = route.attempted_mask | config.groups[route.group_index].bit;
    if let Some(route) = preferred_host_candidate(
        policy,
        config,
        record,
        dest,
        payload,
        false,
        transport,
        attempted_mask,
        can_reconnect,
        false,
        retry_penalties,
        geo,
    ) {
        return Some(route);
    }

    attempted_mask |= record
        .preferred_groups
        .iter()
        .fold(0u64, |mask, &index| mask | config.groups.get(index).map_or(0, |group| group.bit));
    let mut rejected_mask = attempted_mask;
    let mut eligible = Vec::new();
    for &idx in policy.ordered_indices() {
        let group = config.groups.get(idx)?;
        let detect = policy.detect_for(config, idx);
        if rejected_mask & group.bit != 0 {
            continue;
        }
        if detect != 0 && (detect & trigger) == 0 {
            rejected_mask |= group.bit;
            continue;
        }
        if (detect & DETECT_RECONN) != 0 && !can_reconnect {
            rejected_mask |= group.bit;
            continue;
        }
        if group_matches_with_geo(config, group, dest, payload, false, transport, geo) {
            eligible.push(idx);
        } else {
            rejected_mask |= group.bit;
        }
    }
    if let Some(route) = select_best_candidate(policy, &eligible, rejected_mask, retry_penalties) {
        return Some(route);
    }

    preferred_host_candidate(
        policy,
        config,
        record,
        dest,
        payload,
        false,
        transport,
        rejected_mask,
        can_reconnect,
        true,
        retry_penalties,
        geo,
    )
}

fn select_blocked_host_route(
    policy: &RuntimePolicy,
    config: &RuntimeConfig,
    record: &LearnedHostRecord,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
    geo: Option<&dyn GeoMatcher>,
) -> Option<ConnectionRoute> {
    let now_ms = now_millis();
    let mut attempted_mask = 0u64;
    let mut eligible = Vec::new();
    for &idx in policy.ordered_indices() {
        let group = config.groups.get(idx)?;
        if policy.detect_for(config, idx) != 0 {
            continue;
        }
        if group_matches_with_geo(config, group, dest, payload, allow_unknown_payload, transport, geo) {
            eligible.push(idx);
        } else {
            attempted_mask |= group.bit;
        }
    }
    select_best_candidate_for_blocked_host(policy, record, now_ms, &eligible, attempted_mask)
}

#[allow(clippy::too_many_arguments)]
fn preferred_host_candidate(
    policy: &RuntimePolicy,
    config: &RuntimeConfig,
    record: &LearnedHostRecord,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
    attempted_mask: u64,
    can_reconnect: bool,
    penalized: bool,
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    geo: Option<&dyn GeoMatcher>,
) -> Option<ConnectionRoute> {
    let now_ms = now_millis();
    let mut next_mask = attempted_mask;
    let mut eligible = Vec::new();
    for &idx in &record.preferred_groups {
        let group = config.groups.get(idx)?;
        if next_mask & group.bit != 0 {
            continue;
        }
        if (policy.detect_for(config, idx) & DETECT_RECONN) != 0 && !can_reconnect {
            next_mask |= group.bit;
            continue;
        }
        let is_penalized = record.group_stats.get(&idx).is_some_and(|stats| stats.penalty_until_ms > now_ms);
        if is_penalized != penalized {
            next_mask |= group.bit;
            continue;
        }
        if group_matches_with_geo(config, group, dest, payload, allow_unknown_payload, transport, geo) {
            eligible.push(idx);
        } else {
            next_mask |= group.bit;
        }
    }
    select_best_candidate(policy, &eligible, next_mask, retry_penalties)
}

fn select_best_candidate_for_blocked_host(
    policy: &RuntimePolicy,
    record: &LearnedHostRecord,
    now_ms: u64,
    eligible: &[usize],
    attempted_mask: u64,
) -> Option<ConnectionRoute> {
    let mut ranked = eligible.to_vec();
    ranked.sort_by_key(|index| {
        let group = policy.groups.get(*index);
        (
            host_penalty_active_for_group(record, *index, now_ms),
            group.map_or(0, |value| value.fail_count),
            group.map_or(0, |value| value.pri),
            *index,
        )
    });
    ranked.into_iter().next().map(|group_index| ConnectionRoute { group_index, attempted_mask })
}
