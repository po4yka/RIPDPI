use crate::IpClass;
use crate::classify::classify_ip_packet;
use crate::uid_policy::{CachedFlowUidSource, PROTO_UDP, Verdict};

use super::dns_intercept::{dns_query_name, resolve_mapped_target, route_dns_packet};
use super::packet::is_injected_rst;
use super::state::LoopState;
use super::tcp_accept::ensure_pending_listen_for_syn;
use super::udp_assoc::{UdpForwardOutcome, forward_udp_payload};

const STUN_HEADER_LEN: usize = 20;
const STUN_MAGIC_COOKIE: [u8; 4] = 0x2112_A442_u32.to_be_bytes();

pub(in crate::io_loop) fn route_tun_packet(packet: &[u8], state: &mut LoopState) {
    route_tun_packet_inner(packet, state, true);
}

fn route_tun_packet_inner(packet: &[u8], state: &mut LoopState, run_egress_interceptor: bool) {
    let ip_class = classify_ip_packet(packet, state.runtime.mapdns_classify);
    let is_icmp = matches!(ip_class, IpClass::Icmp);
    if is_icmp {
        state.stats.icmp_ingress_packets.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    if is_icmp && state.runtime.uid_policy.is_enforcing() && !state.runtime.uid_policy_allow_icmp {
        return;
    }

    if run_egress_interceptor && state.runtime.tun_egress_interceptor.handle_packet(packet) {
        return;
    }

    match ip_class {
        IpClass::TcpOrOther | IpClass::Icmp => route_tcp_or_other_packet(packet, state),
        IpClass::UdpDns { src, dst, payload } => {
            if state.runtime.uid_policy.is_enforcing() {
                ripdpi_flow_app_attribution::request_uid_admission(PROTO_UDP, src, dst);
            }
            match state.runtime.uid_policy.admit(&CachedFlowUidSource, PROTO_UDP, src, dst) {
                Verdict::Pending => {
                    retain_pending_uid_udp(packet, state);
                    return;
                }
                Verdict::DropUdp | Verdict::ResetTcp => return,
                Verdict::Allow => {}
            }
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
            if let Some(resolved_dst) = resolve_mapped_target(&state.stats, &mut state.dns_cache, dst) {
                let outcome = forward_udp_payload(
                    state.runtime.proxy_sockaddr,
                    &state.runtime.auth,
                    src,
                    dst,
                    resolved_dst,
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
                    &state.runtime.uid_policy,
                );
                if matches!(outcome, UdpForwardOutcome::PendingUid) {
                    retain_pending_uid_udp(packet, state);
                }
            }
        }
    }
}

fn retain_pending_uid_udp(packet: &[u8], state: &mut LoopState) {
    state.pending_uid_udp_packets.retain(packet);
}

fn is_stun_datagram(payload: &[u8]) -> bool {
    if payload.len() < STUN_HEADER_LEN || payload[0] & 0b1100_0000 != 0 || payload[4..8] != STUN_MAGIC_COOKIE {
        return false;
    }

    let declared_len = usize::from(u16::from_be_bytes([payload[2], payload[3]]));
    declared_len.is_multiple_of(4) && STUN_HEADER_LEN.saturating_add(declared_len) <= payload.len()
}

pub(in crate::io_loop) fn retry_pending_uid_udp(state: &mut LoopState) {
    let attempts = state.pending_uid_udp_packets.len().min(super::IO_PHASE_WORK_BUDGET);
    for _ in 0..attempts {
        let Some(packet) = state.pending_uid_udp_packets.pop_front() else {
            break;
        };
        route_tun_packet_inner(&packet, state, false);
        state.pending_uid_udp_packets.recycle(packet);
    }
}

fn route_tcp_or_other_packet(packet: &[u8], state: &mut LoopState) {
    if state.runtime.filter_injected_resets && is_injected_rst(packet) {
        return;
    }

    ensure_pending_listen_for_syn(packet, &mut state.pending_listens, &mut state.socket_set);
    state.device.push_rx(packet.to_vec());
}
