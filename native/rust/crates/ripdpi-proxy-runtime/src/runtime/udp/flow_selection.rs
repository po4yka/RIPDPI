use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::{
    config::{route_matches_transport_payload, udp_bind_low_port},
    decision::{ConnectionRoute, TransportProtocol},
    session::new_session_state,
};

use super::client_receive::UdpClientPacket;
use super::flow::{udp_flow_at_capacity, UdpFlowActivationState};
use super::sockets::build_udp_upstream_socket;
use super::upstream_pump::send_udp_flow_payload;
use crate::runtime::routing::{preferred_targets_for_transport, select_route_for_transport};
use crate::runtime::state::RuntimeState;

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

pub(super) fn ensure_udp_flow_selected(
    state: &RuntimeState,
    protect_path: Option<&str>,
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    flow_limit: usize,
    packet: &UdpClientPacket<'_>,
    now: Instant,
) -> io::Result<bool> {
    let flow_key = packet.flow_key();
    if udp_flow_at_capacity(flow_state, flow_key, flow_limit) {
        tracing::warn!(
            client = %packet.sender,
            target = %packet.original_target,
            flows = flow_state.len(),
            limit = flow_limit,
            "UDP flow rejected: at capacity"
        );
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_client_slot_exhausted();
        }
        return Ok(false);
    }

    let entry = match flow_state.entry(flow_key) {
        std::collections::hash_map::Entry::Occupied(entry) => entry.into_mut(),
        std::collections::hash_map::Entry::Vacant(entry) => {
            let Some(initial_entry) = build_initial_udp_flow_entry(state, protect_path, packet, now)? else {
                return Ok(false);
            };
            entry.insert(initial_entry)
        }
    };
    update_udp_flow_selection(state, protect_path, entry, packet)
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
        let bind_low_port = udp_bind_low_port(&state.config, route.group_index);
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
        state.policy().store_route(
            &state.config,
            entry.current_target,
            entry.route.group_index,
            entry.route.attempted_mask,
            Some(host),
        );
    }
    Ok(())
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

fn build_initial_udp_flow_entry(
    state: &RuntimeState,
    protect_path: Option<&str>,
    packet: &UdpClientPacket<'_>,
    now: Instant,
) -> io::Result<Option<UdpFlowActivationState>> {
    let target_candidates =
        preferred_targets_for_transport(state, packet.original_target, packet.host.as_deref(), TransportProtocol::Udp);
    let Some(selection) = select_udp_flow_target(
        state,
        protect_path,
        packet.host.as_deref(),
        packet.payload,
        &target_candidates,
        0,
        "initial",
    )?
    else {
        return Ok(None);
    };
    let entry = UdpFlowActivationState {
        session: new_session_state(),
        last_used: now,
        route: selection.route,
        host: packet.host.clone(),
        payload: Vec::new(),
        awaiting_response: true,
        upstream: selection.upstream,
        quic_migrated: false,
        current_target: selection.target,
        target_candidates,
        target_index: selection.target_index,
        cache_host: packet.cache_host,
    };
    store_udp_route_hint(state, &entry)?;
    Ok(Some(entry))
}

fn update_udp_flow_selection(
    state: &RuntimeState,
    protect_path: Option<&str>,
    entry: &mut UdpFlowActivationState,
    packet: &UdpClientPacket<'_>,
) -> io::Result<bool> {
    let host_changed = entry.host.as_deref() != packet.host.as_deref();
    entry.host = packet.host.clone();
    entry.cache_host = packet.cache_host;
    if host_changed
        || !route_matches_transport_payload(
            &state.config,
            entry.route.group_index,
            entry.current_target,
            packet.payload,
            TransportProtocol::Udp,
        )
    {
        let Some(selection) = reselect_udp_flow_target(
            state,
            protect_path,
            packet.original_target,
            packet.payload,
            packet.host.as_deref(),
        )?
        else {
            return Ok(false);
        };
        entry.route = selection.route;
        entry.upstream = selection.upstream;
        entry.current_target = selection.target;
        entry.target_candidates = selection.target_candidates;
        entry.target_index = selection.target_index;
        entry.quic_migrated = false;
        store_udp_route_hint(state, entry)?;
    }
    Ok(true)
}
