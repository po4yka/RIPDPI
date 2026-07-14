use std::io;
use std::sync::atomic::Ordering;

use smoltcp::time::Instant;
use tracing::warn;
use tun_rs::AsyncDevice;

use super::bridge::{TunFlushOutcome, flush_device_tx_queue, pump_active_sessions};
use super::dns_intercept::drain_dns_responses;
use super::routing::route_tun_packet;
use super::state::LoopState;
use super::tcp_accept::{gc_stale_pending_listens, spawn_new_tcp_sessions};
use super::{IO_PHASE_WORK_BUDGET, LOSS_EMIT_INTERVAL, PENDING_LISTEN_GC_INTERVAL, PENDING_LISTEN_TIMEOUT};

/// Keep one busy TUN producer from monopolising the single-owner io loop. The
/// cap matches the existing maximum TUN write batch, keeping packet work
/// symmetric while guaranteeing every tick reaches timers and cancellation.
pub(in crate::io_loop) async fn drain_tun(tun: &AsyncDevice, state: &mut LoopState) {
    drain_tun_with(state, |buffer| tun.try_recv(buffer));
}

fn drain_tun_with(state: &mut LoopState, mut recv: impl FnMut(&mut [u8]) -> io::Result<usize>) -> usize {
    let mut tun_read_buf = std::mem::take(&mut state.tun_read_buf);
    let mut drained = 0;

    while drained < IO_PHASE_WORK_BUDGET {
        let n = match recv(&mut tun_read_buf) {
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
        drained += 1;

        let packet = &tun_read_buf[..n];
        // Feed the inbound (TUN -> userspace) packet to the optional
        // synchronous observer (e.g. PCAP capture-set) before routing. No
        // `.await`, no allocation when no observer is installed --
        // cancel-safety of `drain_tun` is preserved.
        state.stats.on_inbound_packet(packet);
        // Observe TCP retransmits for loss-percentage tracking.
        // O(1) amortised; no logging on the hot path.
        state.retransmit_tracker.observe(packet);
        route_tun_packet(packet, state);
    }

    state.tun_read_buf = tun_read_buf;
    drained
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

/// Emit the current retransmit-derived loss percentage via
/// `Stats::emit_loss_pct` every `LOSS_EMIT_INTERVAL` loop iterations.
/// Synchronous, O(1). No `.await`, no per-packet logging.
///
/// Cancel-safety: synchronous; cannot introduce cancel-safety issues.
pub(in crate::io_loop) fn emit_loss_sample(state: &mut LoopState) {
    if state.loop_iteration.wrapping_sub(state.last_loss_emit_iteration) < LOSS_EMIT_INTERVAL {
        return;
    }
    state.last_loss_emit_iteration = state.loop_iteration;
    let loss_pct = state.retransmit_tracker.current_loss_pct();
    state.stats.emit_loss_pct(loss_pct);
}

pub(in crate::io_loop) fn admit_tcp_sessions(state: &mut LoopState) {
    spawn_new_tcp_sessions(
        &mut state.socket_set,
        &mut state.sessions,
        &mut state.pending_listens,
        &mut state.tcp_admission_cursor,
        state.runtime.proxy_sockaddr,
        &state.runtime.auth,
        state.runtime.protect_path.as_deref(),
        state.runtime.tcp_connect_timeout,
        state.runtime.tcp_read_write_timeout,
        &state.cancel,
        &state.stats,
        &mut state.dns_cache,
        &state.runtime.uid_policy,
    );
}

pub(in crate::io_loop) async fn pump_bridges(state: &mut LoopState) {
    pump_active_sessions(&mut state.socket_set, &mut state.sessions, &mut state.dns_cache).await;
}

pub(in crate::io_loop) async fn flush_tun(tun: &AsyncDevice, state: &mut LoopState) -> io::Result<TunFlushOutcome> {
    flush_device_tx_queue(
        tun,
        &state.stats,
        &mut state.device,
        &mut state.runtime.tun_ingress_interceptor,
        &state.cancel,
    )
    .await
    .map_err(|e| io::Error::other(format!("flush TUN tx queue: {e}")))
}

#[cfg(test)]
mod tests {
    use std::collections::{HashMap, HashSet};
    use std::net::SocketAddr;
    use std::sync::atomic::Ordering;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use ripdpi_collections::bounded_heap::BoundedHeap;
    use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
    use smoltcp::time::Instant;
    use smoltcp::wire::HardwareAddress;
    use tokio::net::TcpListener;
    use tokio::sync::mpsc;
    use tokio_util::sync::CancellationToken;

    use crate::session::Auth;
    use crate::{ActiveSessions, Stats, TunDevice};
    use ripdpi_tunnel_intercept::egress::TunEgressPacketHandler;
    use ripdpi_tunnel_intercept::ingress::{RawSynAckPacketInjector, TunIngressInterceptor};

    use super::super::retransmit::RetransmitTracker;
    use super::super::state::{LoopRuntime, LoopState};
    use super::super::udp_assoc::{DEFAULT_MAX_UDP_ASSOCIATIONS, UdpEvictionEntry};
    use super::*;

    #[tokio::test]
    async fn consumed_egress_udp_packet_bypasses_normal_udp_routing() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen_packets))));
        let packet = ipv4_udp_packet(55000, 443, b"quic");

        route_tun_packet(&packet, &mut state);

        assert_eq!(seen_packets.lock().expect("seen packets")[0], packet);
        assert_eq!(state.stats.dht_trigger_observations.load(Ordering::Relaxed), 0);
        assert!(state.udp_associations.is_empty());
    }

    #[tokio::test]
    async fn non_consuming_egress_tcp_packet_continues_to_smoltcp() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        let packet = ipv4_tcp_packet(55000, 443);

        route_tun_packet(&packet, &mut state);

        assert_eq!(seen_packets.lock().expect("seen packets")[0], packet);
        assert_eq!(state.device.rx_queue.front().expect("smoltcp packet"), &packet);
    }

    #[test]
    fn tun_drain_stops_at_per_tick_packet_budget() {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        let packet = ipv4_tcp_packet(55000, 443);
        let mut reads = 0;

        let drained = drain_tun_with(&mut state, |buffer| {
            reads += 1;
            buffer[..packet.len()].copy_from_slice(&packet);
            Ok(packet.len())
        });

        assert_eq!(drained, IO_PHASE_WORK_BUDGET);
        assert_eq!(reads, IO_PHASE_WORK_BUDGET);
        assert_eq!(state.stats.tx_packets.load(Ordering::Relaxed), IO_PHASE_WORK_BUDGET as u64);
    }

    #[tokio::test]
    async fn udp_association_setup_does_not_block_packet_routing() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind stalled proxy");
        let proxy_addr = listener.local_addr().expect("proxy address");
        let stalled_proxy = tokio::spawn(async move {
            let (_stream, _) = listener.accept().await.expect("accept association setup");
            std::future::pending::<()>().await;
        });
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.runtime.proxy_sockaddr = proxy_addr;
        let packet = ipv4_udp_packet(55000, 443, b"quic");

        route_tun_packet(&packet, &mut state);
        let tcp_packet = ipv4_tcp_packet(55001, 443);
        route_tun_packet(&tcp_packet, &mut state);

        assert_eq!(state.device.rx_queue.front().expect("smoltcp packet"), &tcp_packet);
        stalled_proxy.abort();
    }

    #[tokio::test]
    async fn udp_admission_waits_for_cached_uid_and_drops_denied_flow() {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let packet = ipv4_udp_packet(55_123, 443, b"uid-gated");
        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_UDP,
            local: "10.0.0.2:55123".parse().expect("local endpoint"),
            remote: "93.184.216.34:443".parse().expect("remote endpoint"),
        };

        route_tun_packet(&packet, &mut state);
        assert!(state.udp_associations.is_empty(), "pending UID must not create an association");

        let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("denied flow job");
        ripdpi_flow_app_attribution::store_uid_resolution(job, Some(20_000));
        route_tun_packet(&packet, &mut state);
        assert!(state.udp_associations.is_empty(), "denied UID must remain dropped");

        let allowed_packet = ipv4_udp_packet(55_124, 443, b"uid-allowed");
        let allowed_request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_UDP,
            local: "10.0.0.2:55124".parse().expect("local endpoint"),
            remote: "93.184.216.34:443".parse().expect("remote endpoint"),
        };
        route_tun_packet(&allowed_packet, &mut state);
        let job = ripdpi_flow_app_attribution::take_pending_request(allowed_request).expect("allowed flow job");
        ripdpi_flow_app_attribution::store_uid_resolution(job, Some(10_123));
        route_tun_packet(&allowed_packet, &mut state);
        assert_eq!(state.udp_associations.len(), 1, "allowed UID may create the association");
        state.shutdown().await;
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
                uid_policy: crate::uid_policy::UidFlowPolicy::disarmed(),
                tun_ingress_interceptor: TunIngressInterceptor::new(None, RawSynAckPacketInjector::new(None)),
                tun_egress_interceptor,
                udp_idle_timeout: Duration::from_secs(1),
                tcp_connect_timeout: Duration::from_secs(10),
                tcp_read_write_timeout: Duration::from_secs(300),
                protect_path: None,
            },
            pending_listens: HashMap::new(),
            tcp_admission_cursor: 0,
            loop_iteration: 0,
            udp_tx,
            udp_rx,
            udp_associations: HashMap::new(),
            udp_eviction_heap: BoundedHeap::<UdpEvictionEntry>::new(DEFAULT_MAX_UDP_ASSOCIATIONS),
            udp_memory_budget: crate::session::udp::UdpMemoryBudget::for_tunnel_mtu(1500),
            next_udp_association_id: 1,
            dns_req_tx: None,
            dns_resp_rx: None,
            tun_read_buf: vec![0u8; 1500],
            retransmit_tracker: RetransmitTracker::new(),
            last_loss_emit_iteration: 0,
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
