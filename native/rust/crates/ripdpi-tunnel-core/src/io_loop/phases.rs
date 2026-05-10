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
    flush_device_tx_queue(tun, &state.stats, &mut state.device, &mut state.runtime.tun_ingress_interceptor)
        .await
        .map_err(|e| io::Error::other(format!("flush TUN tx queue: {e}")))
}

async fn route_tun_packet(packet: &[u8], state: &mut LoopState) {
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

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::sync::atomic::Ordering;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use ripdpi_collections::bounded_heap::BoundedHeap;
    use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
    use smoltcp::time::Instant;
    use smoltcp::wire::HardwareAddress;
    use tokio::sync::mpsc;
    use tokio_util::sync::CancellationToken;

    use crate::session::Auth;
    use crate::{ActiveSessions, Stats, TunDevice};

    use super::super::state::{LoopRuntime, LoopState};
    use super::super::tun_egress_interceptor::TunEgressPacketHandler;
    use super::super::tun_ingress_interceptor::{RawSynAckPacketInjector, TunIngressInterceptor};
    use super::super::udp_assoc::{UdpEvictionEntry, DEFAULT_MAX_UDP_ASSOCIATIONS};
    use super::*;

    #[tokio::test]
    async fn consumed_egress_udp_packet_bypasses_normal_udp_routing() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen_packets))));
        let packet = ipv4_udp_packet(55000, 443, b"quic");

        route_tun_packet(&packet, &mut state).await;

        assert_eq!(seen_packets.lock().expect("seen packets")[0], packet);
        assert_eq!(state.stats.dht_trigger_observations.load(Ordering::Relaxed), 0);
        assert!(state.udp_associations.is_empty());
    }

    #[tokio::test]
    async fn non_consuming_egress_tcp_packet_continues_to_smoltcp() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        let packet = ipv4_tcp_packet(55000, 443);

        route_tun_packet(&packet, &mut state).await;

        assert_eq!(seen_packets.lock().expect("seen packets")[0], packet);
        assert_eq!(state.device.rx_queue.front().expect("smoltcp packet"), &packet);
    }

    struct RecordingEgressHandler {
        consume: bool,
        seen_packets: Arc<Mutex<Vec<Vec<u8>>>>,
    }

    impl RecordingEgressHandler {
        fn new(consume: bool, seen_packets: Arc<Mutex<Vec<Vec<u8>>>>) -> Self {
            Self { consume, seen_packets }
        }
    }

    impl TunEgressPacketHandler for RecordingEgressHandler {
        fn handle_packet(&mut self, packet: &[u8]) -> bool {
            self.seen_packets.lock().expect("seen packets").push(packet.to_vec());
            self.consume
        }
    }

    fn test_loop_state(tun_egress_interceptor: Box<dyn TunEgressPacketHandler>) -> LoopState {
        let mut device = TunDevice::new(1500);
        let iface_cfg = IfaceConfig::new(HardwareAddress::Ip);
        let iface = Interface::new(iface_cfg, &mut device, Instant::now());
        let (udp_tx, udp_rx) = mpsc::channel(1);

        LoopState {
            device,
            iface,
            socket_set: SocketSet::new(vec![]),
            sessions: ActiveSessions::new(0),
            cancel: CancellationToken::new(),
            stats: Arc::new(Stats::new()),
            dns_cache: None,
            runtime: LoopRuntime {
                proxy_sockaddr: "127.0.0.1:1080".parse::<SocketAddr>().expect("proxy address"),
                auth: Auth::NoAuth,
                mapdns_runtime: None,
                mapdns_classify: None,
                filter_injected_resets: false,
                tun_ingress_interceptor: TunIngressInterceptor::new(None, RawSynAckPacketInjector::new(None)),
                tun_egress_interceptor,
                udp_idle_timeout: Duration::from_secs(1),
            },
            pending_listens: HashMap::new(),
            loop_iteration: 0,
            udp_tx,
            udp_rx,
            udp_associations: HashMap::new(),
            udp_eviction_heap: BoundedHeap::<UdpEvictionEntry>::new(DEFAULT_MAX_UDP_ASSOCIATIONS),
            next_udp_association_id: 1,
            dns_req_tx: None,
            dns_resp_rx: None,
            tun_read_buf: vec![0u8; 1500],
        }
    }

    fn ipv4_udp_packet(src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let total_len = 20 + udp_len;
        let mut packet = vec![0u8; total_len];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        packet[8] = 64;
        packet[9] = 17;
        packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
        packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
        packet[20..22].copy_from_slice(&src_port.to_be_bytes());
        packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
        packet[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
        packet[28..].copy_from_slice(payload);
        packet
    }

    fn ipv4_tcp_packet(src_port: u16, dst_port: u16) -> Vec<u8> {
        let mut packet = vec![0u8; 40];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&40u16.to_be_bytes());
        packet[8] = 64;
        packet[9] = 6;
        packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
        packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
        packet[20..22].copy_from_slice(&src_port.to_be_bytes());
        packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
        packet[32] = 0x50;
        packet[33] = 0x18;
        packet[34..36].copy_from_slice(&65535u16.to_be_bytes());
        packet
    }
}
