use std::io;

use smoltcp::time::Instant;
use tracing::warn;
use tun_rs::AsyncDevice;

use super::bridge::{TunFlushOutcome, flush_device_tx_queue, pump_active_sessions};
use super::dns_intercept::drain_dns_responses;
use super::routing::{retry_pending_uid_udp, route_tun_packet};
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
                state.stats.record_tun_read_error();
                break;
            }
        };

        state.stats.record_tun_read_success(n);
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
            &mut state.active_direct_dns_generation,
        );
    }
}

pub(in crate::io_loop) fn retry_pending_udp_admission(state: &mut LoopState) {
    retry_pending_uid_udp(state);
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
    let resetting = spawn_new_tcp_sessions(
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
        Some(&mut state.active_direct_dns_generation),
        &state.runtime.uid_policy,
        state.runtime.mapdns_runtime,
        state.dns_req_tx.clone(),
        state.runtime.split_dns_policy.clone(),
    );
    if resetting.is_empty() {
        return;
    }

    // `TcpSocket::abort()` schedules a RST; smoltcp must poll the socket once
    // before it is removed or the reset never reaches the kernel-side peer.
    state.iface.poll(Instant::now(), &mut state.device, &mut state.socket_set);
    for handle in resetting {
        state.socket_set.remove(handle);
    }
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
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
    use std::os::fd::RawFd;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex, PoisonError};
    use std::time::Duration;

    use hickory_proto::op::{Message, OpCode, Query, ResponseCode};
    use hickory_proto::rr::rdata::A;
    use hickory_proto::rr::{Name, RData, Record, RecordType};
    use ripdpi_collections::bounded_heap::BoundedHeap;
    use ripdpi_tunnel_config::{
        SplitDnsAction, SplitDnsDomainMatcher, SplitDnsMatcherKind, SplitDnsNetwork, SplitDnsPolicyConfig, SplitDnsRule,
    };
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

    use super::super::packet::{build_ipv4_tcp_ack_packet, build_ipv4_tcp_syn_packet, tcp_seq_ack};
    use super::super::retransmit::RetransmitTracker;
    use super::super::state::{
        LoopRuntime, LoopState, PENDING_UID_UDP_CAPACITY, PENDING_UID_UDP_POOL_CAPACITY, PendingUidUdpPackets,
    };
    use super::super::udp_assoc::{UDP_EVICTION_HEAP_CAPACITY, UdpEvictionEntry};
    use super::super::wait::{WaitEvent, WaitOutcome, handle_wait_event};
    use super::*;

    struct GenerationBinder(u64);

    impl crate::tunnel_api::direct_dns_binding::DirectDnsSocketBinder for GenerationBinder {
        fn current_generation(&self) -> Option<u64> {
            Some(self.0)
        }

        fn is_current(&self, generation: u64) -> bool {
            generation == self.0
        }

        fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
            if self.is_current(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
        }
    }

    #[tokio::test]
    async fn consumed_egress_udp_packet_bypasses_normal_udp_routing() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(true, Arc::clone(&seen_packets))));
        let packet = ipv4_udp_packet(55000, 443, b"quic");

        route_tun_packet(&packet, &mut state);

        assert_eq!(seen_packets.lock().expect("seen packets")[0], packet);
        assert_eq!(state.stats.dht_trigger_observations.load(Ordering::Relaxed), 0);
        assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_interceptor_drops, 1);
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
    fn malformed_tun_packet_records_parse_failure_evidence() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        let packet = vec![0_u8; 5];

        route_tun_packet(&packet, &mut state);

        assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_parse_failures, 1);
        assert_eq!(seen_packets.lock().expect("seen packets")[0], packet);
        assert_eq!(state.device.rx_queue.front().expect("legacy smoltcp path"), &packet);
    }

    #[test]
    fn armed_uid_policy_blocks_icmp_before_egress_interceptor_and_smoltcp() {
        for packet in [
            ipv4_icmp_packet(),
            ipv6_icmp_packet(),
            ipv4_icmp_fragment(true),
            ipv4_icmp_fragment(false),
            ipv6_icmp_fragment(true),
            ipv6_icmp_fragment(false),
        ] {
            let seen_packets = Arc::new(Mutex::new(Vec::new()));
            let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
            state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));

            route_tun_packet(&packet, &mut state);

            assert_eq!(state.stats.icmp_ingress_packets(), 1);
            assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_policy_drops, 1);
            assert!(seen_packets.lock().expect("seen packets").is_empty());
            assert!(state.device.rx_queue.is_empty());
        }
    }

    #[test]
    fn armed_uid_policy_opt_in_preserves_icmp_path() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        state.runtime.uid_policy_allow_icmp = true;
        let packet = ipv4_icmp_packet();

        route_tun_packet(&packet, &mut state);

        assert_eq!(seen_packets.lock().expect("seen packets").as_slice(), [packet.as_slice()]);
        assert_eq!(state.device.rx_queue.front().expect("smoltcp packet"), &packet);
    }

    #[test]
    fn disarmed_uid_policy_preserves_icmp_path_regardless_of_flag() {
        for allow_icmp in [false, true] {
            let seen_packets = Arc::new(Mutex::new(Vec::new()));
            let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
            state.runtime.uid_policy_allow_icmp = allow_icmp;
            let packet = ipv6_icmp_packet();

            route_tun_packet(&packet, &mut state);

            assert_eq!(seen_packets.lock().expect("seen packets").as_slice(), [packet.as_slice()]);
            assert_eq!(state.device.rx_queue.front().expect("smoltcp packet"), &packet);
        }
    }

    #[test]
    fn background_tcp_udp_cannot_satisfy_icmp_ingress_counter() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let tcp_packet = ipv4_tcp_packet(55_000, 443);
        let udp_packet = ipv4_udp_packet(55_001, 443, b"uid-gated");

        route_tun_packet(&tcp_packet, &mut state);
        route_tun_packet(&udp_packet, &mut state);

        assert_eq!(state.stats.icmp_ingress_packets(), 0);
        assert_eq!(seen_packets.lock().expect("seen packets").len(), 2);
        assert_eq!(state.device.rx_queue.front().expect("smoltcp packet"), &tcp_packet);
        assert_eq!(state.pending_uid_udp_packets.len(), 1);
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
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let packet = ipv4_udp_packet(55_123, 443, b"uid-gated");
        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_UDP,
            local: "10.0.0.2:55123".parse().expect("local endpoint"),
            remote: "93.184.216.34:443".parse().expect("remote endpoint"),
        };

        route_tun_packet(&packet, &mut state);
        assert!(state.udp_associations.is_empty(), "pending UID must not create an association");
        assert_eq!(state.pending_uid_udp_packets.len(), 1, "pending UDP datagram must be retained");

        let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("denied flow job");
        ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(20_000));
        retry_pending_uid_udp(&mut state);
        assert!(state.udp_associations.is_empty(), "denied UID must remain dropped");
        assert!(state.pending_uid_udp_packets.is_empty(), "denied UDP datagram must be released");
        assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_policy_drops, 1);
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(request.protocol, request.local, request.remote,),
            ripdpi_flow_app_attribution::FlowUidLookup::Missing,
            "denied UDP admission must evict its exact-tuple UID cache token",
        );

        let allowed_packet = ipv4_udp_packet(55_124, 443, b"uid-allowed");
        let allowed_request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_UDP,
            local: "10.0.0.2:55124".parse().expect("local endpoint"),
            remote: "93.184.216.34:443".parse().expect("remote endpoint"),
        };
        route_tun_packet(&allowed_packet, &mut state);
        assert_eq!(state.pending_uid_udp_packets.len(), 1, "allowed flow waits for UID resolution");
        let job = ripdpi_flow_app_attribution::take_pending_request(allowed_request).expect("allowed flow job");
        ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
        retry_pending_uid_udp(&mut state);
        assert_eq!(state.udp_associations.len(), 1, "allowed UID may create the association");
        assert!(state.pending_uid_udp_packets.is_empty(), "admitted UDP datagram must leave pending queue");
        assert_eq!(
            seen_packets.lock().expect("seen packets").len(),
            2,
            "UID retries must not replay egress interceptor side effects"
        );
        state.shutdown().await;
    }

    #[test]
    fn direct_mapdns_admission_routes_only_after_uid_allow() {
        struct CountingBinder {
            binds: AtomicUsize,
        }
        impl crate::tunnel_api::direct_dns_binding::DirectDnsSocketBinder for CountingBinder {
            fn current_generation(&self) -> Option<u64> {
                Some(41)
            }
            fn is_current(&self, generation: u64) -> bool {
                generation == 41
            }
            fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
                self.binds.fetch_add(1, Ordering::Relaxed);
                if self.is_current(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
            }
        }
        let _binder_guard =
            crate::tunnel_api::direct_dns_binding::TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let binder = Arc::new(CountingBinder { binds: AtomicUsize::new(0) });
        let _registration =
            crate::tunnel_api::direct_dns_binding::register_test_direct_dns_socket_binder(binder.clone());
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("test runtime");
        runtime.block_on(async {
            let seen_packets = Arc::new(Mutex::new(Vec::new()));
            let mut state = mapdns_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
            state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
            state.runtime.split_dns_policy = Some(
                crate::split_dns::SplitDnsPolicy::compile(&SplitDnsPolicyConfig {
                    canonical_digest: "a".repeat(64),
                    destination_routing_digest: "b".repeat(64),
                    default_action: SplitDnsAction::Tunneled,
                    rules: vec![SplitDnsRule {
                        action: SplitDnsAction::Direct,
                        network: SplitDnsNetwork::Both,
                        domains: vec![SplitDnsDomainMatcher {
                            kind: SplitDnsMatcherKind::Exact,
                            value: "example.test".to_string(),
                        }],
                        has_ip_ranges: false,
                        has_ports: false,
                    }],
                    direct_resolver_candidates: vec!["192.0.2.53".parse().expect("resolver")],
                    bootstrap_pins: Vec::new(),
                    geosite_db_path: None,
                    coverage_reason: None,
                })
                .expect("split DNS policy"),
            );
            let (tx, mut rx) = mpsc::channel(8);
            state.dns_req_tx = Some(tx);
            let packet = mapdns_dns_packet(53_101);
            let request = ripdpi_flow_app_attribution::FlowResolveRequest {
                protocol: crate::uid_policy::PROTO_UDP,
                local: "10.0.0.2:53101".parse().expect("local endpoint"),
                remote: "198.18.0.10:53".parse().expect("remote endpoint"),
            };

            route_tun_packet(&packet, &mut state);

            assert_eq!(state.pending_uid_udp_packets.len(), 1);
            assert!(rx.try_recv().is_err(), "pending admission must not reach the DNS worker");
            assert!(state.device.tx_queue.is_empty(), "pending admission must not synthesize a response");
            let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("exact DNS tuple request");
            assert_eq!(job.kind(), ripdpi_flow_app_attribution::FlowResolutionKind::AdmissionOnly);
            ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(20_000));

            retry_pending_uid_udp(&mut state);

            assert!(state.pending_uid_udp_packets.is_empty());
            assert!(rx.try_recv().is_err(), "denied DNS must not reach the worker");
            assert!(state.device.tx_queue.is_empty(), "denied DNS must be silently dropped");
            assert_eq!(seen_packets.lock().expect("seen packets").len(), 1, "retry must not replay interception");

            let unresolved_packet = mapdns_dns_packet(53_104);
            let unresolved_request = ripdpi_flow_app_attribution::FlowResolveRequest {
                protocol: crate::uid_policy::PROTO_UDP,
                local: "10.0.0.2:53104".parse().expect("local endpoint"),
                remote: request.remote,
            };
            route_tun_packet(&unresolved_packet, &mut state);
            let unresolved_job = ripdpi_flow_app_attribution::take_pending_request(unresolved_request)
                .expect("unresolved DNS tuple request");
            ripdpi_flow_app_attribution::store_uid_resolution(&unresolved_job, None);
            retry_pending_uid_udp(&mut state);
            assert!(rx.try_recv().is_err(), "unresolved DNS must not reach the worker");
            assert!(state.device.tx_queue.is_empty(), "unresolved DNS must be silently dropped");

            let allowed_packet = mapdns_dns_packet(53_105);
            let allowed_request = ripdpi_flow_app_attribution::FlowResolveRequest {
                protocol: crate::uid_policy::PROTO_UDP,
                local: "10.0.0.2:53105".parse().expect("local endpoint"),
                remote: request.remote,
            };
            route_tun_packet(&allowed_packet, &mut state);
            assert!(rx.try_recv().is_err(), "pending allowed query must not route early");
            assert_eq!(binder.binds.load(Ordering::Relaxed), 0, "UID admission precedes direct socket binding");
            let allowed_job =
                ripdpi_flow_app_attribution::take_pending_request(allowed_request).expect("allowed DNS tuple request");
            ripdpi_flow_app_attribution::store_uid_resolution(&allowed_job, Some(10_123));
            retry_pending_uid_udp(&mut state);
            let routed = rx.try_recv().expect("allowed direct DNS reaches worker exactly once");
            assert_eq!(routed.direct.as_ref().map(|direct| direct.generation), Some(41));
            assert!(rx.try_recv().is_err(), "one admitted packet produces one routed request");
            assert_eq!(binder.binds.load(Ordering::Relaxed), 0, "worker owns the later bind operation");
            state.shutdown().await;
        });
    }

    /// # Cancel safety
    /// Cancel-safe: the sole suspension point is `state.shutdown().await`; before it,
    /// the state has no TCP sessions or UDP associations, so cancellation cannot strand
    /// a partially shut-down session or association.
    #[tokio::test]
    async fn mapdns_dns_admission_allows_retry_and_reuses_uid_resolution() {
        let seen_packets = Arc::new(Mutex::new(Vec::new()));
        let mut state = mapdns_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::clone(&seen_packets))));
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let (tx, mut rx) = mpsc::channel(8);
        state.dns_req_tx = Some(tx);
        let packet = mapdns_dns_packet(53_102);
        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_UDP,
            local: "10.0.0.2:53102".parse().expect("local endpoint"),
            remote: "198.18.0.10:53".parse().expect("remote endpoint"),
        };

        route_tun_packet(&packet, &mut state);
        let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("exact DNS tuple request");
        ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
        retry_pending_uid_udp(&mut state);
        let first = rx.try_recv().expect("allowed DNS reaches worker");
        assert_eq!(first.src, request.local);
        assert_eq!(first.host.as_deref(), Some("example.test"));

        route_tun_packet(&packet, &mut state);
        assert!(rx.try_recv().is_ok(), "cached UID allows a repeated query immediately");
        assert!(ripdpi_flow_app_attribution::take_pending_request(request).is_none());
        assert_eq!(seen_packets.lock().expect("seen packets").len(), 2, "retry must not replay interception");
        state.shutdown().await;
    }

    /// # Cancel safety
    /// Cancel-safe: the sole suspension point is `state.shutdown().await`; before it,
    /// the state has no TCP sessions or UDP associations, so cancellation cannot strand
    /// a partially shut-down session or association.
    #[tokio::test]
    async fn disarmed_mapdns_dns_is_immediate_and_creates_no_uid_attribution() {
        let mut state =
            mapdns_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        let (tx, mut rx) = mpsc::channel(8);
        state.dns_req_tx = Some(tx);
        let packet = mapdns_dns_packet(53_103);
        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_UDP,
            local: "10.0.0.2:53103".parse().expect("local endpoint"),
            remote: "198.18.0.10:53".parse().expect("remote endpoint"),
        };

        route_tun_packet(&packet, &mut state);

        assert!(rx.try_recv().is_ok());
        assert!(state.pending_uid_udp_packets.is_empty());
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(request.protocol, request.local, request.remote),
            ripdpi_flow_app_attribution::FlowUidLookup::Missing
        );
        assert!(ripdpi_flow_app_attribution::lookup_flow(request.remote.ip()).is_none());
        state.shutdown().await;
    }

    #[tokio::test]
    async fn mapdns_udp_admission_uses_kernel_visible_synthetic_tuple() {
        let mut state = {
            let _binder_guard =
                crate::tunnel_api::direct_dns_binding::TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
            let mut state =
                test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
            state.active_direct_dns_generation = crate::tunnel_api::direct_dns_binding::current_direct_dns_generation();
            state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
            let real_ip = Ipv4Addr::new(93, 184, 216, 34);
            let mut cache = crate::dns_cache::DnsCache::new(0xC612_0000, 0xFFFE_0000, 8).expect("valid cache");
            let synthetic_ip = cache.find("udp-mapdns.test", u32::from(real_ip)).expect("synthetic mapping").0;
            state.dns_cache = Some(cache);

            let mut packet = ipv4_udp_packet(55_125, 3478, b"stun");
            packet[16..20].copy_from_slice(&synthetic_ip.to_be_bytes());
            let local = "10.0.0.2:55125".parse().expect("local endpoint");
            let synthetic_remote = SocketAddr::new(Ipv4Addr::from(synthetic_ip).into(), 3478);

            route_tun_packet(&packet, &mut state);
            assert_eq!(state.pending_uid_udp_packets.len(), 1, "MapDNS UDP waits for UID resolution");
            let request = ripdpi_flow_app_attribution::FlowResolveRequest {
                protocol: crate::uid_policy::PROTO_UDP,
                local,
                remote: synthetic_remote,
            };
            let job = ripdpi_flow_app_attribution::take_pending_request(request)
                .expect("UID lookup must use the kernel-visible synthetic tuple");
            ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));

            retry_pending_uid_udp(&mut state);

            assert_eq!(state.udp_associations.len(), 1, "resolved MapDNS UDP may create the SOCKS association");
            assert!(state.pending_uid_udp_packets.is_empty(), "admitted datagram must leave the pending queue");
            state
        };
        state.shutdown().await;
    }

    #[test]
    fn ordinary_udp_rejects_stale_synthetic_mapping_after_generation_change_without_dns() {
        use crate::tunnel_api::direct_dns_binding::{
            TEST_BINDER_LOCK, register_direct_dns_socket_binder, unregister_direct_dns_socket_binder_if,
        };

        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let registration_a = register_direct_dns_socket_binder(Arc::new(GenerationBinder(31))).expect("A registration");
        let mut state =
            mapdns_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.active_direct_dns_generation = Some(31);
        let synthetic_ip =
            state.dns_cache.as_mut().expect("DNS cache").find("stale-udp.test", 0x5db8_d822).expect("mapping").0;
        let registration_b = register_direct_dns_socket_binder(Arc::new(GenerationBinder(32))).expect("B registration");
        let mut packet = ipv4_udp_packet(55_130, 443, b"stale");
        packet[16..20].copy_from_slice(&synthetic_ip.to_be_bytes());

        route_tun_packet(&packet, &mut state);

        assert!(state.udp_associations.is_empty(), "stale synthetic UDP must not create an association");
        assert!(state.dns_cache.as_mut().expect("DNS cache").lookup(synthetic_ip).is_none());
        assert_eq!(state.active_direct_dns_generation, Some(32));
        assert!(!unregister_direct_dns_socket_binder_if(registration_a));
        assert!(unregister_direct_dns_socket_binder_if(registration_b));
    }

    #[test]
    fn tcp_admission_rejects_stale_synthetic_mapping_after_generation_change_without_dns() {
        use crate::tunnel_api::direct_dns_binding::{
            TEST_BINDER_LOCK, register_direct_dns_socket_binder, unregister_direct_dns_socket_binder_if,
        };

        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let registration_a = register_direct_dns_socket_binder(Arc::new(GenerationBinder(41))).expect("A registration");
        let mut state =
            mapdns_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.active_direct_dns_generation = Some(41);
        let synthetic_ip =
            state.dns_cache.as_mut().expect("DNS cache").find("stale-tcp.test", 0x5db8_d822).expect("mapping").0;
        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let target_ip = Ipv4Addr::from(synthetic_ip);
        let client_port = 51_100;
        let target_port = 443;
        state.iface.update_ip_addrs(|addrs| {
            addrs
                .push(smoltcp::wire::IpCidr::new(smoltcp::wire::IpAddress::v4(10, 0, 0, 2), 24))
                .expect("test address capacity");
        });
        state
            .iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2))
            .expect("default IPv4 route");
        state.iface.set_any_ip(true);

        let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, client_port, target_port);
        super::super::tcp_accept::ensure_pending_listen_for_syn(
            &syn,
            &mut state.pending_listens,
            &mut state.socket_set,
        );
        state.device.rx_queue.push_back(syn);
        state.iface.poll(Instant::now(), &mut state.device, &mut state.socket_set);
        let syn_ack = state.device.tx_queue.pop_front().expect("SYN-ACK");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        state.device.rx_queue.push_back(build_ipv4_tcp_ack_packet(
            client_ip,
            target_ip,
            client_port,
            target_port,
            1,
            server_seq + 1,
        ));
        state.iface.poll(Instant::now(), &mut state.device, &mut state.socket_set);
        let registration_b = register_direct_dns_socket_binder(Arc::new(GenerationBinder(42))).expect("B registration");

        admit_tcp_sessions(&mut state);

        assert!(state.sessions.is_empty(), "stale synthetic TCP must not create an upstream session");
        assert!(state.pending_listens.is_empty(), "unresolvable stale flow must leave pending admission");
        assert!(state.dns_cache.as_mut().expect("DNS cache").lookup(synthetic_ip).is_none());
        assert_eq!(state.active_direct_dns_generation, Some(42));
        assert!(!unregister_direct_dns_socket_binder_if(registration_a));
        assert!(unregister_direct_dns_socket_binder_if(registration_b));
    }

    #[tokio::test]
    async fn webrtc_protection_drops_stun_before_udp_admission() {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.runtime.webrtc_protection_enabled = true;
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let mut stun_request = [0_u8; 20];
        stun_request[0..2].copy_from_slice(&1_u16.to_be_bytes());
        stun_request[4..8].copy_from_slice(&0x2112_A442_u32.to_be_bytes());

        route_tun_packet(&ipv4_udp_packet(55_126, 3478, &stun_request), &mut state);

        assert!(state.pending_uid_udp_packets.is_empty(), "blocked STUN must not enter UID admission");
        assert!(state.udp_associations.is_empty(), "blocked STUN must not create a SOCKS association");
        assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_policy_drops, 1);
        state.shutdown().await;
    }

    #[tokio::test]
    async fn webrtc_protection_preserves_non_stun_udp() {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.runtime.webrtc_protection_enabled = true;
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));

        route_tun_packet(&ipv4_udp_packet(55_127, 443, b"not-stun"), &mut state);

        assert_eq!(state.pending_uid_udp_packets.len(), 1, "non-STUN UDP must continue to UID admission");
        state.shutdown().await;
    }

    #[test]
    fn dns_wait_event_suppresses_stale_a_response_after_b_replacement_and_loss() {
        use crate::io_loop::dns_intercept::DnsResponse;
        use crate::tunnel_api::direct_dns_binding::{
            TEST_BINDER_LOCK, register_direct_dns_socket_binder, unregister_direct_dns_socket_binder_if,
        };

        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let registration_a = register_direct_dns_socket_binder(Arc::new(GenerationBinder(11))).expect("A registration");
        let registration_b = register_direct_dns_socket_binder(Arc::new(GenerationBinder(12))).expect("B registration");
        let mut state =
            mapdns_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.active_direct_dns_generation = Some(12);
        let query = mapdns_dns_query();

        let queue_stale_a = |state: &mut LoopState, host: &str| {
            state
                .dns_cache
                .as_mut()
                .expect("DNS cache")
                .rewrite_response(&query, &mapdns_dns_response(Ipv4Addr::new(203, 0, 113, 7)))
                .expect("prepopulate unleased mapping");
            assert!(matches!(
                handle_wait_event(
                    state,
                    WaitEvent::Dns(Some(DnsResponse {
                        src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53_000),
                        query: query.clone(),
                        host: Some(host.to_string()),
                        upstream: Err("late direct response".to_string()),
                        resolver_error_kind: None,
                        resolver_endpoint_label: None,
                        direct_generation: Some(11),
                        direct_fallback: false,
                        tcp_reply: None,
                    })),
                ),
                WaitOutcome::Continue
            ));
            assert!(
                state.dns_cache.as_mut().expect("DNS cache").lookup(u32::from(Ipv4Addr::new(198, 18, 0, 0))).is_none(),
                "stale wait-path response must clear unleased MapDNS entries",
            );
        };

        queue_stale_a(&mut state, "replaced.test");
        assert_eq!(state.stats.direct_dns_outcomes(), (0, 1));
        assert_eq!(state.device.tx_queue.len(), 1, "stale response after A -> B must become SERVFAIL");
        assert_eq!(state.active_direct_dns_generation, Some(12));

        assert!(unregister_direct_dns_socket_binder_if(registration_b));
        queue_stale_a(&mut state, "lost.test");
        assert_eq!(state.stats.direct_dns_outcomes(), (0, 2));
        assert_eq!(state.device.tx_queue.len(), 2, "stale response after underlay loss must become SERVFAIL");
        assert_eq!(
            state.active_direct_dns_generation, None,
            "binding loss must invalidate B without allowing stale A to become active"
        );
        assert!(!unregister_direct_dns_socket_binder_if(registration_a), "stale unregister cannot clear state");
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
        let stats = Arc::new(Stats::new());
        device.set_tun_queue_drop_stats(Arc::clone(&stats));

        LoopState {
            device,
            iface,
            socket_set: SocketSet::new(vec![]),
            sessions: ActiveSessions::new(0),
            cancel: CancellationToken::new(),
            stats,
            dns_cache: None,
            runtime: LoopRuntime {
                proxy_sockaddr: "127.0.0.1:1080".parse::<SocketAddr>().expect("proxy address"),
                auth: Auth::NoAuth,
                mapdns_runtime: None,
                mapdns_classify: None,
                split_dns_policy: None,
                filter_injected_resets: false,
                webrtc_protection_enabled: false,
                uid_policy: crate::uid_policy::UidFlowPolicy::disarmed(),
                uid_policy_allow_icmp: false,
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
            udp_eviction_heap: BoundedHeap::<UdpEvictionEntry>::new(UDP_EVICTION_HEAP_CAPACITY),
            udp_memory_budget: crate::session::udp::UdpMemoryBudget::for_tunnel_mtu(1500),
            next_udp_association_id: 1,
            pending_uid_udp_packets: PendingUidUdpPackets::new(1564),
            dns_req_tx: None,
            dns_resp_rx: None,
            active_direct_dns_generation: None,
            tun_read_buf: vec![0u8; 1500],
            retransmit_tracker: RetransmitTracker::new(),
            last_loss_emit_iteration: 0,
        }
    }

    #[test]
    fn pending_uid_udp_storage_is_bounded_and_reuses_preallocated_buffers() {
        let mut packets = PendingUidUdpPackets::new(64);
        assert_eq!(packets.free_len(), PENDING_UID_UDP_POOL_CAPACITY);

        for byte in 0..=PENDING_UID_UDP_CAPACITY {
            assert!(packets.retain(&[byte as u8; 32]).is_stored());
        }
        assert_eq!(packets.len(), PENDING_UID_UDP_CAPACITY);
        assert_eq!(packets.free_len(), 1);

        let packet = packets.pop_front().expect("queued packet");
        let allocation = packet.as_ptr();
        packets.recycle(packet);
        assert_eq!(packets.free_len(), 2);
        assert!(packets.retain(&[7; 32]).is_stored());
        assert_eq!(packets.back_ptr(), Some(allocation), "retention must reuse a preallocated buffer");
        assert_eq!(packets.free_len() + packets.len(), PENDING_UID_UDP_POOL_CAPACITY);
    }

    #[test]
    fn pending_uid_udp_overflow_records_queue_drop_evidence() {
        let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));

        for offset in 0..=PENDING_UID_UDP_CAPACITY {
            let port = 40_000 + u16::try_from(offset).expect("test port offset");
            let packet = ipv4_udp_packet(port, 443, b"uid-pending");
            route_tun_packet(&packet, &mut state);
        }

        assert_eq!(state.pending_uid_udp_packets.len(), PENDING_UID_UDP_CAPACITY);
        assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
    }

    fn mapdns_loop_state(tun_egress_interceptor: Box<dyn TunEgressPacketHandler>) -> LoopState {
        let mut state = test_loop_state(tun_egress_interceptor);
        let synthetic_net = u32::from(Ipv4Addr::new(198, 18, 0, 0));
        let synthetic_mask = u32::from(Ipv4Addr::new(255, 254, 0, 0));
        state.runtime.mapdns_classify = Some((synthetic_net, synthetic_mask, 53));
        state.runtime.mapdns_runtime = Some(super::super::dns_intercept::MapDnsRuntime {
            intercept_addr: "198.18.0.10:53".parse().expect("intercept endpoint"),
            synthetic_net,
            synthetic_mask,
            intercept_port: 53,
        });
        state.dns_cache = Some(crate::dns_cache::DnsCache::new(synthetic_net, synthetic_mask, 8).expect("DNS cache"));
        state
    }

    fn mapdns_dns_query() -> Vec<u8> {
        vec![
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x', b'a', b'm', b'p',
            b'l', b'e', 0x04, b't', b'e', b's', b't', 0x00, 0x00, 0x01, 0x00, 0x01,
        ]
    }

    fn mapdns_dns_response(ip: Ipv4Addr) -> Vec<u8> {
        let name = Name::from_ascii("example.test").expect("name");
        let mut message = Message::response(0x1234, OpCode::Query);
        message.metadata.recursion_desired = true;
        message.metadata.recursion_available = true;
        message.metadata.response_code = ResponseCode::NoError;
        message.add_query(Query::new(name.clone(), RecordType::A));
        message.add_answer(Record::from_rdata(name, 60, RData::A(A(ip))));
        message.to_vec().expect("response encodes")
    }

    fn mapdns_dns_packet(src_port: u16) -> Vec<u8> {
        let query = mapdns_dns_query();
        let mut packet = ipv4_udp_packet(src_port, 53, &query);
        packet[16..20].copy_from_slice(&[198, 18, 0, 10]);
        packet
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

    fn ipv4_icmp_packet() -> Vec<u8> {
        let mut packet = vec![0u8; 28];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&28u16.to_be_bytes());
        packet[8] = 64;
        packet[9] = 1;
        packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
        packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
        packet[20] = 8;
        packet
    }

    fn ipv6_icmp_packet() -> Vec<u8> {
        let mut packet = vec![0u8; 48];
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&8u16.to_be_bytes());
        packet[6] = 58;
        packet[7] = 64;
        packet[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        packet[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        packet[40] = 128;
        packet
    }

    fn ipv4_icmp_fragment(first_fragment: bool) -> Vec<u8> {
        let mut packet = ipv4_icmp_packet();
        packet[6..8].copy_from_slice(&(if first_fragment { 0x2000_u16 } else { 0x0001_u16 }).to_be_bytes());
        packet
    }

    fn ipv6_icmp_fragment(first_fragment: bool) -> Vec<u8> {
        let mut packet = vec![0u8; 56];
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&16u16.to_be_bytes());
        packet[6] = 44;
        packet[7] = 64;
        packet[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        packet[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        packet[40] = 58;
        packet[42..44].copy_from_slice(&(if first_fragment { 0x0001_u16 } else { 0x0008_u16 }).to_be_bytes());
        packet
    }
}
