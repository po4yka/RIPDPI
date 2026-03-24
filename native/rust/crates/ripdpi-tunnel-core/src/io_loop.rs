//! Core tunnel event loop — io_loop_task.
//!
//! Implements the 6-phase loop from spec.md v2.  One tokio task drives the
//! entire smoltcp TCP/IP stack and bridges sessions to/from the SOCKS5 proxy.

use std::collections::HashMap;
use std::io::{self, Read};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use smoltcp::iface::{Interface, SocketSet};
use smoltcp::time::Instant;
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use crate::classify::classify_ip_packet;
use crate::dns_cache::DnsCache;
use ripdpi_tunnel_config::Config;

use crate::{ActiveSessions, IpClass, Stats, TunDevice};

mod bridge;
mod dns_intercept;
mod packet;
mod tcp_accept;
mod udp_assoc;

use self::bridge::{flush_device_tx_queue, pump_active_sessions, shutdown_active_sessions, try_write_tun_packet};
use self::dns_intercept::{
    build_encrypted_dns_resolver, dns_query_name, handle_dns_result, parse_dns_cache, parse_mapdns_runtime,
    resolve_mapped_target, DnsRequest, DnsResponse,
};
use self::packet::{build_udp_response, is_injected_rst, TcpFlowKey};
use self::tcp_accept::{
    ensure_pending_listen_for_syn, gc_stale_pending_listens, make_auth, proxy_addr, spawn_new_tcp_sessions,
};
use self::udp_assoc::{forward_udp_payload, handle_udp_event, shutdown_udp_associations, UdpAssociation, UdpEvent};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Buffer size for each `tokio::io::duplex()` pair (Decision A).
const DUPLEX_BUF: usize = 65536;

/// Rx/Tx buffer size for each smoltcp TcpSocket.
const TCP_SOCKET_BUF: usize = 65536;

/// Maximum chunk size for duplex bridge pumping (smoltcp ↔ session).
const PUMP_CHUNK: usize = 4096;

/// Default poll delay when smoltcp has no pending timers.
const DEFAULT_POLL_DELAY_MS: u64 = 50;
const DNS_QUEUE_CAPACITY: usize = 256;

/// Timeout for pending LISTEN sockets that never complete the handshake.
const PENDING_LISTEN_TIMEOUT: Duration = Duration::from_secs(30);

/// How often (in loop iterations) to sweep stale pending LISTEN entries.
const PENDING_LISTEN_GC_INTERVAL: u32 = 100;

// ── io_loop_task ──────────────────────────────────────────────────────────────

/// Main tunnel event loop.
///
/// Implements the 6-phase loop from spec.md v2 (lines 504–588):
///
/// 1. Drain TUN fd — classify raw IP packets; UDP→DNS/session, TCP→smoltcp
/// 2. smoltcp poll — advance all TCP state machines
/// 3. New sessions — detect ESTABLISHED sockets → spawn `TcpSession`
/// 4. Duplex bridge — pump data between smoltcp sockets and session tasks
/// 5. Flush tx_queue — write smoltcp-produced packets back to TUN fd
/// 6. Wait — sleep until TUN readable / poll_delay / cancellation
#[allow(clippy::too_many_arguments)]
pub async fn io_loop_task(
    tun: &AsyncFd<std::fs::File>,
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

    let proxy_sockaddr = proxy_addr(&config)?;
    let auth = make_auth(&config);

    let mapdns_runtime = parse_mapdns_runtime(&config)?;
    dns_cache = parse_dns_cache(&config, dns_cache)?;
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
    // Key: observed SYN flow tuple, Value: (smoltcp SocketHandle, creation time).
    // Retransmitted SYNs reuse the same entry, while concurrent HTTPS flows are
    // allowed to allocate independent LISTEN sockets even on the same dst port.
    let mut pending_listens: HashMap<TcpFlowKey, _> = HashMap::new();
    let mut loop_iteration: u32 = 0;

    // Channel for UDP associations to return packets and lifecycle events.
    let (udp_tx, mut udp_rx) = tokio::sync::mpsc::channel::<UdpEvent>(256);
    let mut udp_associations: HashMap<SocketAddr, UdpAssociation> = HashMap::new();
    let mut next_udp_association_id = 1u64;
    let (mut dns_req_tx, mut dns_resp_rx) = if let Some(resolver) = build_encrypted_dns_resolver(&config)? {
        let (req_tx, mut req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(DNS_QUEUE_CAPACITY);
        let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(DNS_QUEUE_CAPACITY);
        let worker_cancel = cancel.child_token();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = worker_cancel.cancelled() => break,
                    request = req_rx.recv() => {
                        let Some(request) = request else {
                            break;
                        };
                        let upstream =
                            resolver
                                .exchange_with_metadata(&request.query)
                                .await
                                .map_err(|err| {
                                    let kind = err.kind();
                                    (kind, err.to_string())
                                });
                        let (resolver_error_kind, upstream) = match upstream {
                            Ok(success) => (None, Ok(success)),
                            Err((kind, message)) => (Some(kind), Err(message)),
                        };
                        if resp_tx.send(DnsResponse {
                            src: request.src,
                            query: request.query,
                            host: request.host,
                            upstream,
                            resolver_error_kind,
                        }).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });
        (Some(req_tx), Some(resp_rx))
    } else {
        (None, None)
    };

    // Read buffer — sized for max MTU + overhead.
    let mtu = config.tunnel.mtu as usize;
    let mut buf = vec![0u8; mtu + 64];

    info!("io_loop started (proxy={}, max_sessions={})", proxy_sockaddr, max_sessions);

    loop {
        // ── Phase 1: drain TUN fd ─────────────────────────────────────────────
        loop {
            let n = match tun.try_io(Interest::READABLE, |inner| {
                let mut f = inner;
                f.read(&mut buf)
            }) {
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
                        device.rx_queue.push_back(pkt.to_vec());
                    }
                }

                IpClass::UdpDns { src, payload } => {
                    let host = dns_query_name(&payload);
                    match (&mapdns_runtime, dns_cache.as_ref(), dns_req_tx.as_ref()) {
                        (Some(_), Some(_), Some(request_tx)) => {
                            let request = DnsRequest { src, query: payload, host };
                            match request_tx.try_send(request) {
                                Ok(()) => {}
                                Err(tokio::sync::mpsc::error::TrySendError::Full(request)) => {
                                    if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache.as_ref()) {
                                        stats.record_dns_failure(
                                            request.host.as_deref(),
                                            "dns worker queue full",
                                            None,
                                        );
                                        match cache.servfail_response(&request.query) {
                                            Ok(servfail) => {
                                                let raw =
                                                    build_udp_response(mapdns.intercept_addr, request.src, &servfail);
                                                try_write_tun_packet(tun, &stats, &raw, "dns-servfail");
                                            }
                                            Err(err) => debug!("failed to synthesize queue-full SERVFAIL: {err}"),
                                        }
                                    }
                                }
                                Err(tokio::sync::mpsc::error::TrySendError::Closed(request)) => {
                                    if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache.as_ref()) {
                                        stats.record_dns_failure(
                                            request.host.as_deref(),
                                            "dns worker unavailable",
                                            None,
                                        );
                                        match cache.servfail_response(&request.query) {
                                            Ok(servfail) => {
                                                let raw =
                                                    build_udp_response(mapdns.intercept_addr, request.src, &servfail);
                                                try_write_tun_packet(tun, &stats, &raw, "dns-servfail");
                                            }
                                            Err(err) => {
                                                debug!("failed to synthesize unavailable-worker SERVFAIL: {err}");
                                            }
                                        }
                                    }
                                    dns_req_tx = None;
                                    dns_resp_rx = None;
                                }
                            }
                        }
                        (Some(mapdns), Some(cache), None) => {
                            stats.record_dns_failure(host.as_deref(), "encrypted DNS resolver is not configured", None);
                            match cache.servfail_response(&payload) {
                                Ok(servfail) => {
                                    let raw = build_udp_response(mapdns.intercept_addr, src, &servfail);
                                    try_write_tun_packet(tun, &stats, &raw, "dns-servfail");
                                }
                                Err(err) => debug!("failed to synthesize missing-resolver SERVFAIL: {err}"),
                            }
                        }
                        _ => {
                            debug!("DNS intercept hit without mapdns runtime; dropping packet");
                        }
                    }
                }

                IpClass::Udp { src, dst, payload } => {
                    let resolved_dst = resolve_mapped_target(&stats, &mut dns_cache, dst);
                    forward_udp_payload(
                        proxy_sockaddr,
                        &auth,
                        src,
                        resolved_dst,
                        &payload,
                        &mut udp_associations,
                        &mut next_udp_association_id,
                        udp_idle_timeout,
                        &cancel,
                        &udp_tx,
                    )
                    .await;
                }
            }
        }

        if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache.as_mut()) {
            loop {
                let dns_response = match dns_resp_rx.as_mut() {
                    Some(receiver) => match receiver.try_recv() {
                        Ok(response) => Some(response),
                        Err(tokio::sync::mpsc::error::TryRecvError::Empty) => None,
                        Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                            dns_req_tx = None;
                            dns_resp_rx = None;
                            None
                        }
                    },
                    None => None,
                };
                let Some(response) = dns_response else {
                    break;
                };
                handle_dns_result(tun, &stats, mapdns, cache, response);
            }
        }

        // ── Phase 2: advance smoltcp state machines ───────────────────────────
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // ── Phase 2.5: GC stale pending LISTEN sockets ──────────────────────
        loop_iteration = loop_iteration.wrapping_add(1);
        if loop_iteration.is_multiple_of(PENDING_LISTEN_GC_INTERVAL) {
            gc_stale_pending_listens(&mut pending_listens, &mut socket_set, PENDING_LISTEN_TIMEOUT);
        }

        // ── Phase 3: detect new ESTABLISHED TCP sockets → spawn sessions ──────
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
        pump_active_sessions(&mut socket_set, &mut sessions).await;

        // ── Phase 5: flush smoltcp tx_queue → TUN fd ─────────────────────────
        flush_device_tx_queue(tun, &stats, &mut device).await?;

        // ── Phase 6: wait for next event ──────────────────────────────────────
        let smol_delay = iface
            .poll_delay(Instant::now(), &socket_set)
            .map_or(Duration::from_millis(DEFAULT_POLL_DELAY_MS), |d| Duration::from_micros(d.total_micros()));

        // Drain any UDP response packets that arrived between loop iterations.
        while let Ok(event) = udp_rx.try_recv() {
            handle_udp_event(tun, &stats, &mut udp_associations, event);
        }

        tokio::select! {
            _ = tun.readable() => {},
            _ = tokio::time::sleep(smol_delay) => {},
            udp_event = udp_rx.recv() => {
                if let Some(udp_event) = udp_event {
                    handle_udp_event(tun, &stats, &mut udp_associations, udp_event);
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
                            handle_dns_result(tun, &stats, mapdns, cache, response);
                        }
                    }
                    None => {
                        dns_req_tx = None;
                        dns_resp_rx = None;
                    }
                }
            }
            _ = cancel.cancelled() => {
                info!("io_loop cancelled — shutting down");
                break;
            }
        }
    }

    // Graceful shutdown: cancel and clean up all active sessions.
    shutdown_active_sessions(&mut sessions, &mut socket_set).await;
    shutdown_udp_associations(&mut udp_associations).await;

    info!("io_loop exited cleanly");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    use ripdpi_dns_resolver::EncryptedDnsProtocol;
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};
    use smoltcp::wire::IpAddress;

    use super::packet::{
        build_udp_port_unreachable, checksum_sum, endpoint_to_socketaddr, finalize_checksum, ipv4_transport_offset,
        is_tcp_syn, tcp_dst_port, tcp_syn_flow_key,
    };
    use super::tcp_accept::{socketaddr_to_listen_endpoint, tcp_session_target_addr};

    fn ipv4_tcp_rst(ip_id: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45; // IPv4, IHL=5
        pkt[3] = 40; // total length
        pkt[4] = (ip_id >> 8) as u8; // IP ID high
        pkt[5] = (ip_id & 0xFF) as u8; // IP ID low
        pkt[8] = 64; // TTL
        pkt[9] = 6; // TCP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]); // src IP
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]); // dst IP
        pkt[32] = 0x50; // TCP data offset = 5
        pkt[33] = 0x04; // RST flag
        pkt
    }

    fn ipv4_tcp_syn() -> Vec<u8> {
        ipv4_tcp_syn_with_ports(12345, 443)
    }

    fn build_ipv4_tcp_syn_packet(src_ip: Ipv4Addr, dst_ip: Ipv4Addr, src_port: u16, dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&src_ip.octets());
        pkt[16..20].copy_from_slice(&dst_ip.octets());
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x02; // SYN
        let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
        pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
        let tcp_checksum = {
            let mut sum = checksum_sum(&src_ip.octets());
            sum += checksum_sum(&dst_ip.octets());
            sum += u32::from(6u16);
            sum += u32::from((pkt.len() - 20) as u16);
            sum += checksum_sum(&pkt[20..]);
            finalize_checksum(sum)
        };
        pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
        pkt
    }

    fn ipv4_tcp_syn_with_ports(src_port: u16, dst_port: u16) -> Vec<u8> {
        build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 1), Ipv4Addr::new(10, 0, 0, 2), src_port, dst_port)
    }

    fn ipv4_tcp_ack(dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        pkt[20..22].copy_from_slice(&12345u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x10; // ACK
        pkt
    }

    fn ipv6_tcp_syn(dst_port: u16) -> Vec<u8> {
        ipv6_tcp_syn_with_ports(12345, dst_port)
    }

    fn build_ipv6_tcp_syn_packet(src_ip: Ipv6Addr, dst_ip: Ipv6Addr, src_port: u16, dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 60];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
        pkt[6] = 6;
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&src_ip.octets());
        pkt[24..40].copy_from_slice(&dst_ip.octets());
        pkt[40..42].copy_from_slice(&src_port.to_be_bytes());
        pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
        pkt[52] = 0x50;
        pkt[53] = 0x02;
        let tcp_len = u32::try_from(pkt.len() - 40).expect("tcp length");
        let mut sum = checksum_sum(&src_ip.octets());
        sum += checksum_sum(&dst_ip.octets());
        sum += (tcp_len >> 16) + (tcp_len & 0xFFFF);
        sum += u32::from(6u16);
        sum += checksum_sum(&pkt[40..]);
        let tcp_checksum = finalize_checksum(sum);
        pkt[56..58].copy_from_slice(&tcp_checksum.to_be_bytes());
        pkt
    }

    fn ipv6_tcp_syn_with_ports(src_port: u16, dst_port: u16) -> Vec<u8> {
        build_ipv6_tcp_syn_packet(Ipv6Addr::LOCALHOST, Ipv6Addr::LOCALHOST, src_port, dst_port)
    }

    #[test]
    fn injected_rst_with_ip_id_zero_is_detected() {
        assert!(is_injected_rst(&ipv4_tcp_rst(0x0000)));
    }

    #[test]
    fn injected_rst_with_ip_id_one_is_detected() {
        assert!(is_injected_rst(&ipv4_tcp_rst(0x0001)));
    }

    #[test]
    fn real_rst_with_normal_ip_id_is_not_injected() {
        assert!(!is_injected_rst(&ipv4_tcp_rst(0x1234)));
    }

    #[test]
    fn tcp_syn_is_not_injected_rst() {
        assert!(!is_injected_rst(&ipv4_tcp_syn()));
    }

    #[test]
    fn short_packet_is_not_injected_rst() {
        assert!(!is_injected_rst(&[0x45, 0x00, 0x00]));
    }

    #[test]
    fn packet_with_zero_ihl_is_not_injected_rst() {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x40; // IPv4, IHL=0 (malformed)
        pkt[3] = 40;
        pkt[9] = 6; // TCP
                    // IP ID = 0x0000 (would look like injected without the guard)
        pkt[33] = 0x04; // RST flag (at byte 33, which is IHL+13 only if IHL=20)
        assert!(!is_injected_rst(&pkt), "malformed IHL=0 packet should not be detected as injected RST");
    }

    #[test]
    fn tcp_syn_detects_ipv6_packets() {
        assert!(is_tcp_syn(&ipv6_tcp_syn(443)));
    }

    #[test]
    fn ipv4_transport_helpers_reject_wrong_protocol_and_extract_ports() {
        let mut udp_packet = ipv4_tcp_syn();
        udp_packet[9] = 17;

        assert_eq!(ipv4_transport_offset(&ipv4_tcp_syn(), 6), Some(20));
        assert_eq!(ipv4_transport_offset(&udp_packet, 6), None);
        assert_eq!(tcp_dst_port(&ipv4_tcp_ack(8443)), Some(8443));
        assert!(!is_tcp_syn(&ipv4_tcp_ack(8443)));
    }

    #[test]
    fn tcp_dst_port_extracts_ipv6_destination_port() {
        assert_eq!(tcp_dst_port(&ipv6_tcp_syn(8443)), Some(8443));
    }

    #[test]
    fn tcp_syn_flow_key_extracts_ipv4_endpoints() {
        let key = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51000, 443)).expect("ipv4 syn flow key");

        assert_eq!(key.src, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 51000));
        assert_eq!(key.dst, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 443));
    }

    #[test]
    fn tcp_syn_flow_key_distinguishes_parallel_https_flows() {
        let first = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51000, 443)).expect("first flow");
        let second = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51001, 443)).expect("second flow");

        assert_ne!(first, second);
    }

    #[test]
    fn tcp_syn_flow_key_extracts_ipv6_endpoints() {
        let key = tcp_syn_flow_key(&ipv6_tcp_syn_with_ports(51000, 443)).expect("ipv6 syn flow key");

        assert_eq!(key.src, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 51000));
        assert_eq!(key.dst, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443));
    }

    #[test]
    fn socketaddr_to_listen_endpoint_preserves_ip_and_port() {
        let ipv4 = socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443));
        let ipv6 = socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8443));

        assert_eq!(ipv4.addr, Some(IpAddress::v4(203, 0, 113, 10)));
        assert_eq!(ipv4.port, 443);
        assert_eq!(ipv6.addr, Some(IpAddress::v6(0, 0, 0, 0, 0, 0, 0, 1)));
        assert_eq!(ipv6.port, 8443);
    }

    #[test]
    fn listeners_bound_to_different_destination_ips_do_not_steal_https_flows() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2))
            .expect("default ipv4 route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);

        let mut first = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );
        let mut second = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );

        first
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)))
            .expect("first listener");
        second
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443)))
            .expect("second listener");

        let first_handle = socket_set.add(first);
        let second_handle = socket_set.add(second);

        let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 1), Ipv4Addr::new(203, 0, 113, 20), 51000, 443);
        device.rx_queue.push_back(syn);

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let first_socket = socket_set.get::<TcpSocket>(first_handle);
        let second_socket = socket_set.get::<TcpSocket>(second_handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        assert_eq!(first_socket.state(), tcp::State::Listen);
        assert_eq!(second_socket.state(), tcp::State::SynReceived);
        assert_eq!(
            second_socket.local_endpoint().map(endpoint_to_socketaddr),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443))
        );
        assert_eq!(
            second_socket.remote_endpoint().map(endpoint_to_socketaddr),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 51000))
        );
        assert_eq!(
            tcp_session_target_addr(&stats, &mut dns_cache, second_socket),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443))
        );
    }

    #[test]
    fn tcp_session_target_addr_prefers_intercepted_ipv4_destination_over_client_source() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 10, 10, 10), 24)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 10, 10, 10))
            .expect("default ipv4 route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);

        let mut socket = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );
        socket
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443)))
            .expect("listener");

        let handle = socket_set.add(socket);
        let client = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 10, 10, 10)), 51000);
        let destination = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443);
        device.rx_queue.push_back(build_ipv4_tcp_syn_packet(
            Ipv4Addr::new(10, 10, 10, 10),
            Ipv4Addr::new(203, 0, 113, 20),
            51000,
            443,
        ));

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let socket = socket_set.get::<TcpSocket>(handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let target = tcp_session_target_addr(&stats, &mut dns_cache, socket).expect("session target");

        assert_eq!(socket.remote_endpoint().map(endpoint_to_socketaddr), Some(client),);
        assert_eq!(target, destination);
        assert_ne!(target, client);
    }

    #[test]
    fn tcp_session_target_addr_prefers_intercepted_ipv6_destination_over_client_source() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        let destination_ip = Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, 1);
        let client_ip = Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, 2);
        let [a, b, c, d, e, f, g, h] = destination_ip.segments();
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v6(a, b, c, d, e, f, g, h), 128)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv6_route(smoltcp::wire::Ipv6Address::new(a, b, c, d, e, f, g, h))
            .expect("default ipv6 route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);

        let mut socket = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );
        let destination = SocketAddr::new(IpAddr::V6(destination_ip), 443);
        let client = SocketAddr::new(IpAddr::V6(client_ip), 51000);
        socket.listen(socketaddr_to_listen_endpoint(destination)).expect("listener");

        let handle = socket_set.add(socket);
        device.rx_queue.push_back(build_ipv6_tcp_syn_packet(client_ip, destination_ip, 51000, 443));

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let socket = socket_set.get::<TcpSocket>(handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let target = tcp_session_target_addr(&stats, &mut dns_cache, &socket).expect("session target");

        assert_eq!(socket.remote_endpoint().map(endpoint_to_socketaddr), Some(client),);
        assert_eq!(target, destination);
        assert_ne!(target, client);
    }

    #[test]
    fn build_udp_response_supports_ipv4() {
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 53);
        let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 5353);
        let payload = b"dns";

        let pkt = build_udp_response(src, dst, payload);

        assert_eq!(pkt.len(), 20 + 8 + payload.len());
        assert_eq!(pkt[0] >> 4, 4);
        assert_eq!(pkt[9], 17);
        assert_eq!(u16::from_be_bytes([pkt[20], pkt[21]]), 53);
        assert_eq!(u16::from_be_bytes([pkt[22], pkt[23]]), 5353);
        assert_ne!(u16::from_be_bytes([pkt[26], pkt[27]]), 0);
        assert_eq!(&pkt[28..], payload);
    }

    #[test]
    fn build_udp_response_supports_ipv6() {
        let src = SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 53);
        let dst = SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 5353);
        let payload = b"dns";

        let pkt = build_udp_response(src, dst, payload);

        assert_eq!(pkt.len(), 40 + 8 + payload.len());
        assert_eq!(pkt[0] >> 4, 6);
        assert_eq!(pkt[6], 17);
        assert_eq!(u16::from_be_bytes([pkt[40], pkt[41]]), 53);
        assert_eq!(u16::from_be_bytes([pkt[42], pkt[43]]), 5353);
        assert_ne!(u16::from_be_bytes([pkt[46], pkt[47]]), 0);
        assert_eq!(&pkt[48..], payload);
    }

    #[test]
    fn build_udp_response_rejects_oversized_payloads() {
        let payload = vec![0u8; usize::from(u16::MAX)];
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 53);
        let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 5353);

        assert!(build_udp_response(src, dst, &payload).is_empty());
    }

    #[test]
    fn build_udp_port_unreachable_supports_ipv4() {
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);
        let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(157, 240, 229, 174)), 443);
        let expected_src = match dst.ip() {
            IpAddr::V4(value) => value.octets(),
            _ => panic!("expected ipv4"),
        };
        let expected_dst = match src.ip() {
            IpAddr::V4(value) => value.octets(),
            _ => panic!("expected ipv4"),
        };

        let pkt = build_udp_port_unreachable(src, dst, b"quic");

        assert_eq!(pkt[0] >> 4, 4);
        assert_eq!(pkt[9], 1);
        assert_eq!(pkt[20], 3);
        assert_eq!(pkt[21], 3);
        assert_eq!(&pkt[12..16], &expected_src);
        assert_eq!(&pkt[16..20], &expected_dst);
        assert!(!pkt[28..].is_empty());
    }

    #[test]
    fn build_udp_port_unreachable_supports_ipv6() {
        let src = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 53000);
        let dst = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443);

        let pkt = build_udp_port_unreachable(src, dst, b"quic");

        assert_eq!(pkt[0] >> 4, 6);
        assert_eq!(pkt[6], 58);
        assert_eq!(pkt[40], 1);
        assert_eq!(pkt[41], 4);
        assert!(!pkt[48..].is_empty());
    }

    #[test]
    fn parse_mapdns_runtime_uses_address_defaults_for_network_and_mask() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 5300,
            network: None,
            netmask: None,
            cache_size: 8,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            doh_url: None,
            doh_bootstrap_ips: Vec::new(),
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");

        assert_eq!(runtime.intercept_addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 5300));
        assert_eq!(runtime.synthetic_net, u32::from(Ipv4Addr::new(198, 18, 0, 10)));
        assert_eq!(runtime.synthetic_mask, u32::from(Ipv4Addr::new(255, 254, 0, 0)));
        assert_eq!(runtime.intercept_port, 5300);
    }

    #[test]
    fn parse_dns_cache_rejects_zero_cache_size() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 53,
            network: None,
            netmask: None,
            cache_size: 0,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            doh_url: None,
            doh_bootstrap_ips: Vec::new(),
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let err = match parse_dns_cache(&config, None) {
            Ok(_) => panic!("zero cache size should fail"),
            Err(err) => err,
        };

        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        assert_eq!(err.to_string(), "mapdns.cache_size must be greater than zero");
    }

    #[test]
    fn build_encrypted_dns_resolver_uses_doh_url_defaults() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 53,
            network: None,
            netmask: None,
            cache_size: 16,
            resolver_id: Some("fixture".to_string()),
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            doh_url: Some("https://dns.example.test/dns-query".to_string()),
            doh_bootstrap_ips: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
            dns_query_timeout_ms: 2500,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let resolver = build_encrypted_dns_resolver(&config).expect("resolver build").expect("resolver");
        let endpoint = resolver.endpoint();

        assert_eq!(endpoint.protocol, EncryptedDnsProtocol::Doh);
        assert_eq!(endpoint.host, "dns.example.test");
        assert_eq!(endpoint.port, 443);
        assert_eq!(endpoint.tls_server_name.as_deref(), Some("dns.example.test"));
        assert_eq!(
            endpoint.bootstrap_ips,
            vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        );
        assert_eq!(endpoint.doh_url.as_deref(), Some("https://dns.example.test/dns-query"));
    }

    #[test]
    fn dns_query_name_extracts_labels_and_rejects_compression_pointers() {
        let plain_query = [
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, b'w', b'w', b'w', 0x07, b'e',
            b'x', b'a', b'm', b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
        ];
        let compressed_query = [0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xc0, 0x0c];

        assert_eq!(dns_query_name(&plain_query), Some("www.example.com".to_string()));
        assert_eq!(dns_query_name(&compressed_query), None);
    }

    fn tunnel_config_with_mapdns(mapdns: Option<ripdpi_tunnel_config::MapDnsConfig>) -> ripdpi_tunnel_config::Config {
        ripdpi_tunnel_config::Config {
            tunnel: ripdpi_tunnel_config::TunnelConfig::default(),
            socks5: ripdpi_tunnel_config::Socks5Config {
                port: 1080,
                address: "127.0.0.1".to_string(),
                udp: None,
                udp_address: None,
                pipeline: None,
                username: None,
                password: None,
                mark: None,
            },
            mapdns,
            misc: ripdpi_tunnel_config::MiscConfig::default(),
        }
    }
}
