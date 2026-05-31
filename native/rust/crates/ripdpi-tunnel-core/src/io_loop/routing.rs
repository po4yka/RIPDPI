use crate::classify::classify_ip_packet;
use crate::IpClass;

use super::dns_intercept::{dns_query_name, resolve_mapped_target, route_dns_packet};
use super::packet::is_injected_rst;
use super::state::LoopState;
use super::tcp_accept::ensure_pending_listen_for_syn;
use super::udp_assoc::forward_udp_payload;

pub(in crate::io_loop) async fn route_tun_packet(packet: &[u8], state: &mut LoopState) {
    if state.runtime.tun_egress_interceptor.handle_packet(packet) {
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
            if let Some(resolved_dst) = resolve_mapped_target(&state.stats, &mut state.dns_cache, dst) {
                forward_udp_payload(
                    state.runtime.proxy_sockaddr,
                    &state.runtime.auth,
                    src,
                    resolved_dst,
                    payload,
                    &mut state.udp_associations,
                    &mut state.udp_eviction_heap,
                    &mut state.next_udp_association_id,
                    state.runtime.udp_idle_timeout,
                    state.runtime.protect_path.as_deref(),
                    &state.cancel,
                    &state.udp_tx,
                    &state.stats,
                )
                .await;
            }
        }
    }
}

fn route_tcp_or_other_packet(packet: &[u8], state: &mut LoopState) {
    if state.runtime.filter_injected_resets && is_injected_rst(packet) {
        return;
    }

    ensure_pending_listen_for_syn(packet, &mut state.pending_listens, &mut state.socket_set);
    state.device.push_rx(packet.to_vec());
}
