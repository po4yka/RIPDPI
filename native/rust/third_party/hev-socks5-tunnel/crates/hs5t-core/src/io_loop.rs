//! Core tunnel event loop — io_loop_task.
//!
//! Implements the 6-phase loop from spec.md v2.  One tokio task drives the
//! entire smoltcp TCP/IP stack and bridges sessions to/from the SOCKS5 proxy.

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::str::FromStr;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};
use std::time::{Duration, Instant as StdInstant};

use smoltcp::iface::{Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::socket::Socket;
use smoltcp::time::Instant;
use smoltcp::wire::IpAddress;
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWriteExt, Interest, ReadBuf};
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, info, warn};

use hs5t_config::Config;
use hs5t_dns_cache::DnsCache;
use hs5t_session::{Auth, TargetAddr, TcpSession, UdpSession};
use ripdpi_dns_resolver::{
    EncryptedDnsEndpoint, EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsProtocol,
    EncryptedDnsResolver, EncryptedDnsTransport,
};

use crate::classify::classify_ip_packet;
use crate::{ActiveSessions, IpClass, SessionEntry, Stats, TunDevice};

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

// ── No-op waker ───────────────────────────────────────────────────────────────

/// A `Wake` implementation that does nothing.
///
/// Used to poll a `DuplexStream` once without blocking: if the stream is not
/// immediately ready, the waker is never invoked and we treat it as WouldBlock.
struct NoopWaker;

impl std::task::Wake for NoopWaker {
    fn wake(self: Arc<Self>) {}
    fn wake_by_ref(self: &Arc<Self>) {}
}

/// Try to read from a `DuplexStream` without suspending.
///
/// Returns:
/// - `Some(Ok(n))` — n bytes read (n==0 means session_side EOF)
/// - `None`        — no data available right now (would-block equivalent)
/// - `Some(Err(e))`— read error
fn try_read_duplex(stream: &mut tokio::io::DuplexStream, buf: &mut [u8]) -> Option<io::Result<usize>> {
    let waker = Waker::from(Arc::new(NoopWaker));
    let mut cx = Context::from_waker(&waker);
    let mut rb = ReadBuf::new(buf);
    match Pin::new(stream).poll_read(&mut cx, &mut rb) {
        Poll::Ready(Ok(())) => Some(Ok(rb.filled().len())),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

// ── Packet helpers ────────────────────────────────────────────────────────────

fn ipv4_transport_offset(pkt: &[u8], protocol: u8) -> Option<usize> {
    if pkt.len() < 20 || pkt[0] >> 4 != 4 || pkt[9] != protocol {
        return None;
    }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if ihl < 20 || pkt.len() < ihl {
        return None;
    }
    Some(ihl)
}

fn ipv6_transport_offset(pkt: &[u8], next_header: u8) -> Option<usize> {
    if pkt.len() < 40 || pkt[0] >> 4 != 6 || pkt[6] != next_header {
        return None;
    }
    Some(40)
}

fn tcp_header_offset(pkt: &[u8]) -> Option<usize> {
    match pkt.first().map(|value| value >> 4) {
        Some(4) => {
            let offset = ipv4_transport_offset(pkt, 6)?;
            (pkt.len() >= offset + 14).then_some(offset)
        }
        Some(6) => {
            let offset = ipv6_transport_offset(pkt, 6)?;
            (pkt.len() >= offset + 14).then_some(offset)
        }
        _ => None,
    }
}

/// Extract the TCP destination port from a raw IPv4 or IPv6 packet, or `None`
/// if the packet is not a plain TCP transport packet.
fn tcp_dst_port(pkt: &[u8]) -> Option<u16> {
    let offset = tcp_header_offset(pkt)?;
    Some(u16::from_be_bytes([pkt[offset + 2], pkt[offset + 3]]))
}

/// Return `true` if the raw IPv4 or IPv6 packet is a pure TCP SYN
/// (SYN=1, ACK=0).
fn is_tcp_syn(pkt: &[u8]) -> bool {
    let Some(offset) = tcp_header_offset(pkt) else {
        return false;
    };
    pkt[offset + 13] & 0x12 == 0x02
}

/// Return `true` if the raw IPv4 packet looks like an injected TCP RST.
///
/// Passive DPI boxes (e.g. Russian SORM/MGTS) inject spoofed RST packets
/// with IP ID 0x0000 or 0x0001, which is never used by real endpoints.
fn is_injected_rst(pkt: &[u8]) -> bool {
    let Some(ihl) = ipv4_transport_offset(pkt, 6) else {
        return false;
    };
    if pkt.len() < ihl + 14 {
        return false;
    }
    // TCP RST flag is bit 2 (0x04) in the flags byte
    if pkt[ihl + 13] & 0x04 == 0 {
        return false;
    }
    // Injected RSTs have IP ID 0x0000 or 0x0001
    let ip_id = u16::from_be_bytes([pkt[4], pkt[5]]);
    ip_id <= 1
}

fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = chunks.remainder().first() {
        sum += u32::from(*last) << 8;
    }
    sum
}

fn finalize_checksum(mut sum: u32) -> u16 {
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn normalize_udp_checksum(checksum: u16) -> u16 {
    if checksum == 0 {
        0xFFFF
    } else {
        checksum
    }
}

fn udp_checksum_ipv4(src_ip: [u8; 4], dst_ip: [u8; 4], udp_packet: &[u8]) -> u16 {
    let udp_len = u16::try_from(udp_packet.len()).unwrap_or(u16::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += u32::from(17u16);
    sum += u32::from(udp_len);
    sum += checksum_sum(udp_packet);
    normalize_udp_checksum(finalize_checksum(sum))
}

fn udp_checksum_ipv6(src_ip: [u8; 16], dst_ip: [u8; 16], udp_packet: &[u8]) -> u16 {
    let udp_len = u32::try_from(udp_packet.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (udp_len >> 16) + (udp_len & 0xFFFF);
    sum += u32::from(17u16);
    sum += checksum_sum(udp_packet);
    normalize_udp_checksum(finalize_checksum(sum))
}

/// Build a raw IPv4/UDP or IPv6/UDP packet for a tunnel response.
///
/// `src` — the mapdns address (e.g. 198.18.0.0:53)
/// `dst` — the original query source (the TUN client)
fn build_udp_response(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let udp_len = match u16::try_from(8usize + payload.len()) {
        Ok(value) => value,
        Err(_) => return Vec::new(),
    };

    match (src, dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let total_len = match u16::try_from(20usize + usize::from(udp_len)) {
                Ok(value) => value,
                Err(_) => return Vec::new(),
            };
            let mut pkt = vec![0u8; usize::from(total_len)];
            let src_ip = src.ip().octets();
            let dst_ip = dst.ip().octets();

            pkt[0] = 0x45;
            pkt[2..4].copy_from_slice(&total_len.to_be_bytes());
            pkt[8] = 64;
            pkt[9] = 17;
            pkt[12..16].copy_from_slice(&src_ip);
            pkt[16..20].copy_from_slice(&dst_ip);

            pkt[20..22].copy_from_slice(&src.port().to_be_bytes());
            pkt[22..24].copy_from_slice(&dst.port().to_be_bytes());
            pkt[24..26].copy_from_slice(&udp_len.to_be_bytes());
            pkt[28..28 + payload.len()].copy_from_slice(payload);

            let header_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
            pkt[10..12].copy_from_slice(&header_checksum.to_be_bytes());

            let udp_checksum = udp_checksum_ipv4(src_ip, dst_ip, &pkt[20..]);
            pkt[26..28].copy_from_slice(&udp_checksum.to_be_bytes());

            pkt
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let total_len = 40usize + usize::from(udp_len);
            let mut pkt = vec![0u8; total_len];
            let src_ip = src.ip().octets();
            let dst_ip = dst.ip().octets();

            pkt[0] = 0x60;
            pkt[4..6].copy_from_slice(&udp_len.to_be_bytes());
            pkt[6] = 17;
            pkt[7] = 64;
            pkt[8..24].copy_from_slice(&src_ip);
            pkt[24..40].copy_from_slice(&dst_ip);

            pkt[40..42].copy_from_slice(&src.port().to_be_bytes());
            pkt[42..44].copy_from_slice(&dst.port().to_be_bytes());
            pkt[44..46].copy_from_slice(&udp_len.to_be_bytes());
            pkt[48..48 + payload.len()].copy_from_slice(payload);

            let udp_checksum = udp_checksum_ipv6(src_ip, dst_ip, &pkt[40..]);
            pkt[46..48].copy_from_slice(&udp_checksum.to_be_bytes());

            pkt
        }
        _ => Vec::new(),
    }
}

/// Convert a smoltcp `IpEndpoint` to a std `SocketAddr`.
fn endpoint_to_socketaddr(ep: smoltcp::wire::IpEndpoint) -> SocketAddr {
    let ip: IpAddr = match ep.addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(v4),
        IpAddress::Ipv6(v6) => IpAddr::V6(v6),
    };
    SocketAddr::new(ip, ep.port)
}

/// Build `Auth` from config credentials.
fn make_auth(config: &Config) -> Auth {
    match (&config.socks5.username, &config.socks5.password) {
        (Some(u), Some(p)) => Auth::UserPass { username: u.clone(), password: p.clone() },
        _ => Auth::NoAuth,
    }
}

/// Resolve the SOCKS5 proxy `SocketAddr` from config.
fn proxy_addr(config: &Config) -> io::Result<SocketAddr> {
    let ip: IpAddr = config
        .socks5
        .address
        .parse()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid socks5.address"))?;
    Ok(SocketAddr::new(ip, config.socks5.port))
}

#[derive(Debug, Clone, Copy)]
struct MapDnsRuntime {
    intercept_addr: SocketAddr,
    synthetic_net: u32,
    synthetic_mask: u32,
    intercept_port: u16,
}

#[derive(Debug, Clone)]
struct DnsRequest {
    src: SocketAddr,
    query: Vec<u8>,
    host: Option<String>,
}

#[derive(Debug, Clone)]
struct DnsResponse {
    src: SocketAddr,
    query: Vec<u8>,
    host: Option<String>,
    upstream: Result<EncryptedDnsExchangeSuccess, String>,
    resolver_error_kind: Option<EncryptedDnsErrorKind>,
}

fn parse_mapdns_runtime(config: &Config) -> io::Result<Option<MapDnsRuntime>> {
    let Some(mapdns) = &config.mapdns else {
        return Ok(None);
    };

    let intercept_ip = mapdns.address.parse::<Ipv4Addr>().map_err(|err| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid mapdns.address '{}': {err}", mapdns.address))
    })?;
    let synthetic_net =
        mapdns.network.as_deref().unwrap_or(mapdns.address.as_str()).parse::<Ipv4Addr>().map(u32::from).map_err(
            |err| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!(
                        "invalid mapdns.network '{}': {err}",
                        mapdns.network.as_deref().unwrap_or(mapdns.address.as_str())
                    ),
                )
            },
        )?;
    let synthetic_mask =
        mapdns.netmask.as_deref().unwrap_or("255.254.0.0").parse::<Ipv4Addr>().map(u32::from).map_err(|err| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("invalid mapdns.netmask '{}': {err}", mapdns.netmask.as_deref().unwrap_or("255.254.0.0")),
            )
        })?;

    Ok(Some(MapDnsRuntime {
        intercept_addr: SocketAddr::new(IpAddr::V4(intercept_ip), mapdns.port),
        synthetic_net,
        synthetic_mask,
        intercept_port: mapdns.port,
    }))
}

fn parse_dns_cache(config: &Config, dns_cache: Option<DnsCache>) -> io::Result<Option<DnsCache>> {
    if dns_cache.is_some() {
        return Ok(dns_cache);
    }

    let Some(runtime) = parse_mapdns_runtime(config)? else {
        return Ok(None);
    };
    let cache_size = config.mapdns.as_ref().map(|value| value.cache_size as usize).unwrap_or_default();
    if cache_size == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "mapdns.cache_size must be greater than zero"));
    }

    Ok(Some(DnsCache::new(runtime.synthetic_net, runtime.synthetic_mask, cache_size)))
}

fn parse_encrypted_dns_protocol(value: &str) -> Option<EncryptedDnsProtocol> {
    match value.trim().to_ascii_lowercase().as_str() {
        "dot" => Some(EncryptedDnsProtocol::Dot),
        "dnscrypt" => Some(EncryptedDnsProtocol::DnsCrypt),
        "doh" => Some(EncryptedDnsProtocol::Doh),
        _ => None,
    }
}

fn parse_url_host(value: &str) -> Option<String> {
    let (_, rest) = value.split_once("://")?;
    let authority = rest.split('/').next()?;
    let host = authority.trim_start_matches('[').trim_end_matches(']').split(':').next().unwrap_or_default().trim();
    (!host.is_empty()).then(|| host.to_string())
}

fn build_encrypted_dns_resolver(config: &Config) -> io::Result<Option<EncryptedDnsResolver>> {
    let Some(mapdns) = &config.mapdns else {
        return Ok(None);
    };

    let protocol = mapdns
        .encrypted_dns_protocol
        .as_deref()
        .and_then(parse_encrypted_dns_protocol)
        .or_else(|| mapdns.doh_url.as_deref().map(|_| EncryptedDnsProtocol::Doh));
    let Some(protocol) = protocol else {
        return Ok(None);
    };

    let bootstrap_values = if mapdns.encrypted_dns_bootstrap_ips.is_empty() {
        &mapdns.doh_bootstrap_ips
    } else {
        &mapdns.encrypted_dns_bootstrap_ips
    };
    let bootstrap_ips = bootstrap_values
        .iter()
        .map(|value| {
            IpAddr::from_str(value).map_err(|err| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("invalid encrypted DNS bootstrap entry '{value}': {err}"),
                )
            })
        })
        .collect::<Result<Vec<_>, _>>()?;

    let doh_url = mapdns.encrypted_dns_doh_url.clone().or_else(|| mapdns.doh_url.clone());
    let host = mapdns
        .encrypted_dns_host
        .clone()
        .unwrap_or_else(|| doh_url.as_deref().and_then(parse_url_host).unwrap_or_default());
    let resolver = EncryptedDnsResolver::with_timeout(
        EncryptedDnsEndpoint {
            protocol,
            resolver_id: mapdns.resolver_id.clone(),
            host,
            port: mapdns.encrypted_dns_port.unwrap_or_default(),
            tls_server_name: mapdns.encrypted_dns_tls_server_name.clone(),
            bootstrap_ips,
            doh_url,
            dnscrypt_provider_name: mapdns.encrypted_dns_dnscrypt_provider_name.clone(),
            dnscrypt_public_key: mapdns.encrypted_dns_dnscrypt_public_key.clone(),
        },
        EncryptedDnsTransport::Direct,
        Duration::from_millis(u64::from(mapdns.dns_query_timeout_ms)),
    )
    .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;

    Ok(Some(resolver))
}

fn dns_query_name(packet: &[u8]) -> Option<String> {
    if packet.len() < 12 {
        return None;
    }
    if u16::from_be_bytes([packet[4], packet[5]]) == 0 {
        return None;
    }

    let mut offset = 12usize;
    let mut labels = Vec::new();
    loop {
        let length = *packet.get(offset)? as usize;
        offset += 1;
        if length == 0 {
            break;
        }
        if length & 0b1100_0000 != 0 {
            return None;
        }
        let label = packet.get(offset..offset + length)?;
        labels.push(std::str::from_utf8(label).ok()?.to_string());
        offset += length;
    }
    if labels.is_empty() {
        None
    } else {
        Some(labels.join("."))
    }
}

fn try_write_tun_packet(tun: &AsyncFd<std::fs::File>, stats: &Arc<Stats>, raw: &[u8], context: &str) {
    if raw.is_empty() {
        return;
    }

    match tun.try_io(Interest::WRITABLE, |inner| {
        let mut f = inner;
        f.write_all(raw)
    }) {
        Ok(()) => {
            stats.rx_packets.fetch_add(1, Ordering::Relaxed);
            stats.rx_bytes.fetch_add(raw.len() as u64, Ordering::Relaxed);
        }
        Err(err) => {
            debug!("{context} response write dropped: {err:?}");
        }
    }
}

fn handle_dns_result(
    tun: &AsyncFd<std::fs::File>,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dns_cache: &mut DnsCache,
    response: DnsResponse,
) {
    match response.upstream {
        Ok(upstream) => match dns_cache.rewrite_response(&response.query, &upstream.response_bytes) {
            Ok(result) => {
                stats.record_dns_success(
                    &result.host,
                    result.cache_hits,
                    result.cache_misses,
                    Some(&upstream.endpoint_label),
                    Some(upstream.latency_ms),
                );
                let raw = build_udp_response(mapdns.intercept_addr, response.src, &result.response);
                try_write_tun_packet(tun, stats, &raw, "dns");
            }
            Err(err) => {
                let message = err.to_string();
                stats.record_dns_failure(response.host.as_deref(), &message, Some(&upstream.endpoint_label));
                match dns_cache.servfail_response(&response.query) {
                    Ok(servfail) => {
                        let raw = build_udp_response(mapdns.intercept_addr, response.src, &servfail);
                        try_write_tun_packet(tun, stats, &raw, "dns-servfail");
                    }
                    Err(servfail_err) => debug!("failed to synthesize SERVFAIL after rewrite error: {servfail_err}"),
                }
            }
        },
        Err(err) => {
            let formatted_error = response.resolver_error_kind.map(|kind| format!("{kind:?}: {err}")).unwrap_or(err);
            stats.record_dns_failure(response.host.as_deref(), &formatted_error, None);
            match dns_cache.servfail_response(&response.query) {
                Ok(servfail) => {
                    let raw = build_udp_response(mapdns.intercept_addr, response.src, &servfail);
                    try_write_tun_packet(tun, stats, &raw, "dns-servfail");
                }
                Err(servfail_err) => debug!("failed to synthesize SERVFAIL after upstream failure: {servfail_err}"),
            }
        }
    }
}

fn resolve_mapped_target(stats: &Arc<Stats>, dns_cache: &mut Option<DnsCache>, dst: SocketAddr) -> SocketAddr {
    let Some(cache) = dns_cache.as_mut() else {
        return dst;
    };
    let IpAddr::V4(v4) = dst.ip() else {
        return dst;
    };
    let Some(entry) = cache.lookup(u32::from(v4)) else {
        return dst;
    };
    stats.record_last_host(Some(&entry.host));
    SocketAddr::new(IpAddr::V4(Ipv4Addr::from(entry.real_ip)), dst.port())
}

struct UdpAssociation {
    id: u64,
    session: UdpSession,
    cancel: CancellationToken,
    last_activity: Arc<Mutex<StdInstant>>,
    worker: tokio::task::JoinHandle<()>,
}

#[derive(Debug)]
enum UdpEvent {
    Packet { src: SocketAddr, association_id: u64, raw: Vec<u8> },
    Closed { src: SocketAddr, association_id: u64 },
}

fn touch_udp_activity(last_activity: &Arc<Mutex<StdInstant>>) {
    if let Ok(mut guard) = last_activity.lock() {
        *guard = StdInstant::now();
    }
}

fn udp_association_is_idle(last_activity: &Arc<Mutex<StdInstant>>, idle_timeout: Duration) -> bool {
    last_activity.lock().map(|guard| guard.elapsed() >= idle_timeout).unwrap_or(true)
}

#[allow(clippy::too_many_arguments)]
async fn create_udp_association(
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    association_id: u64,
    idle_timeout: Duration,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let session = UdpSession::connect(proxy_addr, auth).await?.with_recv_timeout(idle_timeout);
    let last_activity = Arc::new(Mutex::new(StdInstant::now()));
    let worker_session = session.clone();
    let worker_last_activity = Arc::clone(&last_activity);
    let worker_cancel = cancel.clone();
    let worker_udp_tx = udp_tx.clone();
    let worker = tokio::spawn(async move {
        loop {
            match worker_session.recv_from(worker_cancel.clone()).await {
                Ok(Some((resp_payload, from))) => {
                    touch_udp_activity(&worker_last_activity);
                    let raw = build_udp_response(from, src, &resp_payload);
                    if raw.is_empty() {
                        continue;
                    }
                    if worker_udp_tx.send(UdpEvent::Packet { src, association_id, raw }).await.is_err() {
                        break;
                    }
                }
                Ok(None) => {
                    if worker_cancel.is_cancelled() {
                        break;
                    }
                    if udp_association_is_idle(&worker_last_activity, idle_timeout) {
                        let _ = worker_udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                        break;
                    }
                }
                Err(err) => {
                    debug!("UDP association {} for {} failed: {}", association_id, src, err);
                    let _ = worker_udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                    break;
                }
            }
        }
    });

    Ok(UdpAssociation { id: association_id, session, cancel, last_activity, worker })
}

fn handle_udp_event(
    tun: &AsyncFd<std::fs::File>,
    stats: &Arc<Stats>,
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    event: UdpEvent,
) {
    match event {
        UdpEvent::Packet { src, association_id, raw } => {
            let current_id = udp_associations.get(&src).map(|association| association.id);
            if current_id == Some(association_id) {
                try_write_tun_packet(tun, stats, &raw, "udp");
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if udp_associations.get(&src).map(|association| association.id == association_id).unwrap_or(false) {
                udp_associations.remove(&src);
            }
        }
    }
}

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

    // Tracks pending LISTEN sockets added on-demand per TCP SYN.
    // Key: TCP destination port, Value: (smoltcp SocketHandle, creation time).
    // When the socket transitions to ESTABLISHED, the entry is removed.
    // Entries older than PENDING_LISTEN_TIMEOUT are garbage-collected.
    let mut pending_listens: HashMap<u16, (SocketHandle, StdInstant)> = HashMap::new();
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

    // Handles to remove at end of Phase 4.
    let mut to_remove: Vec<SocketHandle> = Vec::new();

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
                        // On-demand LISTEN socket creation for new TCP flows.
                        if is_tcp_syn(pkt) {
                            if let Some(dst_port) = tcp_dst_port(pkt) {
                                if let std::collections::hash_map::Entry::Vacant(e) = pending_listens.entry(dst_port) {
                                    let mut sock = TcpSocket::new(
                                        tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
                                        tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
                                    );
                                    if sock.listen(dst_port).is_ok() {
                                        let h = socket_set.add(sock);
                                        e.insert((h, StdInstant::now()));
                                        debug!("Added LISTEN socket for port {}", dst_port);
                                    } else {
                                        warn!("listen({}) failed (port already bound?)", dst_port);
                                    }
                                }
                            }
                        }
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
                                                debug!("failed to synthesize unavailable-worker SERVFAIL: {err}")
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

                    if !udp_associations.contains_key(&src) {
                        let association_id = next_udp_association_id;
                        next_udp_association_id = next_udp_association_id.wrapping_add(1);
                        match create_udp_association(
                            proxy_sockaddr,
                            auth.clone(),
                            src,
                            association_id,
                            udp_idle_timeout,
                            cancel.child_token(),
                            udp_tx.clone(),
                        )
                        .await
                        {
                            Ok(association) => {
                                udp_associations.insert(src, association);
                            }
                            Err(err) => {
                                debug!("Failed to create UDP association for {}: {}", src, err);
                                continue;
                            }
                        }
                    }

                    let Some((session, last_activity)) = udp_associations
                        .get(&src)
                        .map(|association| (association.session.clone(), Arc::clone(&association.last_activity)))
                    else {
                        continue;
                    };

                    touch_udp_activity(&last_activity);
                    if let Err(err) = session.send_to(resolved_dst, &payload).await {
                        debug!("UDP association send to {} from {} failed: {}", resolved_dst, src, err);
                        if let Some(association) = udp_associations.remove(&src) {
                            association.cancel.cancel();
                        }

                        let association_id = next_udp_association_id;
                        next_udp_association_id = next_udp_association_id.wrapping_add(1);
                        match create_udp_association(
                            proxy_sockaddr,
                            auth.clone(),
                            src,
                            association_id,
                            udp_idle_timeout,
                            cancel.child_token(),
                            udp_tx.clone(),
                        )
                        .await
                        {
                            Ok(association) => {
                                let retry_session = association.session.clone();
                                touch_udp_activity(&association.last_activity);
                                udp_associations.insert(src, association);
                                if let Err(retry_err) = retry_session.send_to(resolved_dst, &payload).await {
                                    debug!(
                                        "UDP association retry to {} from {} failed: {}",
                                        resolved_dst, src, retry_err
                                    );
                                    if let Some(association) = udp_associations.remove(&src) {
                                        association.cancel.cancel();
                                    }
                                }
                            }
                            Err(recreate_err) => {
                                debug!(
                                    "Failed to recreate UDP association for {} after send error: {}",
                                    src, recreate_err
                                );
                            }
                        }
                    }
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
        if loop_iteration % PENDING_LISTEN_GC_INTERVAL == 0 {
            let now = StdInstant::now();
            pending_listens.retain(|port, (handle, created_at)| {
                if now.duration_since(*created_at) > PENDING_LISTEN_TIMEOUT {
                    debug!("GC stale LISTEN socket for port {} (age {:?})", port, now.duration_since(*created_at));
                    socket_set.remove(*handle);
                    false
                } else {
                    true
                }
            });
        }

        // ── Phase 3: detect new ESTABLISHED TCP sockets → spawn sessions ──────
        {
            // Collect newly active sockets (moved past LISTEN) not yet tracked.
            let mut new_sessions: Vec<(SocketHandle, SocketAddr)> = Vec::new();

            for (handle, socket) in socket_set.iter_mut() {
                if let Socket::Tcp(tcp) = socket {
                    if tcp.is_active() && !sessions.contains(handle) {
                        match tcp.remote_endpoint() {
                            Some(remote) => {
                                new_sessions.push((handle, endpoint_to_socketaddr(remote)));
                            }
                            None => {
                                error!("TCP socket {:?} active but remote_endpoint is None — skipped", handle);
                            }
                        }
                    }
                }
            }

            // Spawn a TcpSession for each newly active socket (Decision C).
            for (handle, remote_addr) in new_sessions {
                // Remove the LISTEN tracking entry for this port.
                let port = socket_set.get_mut::<TcpSocket>(handle).local_endpoint().map(|e| e.port).unwrap_or(0);
                pending_listens.remove(&port);

                let resolved_remote = resolve_mapped_target(&stats, &mut dns_cache, remote_addr);
                let target = TargetAddr::Ip(resolved_remote);
                let (smoltcp_side, session_side) = tokio::io::duplex(DUPLEX_BUF);
                let child_cancel = cancel.child_token();
                let session_inst = TcpSession::new(proxy_sockaddr, auth.clone(), target);
                let child_cancel_clone = child_cancel.clone();
                let jh = tokio::spawn(async move {
                    let mut session_side = session_side;
                    session_inst.run(&mut session_side, child_cancel_clone).await
                });

                let entry = SessionEntry { smoltcp_side, cancel: child_cancel, handle: jh };
                if let Some(evicted_h) = sessions.insert(handle, entry) {
                    socket_set.remove(evicted_h);
                    debug!("Evicted session socket {:?} removed from socket_set", evicted_h);
                }
                info!("TCP session spawned: remote={}", resolved_remote);
            }
        }

        // ── Phase 4: pump duplex bridges (Decision A) ─────────────────────────
        //
        // Split into two sub-phases to avoid holding borrows across `.await`:
        //   4a — synchronous: read smoltcp sockets, write session→smoltcp
        //   4b — async: write smoltcp→session_side (async write_all)
        //   4c — close sessions that have ended

        to_remove.clear();

        // 4a: synchronous pumping.
        let mut smoltcp_to_session: Vec<(SocketHandle, Vec<u8>)> = Vec::new();

        for (handle, session) in sessions.iter_mut() {
            let tcp = socket_set.get_mut::<TcpSocket>(handle);

            // smoltcp → session: read from smoltcp, buffer for async write.
            let mut tmp = [0u8; PUMP_CHUNK];
            if let Ok(n) = tcp.recv_slice(&mut tmp) {
                if n > 0 {
                    smoltcp_to_session.push((handle, tmp[..n].to_vec()));
                }
            }

            // session → smoltcp: non-blocking read from DuplexStream.
            let mut tmp2 = [0u8; PUMP_CHUNK];
            match try_read_duplex(&mut session.smoltcp_side, &mut tmp2) {
                Some(Ok(0)) => {
                    // session_side EOF → TcpSession task has exited.
                    tcp.close();
                    to_remove.push(handle);
                }
                Some(Ok(n)) => {
                    tcp.send_slice(&tmp2[..n]).ok();
                }
                Some(Err(e)) => {
                    debug!("smoltcp_side read error: {} — closing session {:?}", e, handle);
                    tcp.close();
                    to_remove.push(handle);
                }
                None => {} // no data yet
            }

            // smoltcp socket closed by remote side.
            if !tcp.is_active() && !to_remove.contains(&handle) {
                to_remove.push(handle);
            }
        }
        // sessions.iter_mut() borrow ends here.

        // 4b: async write buffered smoltcp→session data.
        for (handle, data) in smoltcp_to_session {
            if to_remove.contains(&handle) {
                continue;
            }
            if let Some(entry) = sessions.get_mut(handle) {
                if let Err(e) = entry.smoltcp_side.write_all(&data).await {
                    debug!("smoltcp_side write error: {} — closing session {:?}", e, handle);
                    to_remove.push(handle);
                }
            }
        }

        // 4c: close and remove ended sessions.
        for h in to_remove.drain(..) {
            if let Some(mut entry) = sessions.remove(h) {
                // Shutdown smoltcp_side → session_side sees EOF (belt-and-suspenders).
                entry.smoltcp_side.shutdown().await.ok();
                // Do not await the handle; session observes cancel/EOF and exits on its own.
            }
            // Also remove from socket_set and call close() if still active.
            // get_mut returns &mut TcpSocket — close it before removing.
            {
                let tcp = socket_set.get_mut::<TcpSocket>(h);
                if tcp.is_active() {
                    tcp.close();
                }
            }
            socket_set.remove(h);
        }

        // ── Phase 5: flush smoltcp tx_queue → TUN fd ─────────────────────────
        while let Some(pkt) = device.tx_queue.pop_front() {
            // Try non-blocking write; if the fd is not writable, wait once.
            loop {
                match tun.try_io(Interest::WRITABLE, |inner| {
                    let mut f = inner;
                    f.write_all(&pkt)
                }) {
                    Ok(()) => {
                        stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                        stats.rx_bytes.fetch_add(pkt.len() as u64, Ordering::Relaxed);
                        break;
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                        // Wait until writable, then retry.
                        let _ = tun.writable().await?;
                    }
                    Err(e) => {
                        warn!("TUN write error: {} (packet dropped)", e);
                        break;
                    }
                }
            }
        }

        // ── Phase 6: wait for next event ──────────────────────────────────────
        let smol_delay = iface
            .poll_delay(Instant::now(), &socket_set)
            .map(|d| Duration::from_micros(d.total_micros()))
            .unwrap_or(Duration::from_millis(DEFAULT_POLL_DELAY_MS));

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
    let handles: Vec<SocketHandle> = sessions.iter_mut().map(|(h, _)| h).collect();
    for h in handles {
        if let Some(mut entry) = sessions.remove(h) {
            entry.cancel.cancel();
            entry.smoltcp_side.shutdown().await.ok();
            // Await with a short timeout to let the session observe the cancel.
            let _ = tokio::time::timeout(Duration::from_secs(5), entry.handle).await;
        }
        socket_set.remove(h);
    }

    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
        let _ = tokio::time::timeout(Duration::from_secs(5), association.worker).await;
    }

    info!("io_loop exited cleanly");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        pkt[32] = 0x50;
        pkt[33] = 0x02; // SYN
        pkt
    }

    fn ipv6_tcp_syn(dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 60];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
        pkt[6] = 6;
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&std::net::Ipv6Addr::LOCALHOST.octets());
        pkt[24..40].copy_from_slice(&std::net::Ipv6Addr::LOCALHOST.octets());
        pkt[40..42].copy_from_slice(&12345u16.to_be_bytes());
        pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
        pkt[52] = 0x50;
        pkt[53] = 0x02;
        pkt
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
    fn tcp_dst_port_extracts_ipv6_destination_port() {
        assert_eq!(tcp_dst_port(&ipv6_tcp_syn(8443)), Some(8443));
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
}
