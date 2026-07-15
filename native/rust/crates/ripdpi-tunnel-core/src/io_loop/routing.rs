use crate::IpClass;
use crate::classify::classify_ip_packet;

use super::dns_intercept::{dns_query_name, resolve_mapped_target, route_dns_packet};
use super::packet::is_injected_rst;
use super::state::LoopState;
use super::tcp_accept::ensure_pending_listen_for_syn;
use super::udp_assoc::{UdpForwardOutcome, forward_udp_payload};

const PENDING_UID_UDP_CAPACITY: usize = 256;

pub(in crate::io_loop) fn route_tun_packet(packet: &[u8], state: &mut LoopState) {
    route_tun_packet_inner(packet, state, true);
}

fn route_tun_packet_inner(packet: &[u8], state: &mut LoopState, run_egress_interceptor: bool) {
    if run_egress_interceptor && state.runtime.tun_egress_interceptor.handle_packet(packet) {
        return;
    }

    match classify_ip_packet(packet, state.runtime.mapdns_classify) {
        IpClass::TcpOrOther => route_tcp_or_other_packet(packet, state),
        IpClass::UdpDns { src, payload } => {
            let host = dns_query_name(payload);
            route_dns_packet(
                &mut state.device,
                &state.stats,
                state.runtime.mapdns_runtime,
                state.dns_cache.as_ref(),
                &mut state.dns_req_tx,
                &mut state.dns_resp_rx,
                src,
                payload,
                host,
            );
        }
        IpClass::Udp { src, dst, payload } => {
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
                    if state.pending_uid_udp_packets.len() >= PENDING_UID_UDP_CAPACITY {
                        state.pending_uid_udp_packets.pop_front();
                    }
                    state.pending_uid_udp_packets.push_back(packet.to_vec());
                }
            }
        }
    }
}

pub(in crate::io_loop) fn retry_pending_uid_udp(state: &mut LoopState) {
    let attempts = state.pending_uid_udp_packets.len().min(super::IO_PHASE_WORK_BUDGET);
    for _ in 0..attempts {
        let Some(packet) = state.pending_uid_udp_packets.pop_front() else {
            break;
        };
        route_tun_packet_inner(&packet, state, false);
    }
}

fn route_tcp_or_other_packet(packet: &[u8], state: &mut LoopState) {
    if state.runtime.filter_injected_resets && is_injected_rst(packet) {
        return;
    }

    ensure_pending_listen_for_syn(packet, &mut state.pending_listens, &mut state.socket_set);
    state.device.push_rx(packet.to_vec());
}
