use std::time::Instant;

use ripdpi_flow_app_attribution::FlowRegistrationId;

use crate::IpClass;
use crate::classify::classify_ip_packet_with_parse_status;
use crate::uid_policy::{PROTO_TCP, PROTO_UDP, Verdict};

use super::dns_intercept::{
    dns_query_name, resolve_mapped_destination, route_dns_packet, sync_direct_dns_mapping_generation,
};
use super::packet::{TcpFlowKey, build_tcp_reset, is_injected_rst, tcp_packet_endpoints};
use super::state::LoopState;
use super::state::PendingUidRetainOutcome;
use super::tcp_accept::ensure_pending_listen_for_syn;
use super::udp_assoc::{forward_udp_payload, release_unowned_udp_attribution};

const STUN_HEADER_LEN: usize = 20;
const STUN_MAGIC_COOKIE: [u8; 4] = 0x2112_A442_u32.to_be_bytes();

pub(in crate::io_loop) fn route_tun_packet(packet: &[u8], state: &mut LoopState) {
    route_tun_packet_inner(packet, state, None);
}

fn route_tun_packet_inner(packet: &[u8], state: &mut LoopState, pending: Option<(&FlowRegistrationId, Instant)>) {
    let (ip_class, parsed_ip) = classify_ip_packet_with_parse_status(packet, state.runtime.mapdns_classify);
    if !parsed_ip {
        state.stats.record_tun_parse_failure();
    }
    let is_icmp = matches!(ip_class, IpClass::Icmp);
    if is_icmp {
        state.stats.icmp_ingress_packets.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    if is_icmp && state.runtime.uid_policy.is_enforcing() && !state.runtime.uid_policy_allow_icmp {
        state.stats.record_tun_policy_drop();
        return;
    }

    match ip_class {
        IpClass::TcpOrOther => {
            if state.runtime.uid_policy.is_enforcing() {
                let Some((src, dst)) = tcp_packet_endpoints(packet) else {
                    state.stats.record_tun_policy_drop();
                    return;
                };
                let key = TcpFlowKey { src, dst };
                // Allocate a parked listener before queuing UID work. It owns
                // the generation, but no unadmitted bytes reach smoltcp.
                ensure_pending_listen_for_syn(packet, &mut state.pending_listens, &mut state.socket_set);
                let registration_id = pending.map_or_else(
                    || {
                        state.pending_listens.get(&key).map_or_else(
                            || ripdpi_flow_app_attribution::note_flow(PROTO_TCP, src, dst).registration_id,
                            |listener| *listener.attribution_id(),
                        )
                    },
                    |(registration_id, _)| *registration_id,
                );
                match state.runtime.uid_policy.admit_registration(&registration_id) {
                    Verdict::Pending => {
                        retain_pending_uid_packet(packet, state, registration_id, pending.map(|(_, time)| time));
                        return;
                    }
                    Verdict::ResetTcp | Verdict::DropUdp => {
                        retire_denied_tcp_flow(state, key);
                        ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
                        state.stats.record_tun_policy_drop();
                        if let Some(reset) = build_tcp_reset(packet) {
                            state.device.tx_queue.push_back(reset);
                        }
                        return;
                    }
                    Verdict::Allow => {}
                }
                if intercept_packet(packet, state) {
                    if !state
                        .pending_listens
                        .get(&key)
                        .is_some_and(|listener| listener.attribution_id() == &registration_id)
                        && !state
                            .sessions
                            .iter_mut()
                            .any(|(_, entry)| entry.attribution_id.as_ref() == Some(&registration_id))
                    {
                        ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
                    }
                    return;
                }
            } else if intercept_packet(packet, state) {
                return;
            }
            route_tcp_or_other_packet(packet, state);
        }
        IpClass::Icmp => {
            if !intercept_packet(packet, state) {
                route_tcp_or_other_packet(packet, state);
            }
        }
        IpClass::UdpDns { src, dst, payload } => {
            if state.runtime.uid_policy.is_enforcing() {
                let registration_id = pending.map_or_else(
                    || ripdpi_flow_app_attribution::request_uid_admission(PROTO_UDP, src, dst),
                    |(registration_id, _)| *registration_id,
                );
                match state.runtime.uid_policy.admit_registration(&registration_id) {
                    Verdict::Pending => {
                        retain_pending_uid_packet(packet, state, registration_id, pending.map(|(_, time)| time));
                        return;
                    }
                    Verdict::DropUdp | Verdict::ResetTcp => {
                        state.stats.record_tun_policy_drop();
                        return;
                    }
                    Verdict::Allow => {}
                }
            }
            if intercept_packet(packet, state) {
                return;
            }
            sync_direct_dns_mapping_generation(state.dns_cache.as_mut(), &mut state.active_direct_dns_generation);
            let host = dns_query_name(payload);
            route_dns_packet(
                &mut state.device,
                &state.stats,
                state.runtime.mapdns_runtime,
                state.dns_cache.as_ref(),
                state.runtime.split_dns_policy.as_ref(),
                &mut state.dns_req_tx,
                &mut state.dns_resp_rx,
                src,
                payload,
                host,
            );
        }
        IpClass::Udp { src, dst, payload } => {
            if state.runtime.webrtc_protection_enabled && is_stun_datagram(payload) {
                state.stats.record_tun_policy_drop();
                return;
            }
            if !state.runtime.uid_policy.is_enforcing() && intercept_packet(packet, state) {
                return;
            }
            let registration_id = pending.map_or_else(
                || ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, dst).registration_id,
                |(registration_id, _)| *registration_id,
            );
            match state.runtime.uid_policy.admit_registration(&registration_id) {
                Verdict::Pending => {
                    retain_pending_uid_packet(packet, state, registration_id, pending.map(|(_, time)| time));
                    return;
                }
                Verdict::DropUdp | Verdict::ResetTcp => {
                    ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
                    state.stats.record_tun_policy_drop();
                    return;
                }
                Verdict::Allow => {}
            }
            // A raw hook can send even when it does not consume the packet.
            if state.runtime.uid_policy.is_enforcing() && intercept_packet(packet, state) {
                release_unowned_udp_attribution(&state.udp_associations, src, registration_id);
                return;
            }
            state.stats.record_dht_trigger_destination(dst);
            let synthetic_ip = match dst.ip() {
                std::net::IpAddr::V4(ip)
                    if state.dns_cache.as_ref().is_some_and(|cache| cache.contains_mapped_ip(u32::from(ip))) =>
                {
                    Some(u32::from(ip))
                }
                _ => None,
            };
            if let Some(resolved) = resolve_mapped_destination(
                &state.stats,
                &mut state.dns_cache,
                Some(&mut state.active_direct_dns_generation),
                dst,
            ) {
                forward_udp_payload(
                    state.runtime.proxy_sockaddr,
                    &state.runtime.auth,
                    src,
                    dst,
                    resolved.addr,
                    resolved.host.as_deref(),
                    synthetic_ip,
                    payload,
                    &mut state.dns_cache,
                    &mut state.udp_associations,
                    &mut state.udp_eviction_heap,
                    &state.udp_memory_budget,
                    &mut state.next_udp_association_id,
                    state.runtime.udp_idle_timeout,
                    state.runtime.protect_path.as_deref(),
                    &state.cancel,
                    &state.udp_tx,
                    &state.stats,
                    registration_id,
                );
            }
        }
    }
}

fn retire_denied_tcp_flow(state: &mut LoopState, key: TcpFlowKey) {
    if let Some(listener) = state.pending_listens.remove(&key) {
        state.socket_set.remove(listener.handle);
    }
    let handle = state.sessions.iter_mut().find_map(|(handle, entry)| {
        entry.attribution_id.as_ref().and_then(|registration_id| {
            let request = registration_id.request();
            (request.local == key.src && request.remote == key.dst).then_some(handle)
        })
    });
    if let Some(handle) = handle {
        super::bridge::remove_session(handle, &mut state.sessions, &mut state.socket_set, &mut state.dns_cache);
    }
}

fn intercept_packet(packet: &[u8], state: &mut LoopState) -> bool {
    let consumed = state.runtime.tun_egress_interceptor.handle_packet(packet);
    if consumed {
        state.stats.record_tun_interceptor_drop();
    }
    consumed
}

fn retain_pending_uid_packet(
    packet: &[u8],
    state: &mut LoopState,
    registration_id: FlowRegistrationId,
    captured_at: Option<Instant>,
) {
    match state.pending_uid_packets.retain(packet, registration_id, captured_at.unwrap_or_else(Instant::now)) {
        PendingUidRetainOutcome::Stored => {}
        PendingUidRetainOutcome::EvictedOldest | PendingUidRetainOutcome::Rejected => {
            state.stats.record_tun_queue_drop();
        }
    }
}

fn is_stun_datagram(payload: &[u8]) -> bool {
    if payload.len() < STUN_HEADER_LEN || payload[0] & 0b1100_0000 != 0 || payload[4..8] != STUN_MAGIC_COOKIE {
        return false;
    }

    let declared_len = usize::from(u16::from_be_bytes([payload[2], payload[3]]));
    declared_len.is_multiple_of(4) && STUN_HEADER_LEN.saturating_add(declared_len) <= payload.len()
}

pub(in crate::io_loop) fn retry_pending_uid_packets(state: &mut LoopState) {
    let attempts = state.pending_uid_packets.len().min(super::IO_PHASE_WORK_BUDGET);
    for _ in 0..attempts {
        let Some(packet) = state.pending_uid_packets.pop_front() else {
            break;
        };
        if packet.expired()
            || matches!(
                ripdpi_flow_app_attribution::lookup_registered_flow_uid(&packet.registration_id),
                ripdpi_flow_app_attribution::FlowUidLookup::Missing
            )
        {
            state.stats.record_tun_policy_drop();
        } else {
            route_tun_packet_inner(&packet.bytes, state, Some((&packet.registration_id, packet.captured_at)));
        }
        state.pending_uid_packets.recycle(packet);
    }
}

fn route_tcp_or_other_packet(packet: &[u8], state: &mut LoopState) {
    if state.runtime.filter_injected_resets && is_injected_rst(packet) {
        state.stats.record_tun_policy_drop();
        return;
    }

    ensure_pending_listen_for_syn(packet, &mut state.pending_listens, &mut state.socket_set);
    if !state.device.push_rx(packet.to_vec()) {
        state.stats.record_tun_queue_drop();
    }
}
