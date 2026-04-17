use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_config::{QuicInitialMode, RuntimeConfig, DETECT_CONNECT};
use ripdpi_desync::{plan_udp, ActivationTransport, DesyncAction};
use ripdpi_session::SessionState;

use super::actions::{execute_udp_actions, UdpActionExecContext};
use super::sockets::build_udp_upstream_socket;
use crate::runtime::adaptive::{note_adaptive_udp_failure, note_evolver_failure, resolve_udp_hints_with_evolver};
use crate::runtime::morph::{emit_morph_hint_applied, udp_morph_hint_family};
use crate::runtime::retry::{
    build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure,
};
use crate::runtime::routing::{
    note_block_signal_for_failure, preferred_targets_for_transport, select_route_for_transport,
};
use crate::runtime::state::{flush_autolearn_updates, RuntimeState, UDP_FLOW_IDLE_TIMEOUT};
use crate::runtime_policy::{ConnectionRoute, ExtractedHost, HostSource, RouteAdvance, TransportProtocol};

pub(super) struct UdpFlowActivationState {
    pub(super) session: SessionState,
    pub(super) last_used: Instant,
    pub(super) route: ConnectionRoute,
    pub(super) host: Option<String>,
    pub(super) payload: Vec<u8>,
    pub(super) awaiting_response: bool,
    pub(super) upstream: UdpSocket,
    pub(super) quic_migrated: bool,
    pub(super) current_target: SocketAddr,
    pub(super) target_candidates: Vec<SocketAddr>,
    pub(super) target_index: usize,
    pub(super) cache_host: bool,
}

pub(super) struct UdpFlowSelection {
    pub(super) target: SocketAddr,
    pub(super) target_index: usize,
    pub(super) route: ConnectionRoute,
    pub(super) upstream: UdpSocket,
}

pub(super) struct UdpFlowSelectionWithCandidates {
    pub(super) target: SocketAddr,
    pub(super) target_index: usize,
    pub(super) route: ConnectionRoute,
    pub(super) upstream: UdpSocket,
    pub(super) target_candidates: Vec<SocketAddr>,
}

pub(super) fn udp_flow_limit(config: &RuntimeConfig) -> usize {
    config.network.max_open.max(1) as usize
}

pub(super) fn udp_flow_at_capacity<T>(
    flow_state: &HashMap<(SocketAddr, SocketAddr), T>,
    flow_key: (SocketAddr, SocketAddr),
    flow_limit: usize,
) -> bool {
    !flow_state.contains_key(&flow_key) && flow_state.len() >= flow_limit
}

pub(super) fn expire_udp_flows(
    state: &RuntimeState,
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    protect_path: Option<&str>,
    now: Instant,
) -> io::Result<()> {
    let expired = flow_state
        .iter()
        .filter(|(_, value)| now.duration_since(value.last_used) >= UDP_FLOW_IDLE_TIMEOUT)
        .map(|(key, _)| *key)
        .collect::<Vec<_>>();

    for (client_addr, target) in expired {
        let Some(mut entry) = flow_state.remove(&(client_addr, target)) else {
            continue;
        };
        if !entry.awaiting_response {
            continue;
        }
        if try_advance_udp_preferred_target(state, protect_path, &mut entry, now)? {
            flow_state.insert((client_addr, target), entry);
            continue;
        }
        if let Some(failure) = ripdpi_failure_classifier::classify_quic_probe(
            "quic_timeout",
            Some("UDP flow expired before first response"),
        ) {
            note_block_signal_for_failure(state, entry.host.as_deref(), &failure, None);
        }
        let failed_target = entry.current_target;
        let _ = note_retry_failure(
            state,
            failed_target,
            entry.route.group_index,
            entry.host.as_deref(),
            Some(entry.payload.as_slice()),
            TransportProtocol::Udp,
        )?;
        note_adaptive_udp_failure(
            state,
            failed_target,
            entry.route.group_index,
            entry.host.as_deref(),
            &entry.payload,
        )?;
        note_evolver_failure(state, ripdpi_failure_classifier::FailureClass::SilentDrop);
        let retry_penalties = build_retry_selection_penalties(
            state,
            failed_target,
            entry.host.as_deref(),
            Some(entry.payload.as_slice()),
            TransportProtocol::Udp,
        )?;
        let next = {
            let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
            let next = cache.advance_route(
                &state.config,
                &entry.route,
                RouteAdvance {
                    dest: failed_target,
                    payload: Some(entry.payload.as_slice()),
                    transport: TransportProtocol::Udp,
                    trigger: DETECT_CONNECT,
                    can_reconnect: true,
                    host: entry.host.clone(),
                    penalize_strategy_failure: false,
                    retry_penalties: Some(&retry_penalties),
                },
            )?;
            flush_autolearn_updates(state, &mut cache);
            next
        };
        if let Some(next_route) = next.as_ref() {
            maybe_emit_candidate_diversification(state, failed_target, next_route, &retry_penalties);
        }
        if let (Some(telemetry), Some(next)) = (&state.telemetry, next) {
            telemetry.on_route_advanced(
                failed_target,
                entry.route.group_index,
                next.group_index,
                DETECT_CONNECT,
                entry.host.as_deref(),
            );
        }
    }
    Ok(())
}

pub(super) fn should_migrate_quic_flow(config: &RuntimeConfig, route: &ConnectionRoute) -> bool {
    config.groups.get(route.group_index).is_some_and(|group| group.actions.quic_migrate_after_handshake)
}

pub(super) fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => matches!(config.quic.initial_mode, QuicInitialMode::RouteAndCache),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

pub(super) fn select_udp_flow_target(
    state: &RuntimeState,
    protect_path: Option<&str>,
    host: Option<&str>,
    payload: &[u8],
    target_candidates: &[SocketAddr],
    start_index: usize,
    phase: &'static str,
) -> io::Result<Option<UdpFlowSelection>> {
    for (target_index, &target) in target_candidates.iter().enumerate().skip(start_index) {
        let Ok(route) = select_route_for_transport(state, target, Some(payload), host, false, TransportProtocol::Udp)
        else {
            continue;
        };
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_route_selected(target, route.group_index, host, phase);
        }
        let bind_low_port =
            state.config.groups.get(route.group_index).is_some_and(|group| group.actions.quic_bind_low_port);
        let Ok(upstream) = build_udp_upstream_socket(target, protect_path, bind_low_port) else {
            continue;
        };
        return Ok(Some(UdpFlowSelection { target, target_index, route, upstream }));
    }
    Ok(None)
}

pub(super) fn reselect_udp_flow_target(
    state: &RuntimeState,
    protect_path: Option<&str>,
    original_target: SocketAddr,
    payload: &[u8],
    host: Option<&str>,
) -> io::Result<Option<UdpFlowSelectionWithCandidates>> {
    let target_candidates = preferred_targets_for_transport(state, original_target, host, TransportProtocol::Udp);
    let Some(selection) =
        select_udp_flow_target(state, protect_path, host, payload, &target_candidates, 0, "payload_reselect")?
    else {
        return Ok(None);
    };
    Ok(Some(UdpFlowSelectionWithCandidates {
        target: selection.target,
        target_index: selection.target_index,
        route: selection.route,
        upstream: selection.upstream,
        target_candidates,
    }))
}

pub(super) fn store_udp_route_hint(state: &RuntimeState, entry: &UdpFlowActivationState) -> io::Result<()> {
    if let Some(host) = entry.host.clone().filter(|_| entry.cache_host) {
        let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
        cache.store(
            &state.config,
            entry.current_target,
            entry.route.group_index,
            entry.route.attempted_mask,
            Some(host),
        );
    }
    Ok(())
}

pub(super) fn plan_udp_flow_actions(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    payload: &[u8],
    now: Instant,
) -> io::Result<Vec<DesyncAction>> {
    let group =
        state.config.groups.get(entry.route.group_index).ok_or_else(|| io::Error::other("missing udp route group"))?;
    let adaptive_hints = resolve_udp_hints_with_evolver(
        state,
        entry.current_target,
        entry.route.group_index,
        group,
        entry.host.as_deref(),
        payload,
    )?;
    emit_morph_hint_applied(state, entry.current_target, udp_morph_hint_family(state, adaptive_hints));
    entry.last_used = now;
    entry.payload.clear();
    entry.payload.extend_from_slice(payload);
    entry.awaiting_response = true;
    let progress = entry.session.observe_datagram_outbound(payload);
    let activation = super::super::desync::activation_context_from_progress(
        progress,
        ActivationTransport::Udp,
        Some(payload),
        None,
        None,
        None,
        adaptive_hints,
    );
    Ok(plan_udp(group, payload, state.config.network.default_ttl, activation))
}

pub(super) fn send_udp_flow_payload(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    payload: &[u8],
    now: Instant,
    protect_path: Option<&str>,
) -> io::Result<()> {
    let actions = plan_udp_flow_actions(state, entry, payload, now)?;
    let exec_ctx = UdpActionExecContext {
        upstream: &entry.upstream,
        target: entry.current_target,
        default_ttl: state.config.network.default_ttl,
        protect_path,
        ip_id_mode: state.config.groups[entry.route.group_index].actions.ip_id_mode,
    };
    execute_udp_actions(exec_ctx, &actions)
}

pub(super) fn try_advance_udp_preferred_target(
    state: &RuntimeState,
    protect_path: Option<&str>,
    entry: &mut UdpFlowActivationState,
    now: Instant,
) -> io::Result<bool> {
    let payload = entry.payload.clone();
    let mut next_index = entry.target_index + 1;
    while let Some(selection) = select_udp_flow_target(
        state,
        protect_path,
        entry.host.as_deref(),
        payload.as_slice(),
        &entry.target_candidates,
        next_index,
        "edge_fallback",
    )? {
        entry.route = selection.route;
        entry.upstream = selection.upstream;
        entry.current_target = selection.target;
        entry.target_index = selection.target_index;
        entry.quic_migrated = false;
        store_udp_route_hint(state, entry)?;
        match send_udp_flow_payload(state, entry, payload.as_slice(), now, protect_path) {
            Ok(()) => return Ok(true),
            Err(_) => next_index = entry.target_index + 1,
        }
    }
    Ok(false)
}
