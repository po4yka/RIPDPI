use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ciadpi_desync::AdaptivePlannerHints;
use ripdpi_packets::parse_quic_initial;

use crate::retry_stealth::{adaptive_signature_hash, target_key, RetryDecision, RetryLane, RetrySignature};
use crate::runtime_policy::{RetrySelectionPenalty, TransportProtocol};

use super::adaptive::{
    network_scope_key, resolve_adaptive_fake_ttl, resolve_adaptive_tcp_hints, resolve_adaptive_udp_hints,
};
use super::state::RuntimeState;

pub(super) fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
}

pub(super) fn build_retry_signature(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<Option<RetrySignature>> {
    let Some(group) = state.config.groups.get(group_index) else {
        return Ok(None);
    };
    let resolved_fake_ttl = resolve_adaptive_fake_ttl(state, target, group_index, group, host)?;
    let adaptive_hints = match (transport, payload) {
        (TransportProtocol::Tcp, Some(bytes)) => {
            resolve_adaptive_tcp_hints(state, target, group_index, group, host, bytes)?
        }
        (TransportProtocol::Udp, Some(bytes)) => {
            resolve_adaptive_udp_hints(state, target, group_index, group, host, bytes)?
        }
        _ => AdaptivePlannerHints::default(),
    };
    Ok(Some(RetrySignature::new(
        network_scope_key(&state.config).unwrap_or("default"),
        retry_lane(transport, payload),
        target_key(host, target),
        group_index,
        adaptive_signature_hash(resolved_fake_ttl, &adaptive_hints),
    )))
}

pub(super) fn retry_lane(transport: TransportProtocol, payload: Option<&[u8]>) -> RetryLane {
    match transport {
        TransportProtocol::Tcp if payload.is_some_and(ripdpi_packets::is_tls_client_hello) => RetryLane::TcpTls,
        TransportProtocol::Tcp => RetryLane::TcpOther,
        TransportProtocol::Udp if payload.is_some_and(|bytes| parse_quic_initial(bytes).is_some()) => {
            RetryLane::UdpQuic
        }
        TransportProtocol::Udp => RetryLane::UdpOther,
    }
}

pub(super) fn note_retry_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<()> {
    let Some(signature) = build_retry_signature(state, target, group_index, host, payload, transport)? else {
        return Ok(());
    };
    let mut pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
    pacer.clear_success(&signature);
    Ok(())
}

pub(super) fn note_retry_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<Option<RetryDecision>> {
    let Some(signature) = build_retry_signature(state, target, group_index, host, payload, transport)? else {
        return Ok(None);
    };
    let mut pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
    Ok(Some(pacer.record_failure(&signature, now_millis())))
}

/// Builds retry selection penalties for all groups.
///
/// LOCK ORDERING: This function acquires the retry_stealth lock first, then calls
/// `build_retry_signature` which acquires adaptive locks (adaptive_fake_ttl, adaptive_tuning).
/// This establishes the retry -> adaptive lock ordering. Do not invert.
pub(super) fn build_retry_selection_penalties(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>> {
    let now_ms = now_millis();
    let mut penalties = BTreeMap::new();
    let pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
    for group_index in 0..state.config.groups.len() {
        if let Some(signature) = build_retry_signature(state, target, group_index, host, payload, transport)? {
            penalties.insert(group_index, pacer.penalty_for(&signature, now_ms));
        }
    }
    Ok(penalties)
}

pub(super) fn maybe_emit_candidate_diversification(
    state: &RuntimeState,
    target: SocketAddr,
    route: &crate::runtime_policy::ConnectionRoute,
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
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_retry_paced(target, route.group_index, "candidate_order_diversified", 0);
    }
}

pub(super) fn apply_retry_pacing_before_connect(
    state: &RuntimeState,
    target: SocketAddr,
    route: &crate::runtime_policy::ConnectionRoute,
    host: Option<&str>,
    payload: Option<&[u8]>,
) -> io::Result<()> {
    let Some(signature) =
        build_retry_signature(state, target, route.group_index, host, payload, TransportProtocol::Tcp)?
    else {
        return Ok(());
    };
    let decision = {
        let pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
        pacer.retry_delay_for(&signature, now_millis())
    };
    let Some(decision) = decision.filter(|value| value.backoff_ms > 0) else {
        return Ok(());
    };
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_retry_paced(target, route.group_index, decision.reason, decision.backoff_ms);
    }
    thread::sleep(Duration::from_millis(decision.backoff_ms));
    Ok(())
}
