//! Core tunnel event loop — io_loop_task.
//!
//! Implements the 6-phase loop from spec.md v2.  One tokio task drives the
//! entire smoltcp TCP/IP stack and bridges sessions to/from the SOCKS5 proxy.

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use smoltcp::iface::{Interface, SocketSet};
use smoltcp::time::Instant;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};
use tun_rs::AsyncDevice;

use crate::classify::classify_ip_packet;
use crate::dns_cache::DnsCache;
use ripdpi_tunnel_config::Config;

use crate::{ActiveSessions, IpClass, Stats, TunDevice};

mod bridge;
mod dns_intercept;
mod packet;
mod tcp_accept;
mod udp_assoc;

use self::bridge::{enqueue_tun_packet, flush_device_tx_queue, pump_active_sessions, shutdown_active_sessions};
use self::dns_intercept::{
    build_encrypted_dns_resolver, dns_query_name, drain_dns_responses, handle_dns_result, parse_dns_cache,
    parse_mapdns_runtime, resolve_mapped_target, route_dns_packet, spawn_dns_worker, MapDnsRuntime,
};
use self::packet::{build_udp_response, is_injected_rst, TcpFlowKey};
use self::tcp_accept::{
    ensure_pending_listen_for_syn, gc_stale_pending_listens, make_auth, proxy_addr, spawn_new_tcp_sessions,
};
use self::udp_assoc::{
    forward_udp_payload, handle_udp_event, shutdown_udp_associations, UdpAssociation, UdpEvent, UdpEvictionEntry,
    DEFAULT_MAX_UDP_ASSOCIATIONS,
};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Buffer size for each `tokio::io::duplex()` pair (Decision A).
const DUPLEX_BUF: usize = 65536;

/// Rx/Tx buffer size for each smoltcp TcpSocket.
const TCP_SOCKET_BUF: usize = 65536;

/// Maximum chunk size for duplex bridge pumping (smoltcp <-> session).
const PUMP_CHUNK: usize = 4096;

/// Default poll delay when smoltcp has no pending timers.
const DEFAULT_POLL_DELAY_MS: u64 = 50;
const DNS_QUEUE_CAPACITY: usize = 256;

/// Timeout for pending LISTEN sockets that never complete the handshake.
const PENDING_LISTEN_TIMEOUT: Duration = Duration::from_secs(30);

/// How often (in loop iterations) to sweep stale pending LISTEN entries.
const PENDING_LISTEN_GC_INTERVAL: u32 = 100;

// ── Helpers ──────────────────────────────────────────────────────────────────

fn send_dns_servfail(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &DnsCache,
    src: SocketAddr,
    query: &[u8],
    host: Option<&str>,
    reason: &str,
) {
    stats.record_dns_failure(host, reason, None);
    match cache.servfail_response(query) {
        Ok(servfail) => {
            let raw = build_udp_response(mapdns.intercept_addr, src, &servfail);
            enqueue_tun_packet(device, raw, "dns-servfail");
        }
        Err(err) => debug!("failed to synthesize SERVFAIL ({reason}): {err}"),
    }
}

// ── io_loop_task ──────────────────────────────────────────────────────────────

/// Main tunnel event loop.
///
/// Implements the 6-phase loop from spec.md v2 (lines 504-588):
///
/// 1. Drain TUN fd -- classify raw IP packets; UDP->DNS/session, TCP->smoltcp
/// 2. smoltcp poll -- advance all TCP state machines
/// 3. New sessions -- detect ESTABLISHED sockets -> spawn `TcpSession`
/// 4. Duplex bridge -- pump data between smoltcp sockets and session tasks
/// 5. Flush tx_queue -- write smoltcp-produced packets back to TUN fd
/// 6. Wait -- sleep until TUN readable / poll_delay / cancellation
/// Optional io_uring context for batch TUN I/O acceleration.
///
/// When provided, Phase 5 (flush tx_queue) uses io_uring batched writes
/// instead of per-packet `try_send` calls. Phase 1 (drain TUN) still uses
/// `try_recv` because the async select loop depends on `tun.readable()` for
/// wakeup, which is incompatible with io_uring-driven reads.
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
pub struct IoUringTunContext {
    pub driver: std::sync::Arc<ripdpi_io_uring::IoUringDriver>,
    /// Owned TUN file descriptor kept alive for the duration of the io_uring context.
    /// Closed automatically when `IoUringTunContext` is dropped.
    pub tun_fd: std::os::fd::OwnedFd,
}

#[allow(clippy::too_many_arguments)]
pub async fn io_loop_task(
    tun: &AsyncDevice,
    mut device: TunDevice,
    mut iface: Interface,
    mut socket_set: SocketSet<'static>,
    mut sessions: ActiveSessions,
    config: Arc<Config>,
    cancel: CancellationToken,
    stats: Arc<Stats>,
    mut dns_cache: Option<DnsCache>,
) -> io::Result<()> {
    // ── One-time setup ────────────────────────────────────────────────────────

    let proxy_sockaddr =
        proxy_addr(&config).map_err(|e| io::Error::other(format!("resolve SOCKS5 proxy address: {e}")))?;
    let auth = make_auth(&config);

    let mapdns_runtime =
        parse_mapdns_runtime(&config).map_err(|e| io::Error::other(format!("parse mapdns runtime config: {e}")))?;
    dns_cache =
        parse_dns_cache(&config, dns_cache).map_err(|e| io::Error::other(format!("initialize DNS cache: {e}")))?;
    if let Some(mapdns) = config.mapdns.as_ref() {
        stats.configure_resolver_fallback(mapdns.resolver_fallback_active, mapdns.resolver_fallback_reason.as_deref());
    }
    let mapdns_classify = mapdns_runtime.map(|value| {
        (
            match value.intercept_addr.ip() {
                IpAddr::V4(v4) => u32::from(v4),
                IpAddr::V6(_) => unreachable!("mapdns runtime only supports IPv4"),
            },
            u32::MAX,
            value.intercept_port,
        )
    });

    let max_sessions = config.misc.max_session_count as usize;
    let filter_injected_resets = config.misc.filter_injected_resets;
    let udp_idle_timeout = Duration::from_millis(u64::from(config.misc.udp_read_write_timeout));

    // Tracks pending LISTEN sockets added on-demand per TCP flow.
    let mut pending_listens: HashMap<TcpFlowKey, _> = HashMap::new();
    let mut loop_iteration: u32 = 0;

    // Channel for UDP associations to return packets and lifecycle events.
    let (udp_tx, mut udp_rx) = tokio::sync::mpsc::channel::<UdpEvent>(256);
    let mut udp_associations: HashMap<SocketAddr, UdpAssociation> = HashMap::new();
    let mut udp_eviction_heap: ripdpi_collections::bounded_heap::BoundedHeap<UdpEvictionEntry> =
        ripdpi_collections::bounded_heap::BoundedHeap::new(DEFAULT_MAX_UDP_ASSOCIATIONS);
    let mut next_udp_association_id = 1u64;

    let (mut dns_req_tx, mut dns_resp_rx) = if let Some(resolver) = build_encrypted_dns_resolver(&config)
        .map_err(|e| io::Error::other(format!("build encrypted DNS resolver: {e}")))?
    {
        let (tx, rx) = spawn_dns_worker(resolver, cancel.child_token());
        (Some(tx), Some(rx))
    } else {
        (None, None)
    };

    // Read buffer -- sized for max MTU + overhead.
    let mtu = config.tunnel.mtu as usize;
    let mut buf = vec![0u8; mtu + 64];

    info!("io_loop started (proxy={}, max_sessions={})", proxy_sockaddr, max_sessions);

    loop {
        // ── Phase 1: drain TUN fd ─────────────────────────────────────────────
        loop {
            let n = match tun.try_recv(&mut buf) {
                Ok(0) => break, // EOF (unexpected for TUN; stop draining)
                Ok(n) => n,
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break, // no more data
                Err(e) => {
                    warn!("TUN read error: {}", e);
                    break;
                }
            };

            stats.tx_packets.fetch_add(1, Ordering::Relaxed);
            stats.tx_bytes.fetch_add(n as u64, Ordering::Relaxed);

            let pkt = &buf[..n];

            // Decision B: classify before handing to smoltcp.
            match classify_ip_packet(pkt, mapdns_classify) {
                IpClass::TcpOrOther => {
                    if filter_injected_resets && is_injected_rst(pkt) {
                        // Drop injected RST before it reaches smoltcp
                    } else {
                        ensure_pending_listen_for_syn(pkt, &mut pending_listens, &mut socket_set);
                        device.push_rx(pkt.to_vec());
                    }
                }

                IpClass::UdpDns { src, payload } => {
                    let host = dns_query_name(payload);
                    route_dns_packet(
                        &mut device,
                        &stats,
                        mapdns_runtime,
                        dns_cache.as_ref(),
                        &mut dns_req_tx,
                        &mut dns_resp_rx,
                        src,
                        payload,
                        host,
                    );
                }

                IpClass::Udp { src, dst, payload } => {
                    stats.record_dht_trigger_destination(dst);
                    if let Some(resolved_dst) = resolve_mapped_target(&stats, &mut dns_cache, dst) {
                        forward_udp_payload(
                            proxy_sockaddr,
                            &auth,
                            src,
                            resolved_dst,
                            payload,
                            &mut udp_associations,
                            &mut udp_eviction_heap,
                            &mut next_udp_association_id,
                            udp_idle_timeout,
                            &cancel,
                            &udp_tx,
                        )
                        .await;
                    }
                }
            }
        }

        if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache.as_mut()) {
            drain_dns_responses(&mut device, &stats, mapdns, cache, &mut dns_resp_rx, &mut dns_req_tx);
        }

        // ── Phase 2: advance smoltcp state machines ───────────────────────────
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // ── Phase 2.5: GC stale pending LISTEN sockets ──────────────────────
        loop_iteration = loop_iteration.wrapping_add(1);
        if loop_iteration.is_multiple_of(PENDING_LISTEN_GC_INTERVAL) {
            gc_stale_pending_listens(&mut pending_listens, &mut socket_set, PENDING_LISTEN_TIMEOUT);
        }

        // ── Phase 3: detect new ESTABLISHED TCP sockets -> spawn sessions ──────
        spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            proxy_sockaddr,
            &auth,
            &cancel,
            &stats,
            &mut dns_cache,
        );

        // ── Phase 4: pump duplex bridges (Decision A) ─────────────────────────
        pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

        // ── Phase 5: flush smoltcp tx_queue -> TUN fd ─────────────────────────
        flush_device_tx_queue(tun, &stats, &mut device)
            .await
            .map_err(|e| io::Error::other(format!("flush TUN tx queue: {e}")))?;

        // ── Phase 6: wait for next event ──────────────────────────────────────
        let smol_delay = iface
            .poll_delay(Instant::now(), &socket_set)
            .map_or(Duration::from_millis(DEFAULT_POLL_DELAY_MS), |d| Duration::from_micros(d.total_micros()));

        // Drain any UDP response packets that arrived between loop iterations.
        while let Ok(event) = udp_rx.try_recv() {
            handle_udp_event(&mut device, &mut udp_associations, event);
        }

        tokio::select! {
            _ = tun.readable() => {},
            _ = tokio::time::sleep(smol_delay) => {},
            udp_event = udp_rx.recv() => {
                if let Some(udp_event) = udp_event {
                    handle_udp_event(&mut device, &mut udp_associations, udp_event);
                }
            }
            dns_result = async {
                match dns_resp_rx.as_mut() {
                    Some(receiver) => receiver.recv().await,
                    None => None,
                }
            }, if dns_resp_rx.is_some() => {
                match dns_result {
                    Some(response) => {
                        if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache.as_mut()) {
                            handle_dns_result(&mut device, &stats, mapdns, cache, response);
                        }
                    }
                    None => {
                        stats.record_dns_failure(None, "dns worker exited unexpectedly", None);
                        dns_req_tx = None;
                        dns_resp_rx = None;
                    }
                }
            }
            _ = cancel.cancelled() => {
                info!("io_loop cancelled -- shutting down");
                break;
            }
        }
    }

    // Graceful shutdown: cancel and clean up all active sessions.
    shutdown_active_sessions(&mut sessions, &mut socket_set, &mut dns_cache).await;
    shutdown_udp_associations(&mut udp_associations).await;

    info!("io_loop exited cleanly");
    Ok(())
}
