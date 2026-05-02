use std::io;
use std::sync::atomic::Ordering;

use smoltcp::time::Instant;
use tracing::warn;
use tun_rs::AsyncDevice;

use crate::classify::classify_ip_packet;
use crate::IpClass;

use super::bridge::{flush_device_tx_queue, pump_active_sessions};
use super::dns_intercept::{dns_query_name, drain_dns_responses, resolve_mapped_target, route_dns_packet};
use super::packet::is_injected_rst;
use super::state::LoopState;
use super::tcp_accept::{ensure_pending_listen_for_syn, gc_stale_pending_listens, spawn_new_tcp_sessions};
use super::udp_assoc::forward_udp_payload;
use super::{PENDING_LISTEN_GC_INTERVAL, PENDING_LISTEN_TIMEOUT};

pub(in crate::io_loop) async fn drain_tun(tun: &AsyncDevice, state: &mut LoopState) {
    let mut tun_read_buf = std::mem::take(&mut state.tun_read_buf);

    loop {
        let n = match tun.try_recv(&mut tun_read_buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
            Err(e) => {
                warn!("TUN read error: {}", e);
                break;
            }
        };

        state.stats.tx_packets.fetch_add(1, Ordering::Relaxed);
        state.stats.tx_bytes.fetch_add(n as u64, Ordering::Relaxed);

        let packet = &tun_read_buf[..n];
        route_tun_packet(packet, state).await;
    }

    state.tun_read_buf = tun_read_buf;
}

pub(in crate::io_loop) fn drain_dns(state: &mut LoopState) {
    if let (Some(mapdns), Some(cache)) = (state.runtime.mapdns_runtime, state.dns_cache.as_mut()) {
        drain_dns_responses(
            &mut state.device,
            &state.stats,
            mapdns,
            cache,
            &mut state.dns_resp_rx,
            &mut state.dns_req_tx,
        );
    }
}

pub(in crate::io_loop) fn poll_smoltcp(state: &mut LoopState) {
    state.iface.poll(Instant::now(), &mut state.device, &mut state.socket_set);
}

pub(in crate::io_loop) fn gc_pending_listens(state: &mut LoopState) {
    state.loop_iteration = state.loop_iteration.wrapping_add(1);
    if state.loop_iteration.is_multiple_of(PENDING_LISTEN_GC_INTERVAL) {
        gc_stale_pending_listens(&mut state.pending_listens, &mut state.socket_set, PENDING_LISTEN_TIMEOUT);
    }
}

pub(in crate::io_loop) fn admit_tcp_sessions(state: &mut LoopState) {
    spawn_new_tcp_sessions(
        &mut state.socket_set,
        &mut state.sessions,
        &mut state.pending_listens,
        state.runtime.proxy_sockaddr,
        &state.runtime.auth,
        &state.cancel,
        &state.stats,
        &mut state.dns_cache,
    );
}

pub(in crate::io_loop) async fn pump_bridges(state: &mut LoopState) {
    pump_active_sessions(&mut state.socket_set, &mut state.sessions, &mut state.dns_cache).await;
}

pub(in crate::io_loop) async fn flush_tun(tun: &AsyncDevice, state: &mut LoopState) -> io::Result<()> {
    flush_device_tx_queue(tun, &state.stats, &mut state.device)
        .await
        .map_err(|e| io::Error::other(format!("flush TUN tx queue: {e}")))
}

async fn route_tun_packet(packet: &[u8], state: &mut LoopState) {
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
                    &state.cancel,
                    &state.udp_tx,
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
