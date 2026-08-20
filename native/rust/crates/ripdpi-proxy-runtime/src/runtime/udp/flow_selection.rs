use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use super::client_receive::UdpClientPacket;
use super::flow::{UdpFlowActivationState, UdpFlowKey, udp_flow_at_capacity};
use super::session::UdpFlowSession;
use super::sockets::build_udp_upstream_socket;
use super::upstream_pump::send_udp_flow_payload;
use super::upstream_socks::{UpstreamUdpSocks, open_upstream_udp_associate};
use super::{RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy, UdpFlowGroupPolicy};
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::routing::{preferred_targets_for_transport, select_route_for_transport};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

pub(super) struct UdpFlowSelection {
    pub(super) target: SocketAddr,
    pub(super) target_index: usize,
    pub(super) route: RuntimeConnectionRoute,
    pub(super) socket_settings: RuntimeUdpSocketSettings,
    pub(super) packet_settings: RuntimeUdpPacketSettings,
    pub(super) source_rebind_policy: RuntimeUdpSourceRebindPolicy,
    pub(super) execution_family: Option<&'static str>,
    pub(super) upstream: UdpSocket,
    pub(super) upstream_socks: Option<UpstreamUdpSocks>,
}

pub(super) struct UdpFlowSelectionWithCandidates {
    pub(super) target: SocketAddr,
    pub(super) target_index: usize,
    pub(super) route: RuntimeConnectionRoute,
    pub(super) socket_settings: RuntimeUdpSocketSettings,
    pub(super) packet_settings: RuntimeUdpPacketSettings,
    pub(super) source_rebind_policy: RuntimeUdpSourceRebindPolicy,
    pub(super) execution_family: Option<&'static str>,
    pub(super) upstream: UdpSocket,
    pub(super) upstream_socks: Option<UpstreamUdpSocks>,
    pub(super) target_candidates: Vec<SocketAddr>,
}

/// Build the protected upstream UDP socket for a flow, plus (when the group
/// configures `ext_socks`) the live SOCKS5 UDP ASSOCIATE session.
///
/// Direct path: the upstream socket connects straight to `target`.
/// Upstream-SOCKS path: a protected control TCP runs the ASSOCIATE handshake to
/// learn the relay `BND.ADDR:BND.PORT`, then the protected upstream UDP socket
/// connects to that relay endpoint (NOT `target`). Both sockets are protected by
/// their respective platform constructors before any connect/bind.
fn build_udp_flow_upstream(
    target: SocketAddr,
    protect_path: Option<&str>,
    group_policy: &UdpFlowGroupPolicy,
) -> io::Result<(UdpSocket, Option<UpstreamUdpSocks>)> {
    let Some(upstream_socks_addr) = group_policy.upstream_socks_addr else {
        let socket = build_udp_upstream_socket(target, protect_path, group_policy.socket.bind_low_port)?;
        return Ok((socket, None));
    };
    let session = open_upstream_udp_associate(upstream_socks_addr, protect_path, group_policy.connect_timeout)?;
    let socket = build_udp_upstream_socket(session.relay_endpoint, protect_path, group_policy.socket.bind_low_port)?;
    Ok((socket, Some(session)))
}

pub(super) fn ensure_udp_flow_selected(
    state: &RuntimeState,
    protect_path: Option<&str>,
    flow_state: &mut HashMap<UdpFlowKey, UdpFlowActivationState>,
    flow_limit: usize,
    packet: &UdpClientPacket<'_>,
    now: Instant,
    attempt_token: Option<&AttemptCorrelationId>,
) -> io::Result<bool> {
    let flow_key = packet.flow_key();
    if udp_flow_at_capacity(flow_state, &flow_key, flow_limit) {
        tracing::warn!(
            client = %packet.sender,
            target = %packet.original_target,
            flows = flow_state.len(),
            limit = flow_limit,
            "UDP flow rejected: at capacity"
        );
        state.note_client_slot_exhausted();
        return Ok(false);
    }

    let entry = match flow_state.entry(flow_key) {
        std::collections::hash_map::Entry::Occupied(entry) => entry.into_mut(),
        std::collections::hash_map::Entry::Vacant(entry) => {
            let Some(initial_entry) = build_initial_udp_flow_entry(state, protect_path, packet, now, attempt_token)?
            else {
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
    egress: DestinationEgress,
) -> io::Result<Option<UdpFlowSelection>> {
    if egress == DestinationEgress::Block {
        return Ok(None);
    }
    for (target_index, &target) in target_candidates.iter().enumerate().skip(start_index) {
        let Ok(route) =
            select_route_for_transport(state, target, Some(payload), host, false, RuntimeTransportProtocol::Udp)
        else {
            continue;
        };
        state.note_route_selected(target, route.group_index, host, phase);
        let Some(group_policy) = state.udp_flow_group_policy(route.group_index, egress) else {
            continue;
        };
        let socket_settings = group_policy.socket;
        let packet_settings = group_policy.packet;
        let source_rebind_policy = group_policy.source_rebind;
        let execution_family = group_policy.execution_family;
        let Ok((upstream, upstream_socks)) = build_udp_flow_upstream(target, protect_path, &group_policy) else {
            continue;
        };
        return Ok(Some(UdpFlowSelection {
            target,
            target_index,
            route,
            socket_settings,
            packet_settings,
            source_rebind_policy,
            execution_family,
            upstream,
            upstream_socks,
        }));
    }
    Ok(None)
}

pub(super) fn reselect_udp_flow_target(
    state: &RuntimeState,
    protect_path: Option<&str>,
    original_target: SocketAddr,
    payload: &[u8],
    host: Option<&str>,
    egress: DestinationEgress,
) -> io::Result<Option<UdpFlowSelectionWithCandidates>> {
    let target_candidates =
        preferred_targets_for_transport(state, original_target, host, RuntimeTransportProtocol::Udp);
    let Some(selection) =
        select_udp_flow_target(state, protect_path, host, payload, &target_candidates, 0, "payload_reselect", egress)?
    else {
        return Ok(None);
    };
    Ok(Some(UdpFlowSelectionWithCandidates {
        target: selection.target,
        target_index: selection.target_index,
        route: selection.route,
        socket_settings: selection.socket_settings,
        packet_settings: selection.packet_settings,
        source_rebind_policy: selection.source_rebind_policy,
        execution_family: selection.execution_family,
        upstream: selection.upstream,
        upstream_socks: selection.upstream_socks,
        target_candidates,
    }))
}

pub(super) fn store_udp_route_hint(state: &RuntimeState, entry: &UdpFlowActivationState) -> io::Result<()> {
    if let Some(host) = entry.host.clone().filter(|_| entry.cache_host) {
        state.store_udp_route_hint(
            entry.logical_target,
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
        entry.destination_egress,
    )? {
        entry.route = selection.route;
        entry.socket_settings = selection.socket_settings;
        entry.packet_settings = selection.packet_settings;
        entry.source_rebind_policy = selection.source_rebind_policy;
        entry.execution_family = selection.execution_family;
        entry.upstream = selection.upstream;
        entry.upstream_socks = selection.upstream_socks;
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
    attempt_token: Option<&AttemptCorrelationId>,
) -> io::Result<Option<UdpFlowActivationState>> {
    let destination_egress =
        state.destination_egress(packet.original_target, packet.host.as_deref(), RuntimeTransportProtocol::Udp);
    if destination_egress == DestinationEgress::Block {
        return Ok(None);
    }
    let target_candidates = preferred_targets_for_transport(
        state,
        packet.original_target,
        packet.host.as_deref(),
        RuntimeTransportProtocol::Udp,
    );
    let Some(selection) = select_udp_flow_target(
        state,
        protect_path,
        packet.host.as_deref(),
        packet.payload,
        &target_candidates,
        0,
        "initial",
        destination_egress,
    )?
    else {
        return Ok(None);
    };
    let entry = UdpFlowActivationState {
        session: UdpFlowSession::new(),
        last_used: now,
        route: selection.route,
        destination_egress,
        socket_settings: selection.socket_settings,
        packet_settings: selection.packet_settings,
        source_rebind_policy: selection.source_rebind_policy,
        execution_family: selection.execution_family,
        attempt_token: attempt_token.cloned(),
        host: packet.host.clone(),
        payload: Vec::new(),
        awaiting_response: true,
        upstream: selection.upstream,
        quic_migrated: false,
        logical_target: packet.original_target,
        current_target: selection.target,
        target_candidates,
        target_index: selection.target_index,
        cache_host: packet.cache_host,
        upstream_socks: selection.upstream_socks,
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
        || !state.route_matches_transport_payload(
            entry.route.group_index,
            entry.current_target,
            packet.payload,
            RuntimeTransportProtocol::Udp,
        )
    {
        let Some(selection) = reselect_udp_flow_target(
            state,
            protect_path,
            packet.original_target,
            packet.payload,
            packet.host.as_deref(),
            entry.destination_egress,
        )?
        else {
            return Ok(false);
        };
        entry.route = selection.route;
        entry.socket_settings = selection.socket_settings;
        entry.packet_settings = selection.packet_settings;
        entry.source_rebind_policy = selection.source_rebind_policy;
        entry.execution_family = selection.execution_family;
        entry.upstream = selection.upstream;
        entry.upstream_socks = selection.upstream_socks;
        entry.current_target = selection.target;
        entry.target_candidates = selection.target_candidates;
        entry.target_index = selection.target_index;
        entry.quic_migrated = false;
        store_udp_route_hint(state, entry)?;
    }
    Ok(true)
}
