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
use smoltcp::wire::{IpAddress, IpListenEndpoint};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, Interest, ReadBuf};
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, info, warn};

use crate::dns_cache::DnsCache;
use crate::session::{Auth, TargetAddr, TcpSession, UdpSession};
use ripdpi_dns_resolver::{
    EncryptedDnsEndpoint, EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsProtocol,
    EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_tunnel_config::Config;

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

fn try_write_duplex(stream: &mut tokio::io::DuplexStream, buf: &[u8]) -> Option<io::Result<usize>> {
    let waker = Waker::from(Arc::new(NoopWaker));
    let mut cx = Context::from_waker(&waker);
    match Pin::new(stream).poll_write(&mut cx, buf) {
        Poll::Ready(Ok(n)) => Some(Ok(n)),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

fn flush_pending_to_session(
    stream: &mut tokio::io::DuplexStream,
    pending: &mut Vec<u8>,
) -> Option<io::Result<()>> {
    while !pending.is_empty() {
        match try_write_duplex(stream, pending) {
            Some(Ok(0)) => {
                return Some(Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "session duplex stream accepted zero bytes",
                )));
            }
            Some(Ok(sent)) => {
                pending.drain(..sent);
            }
            Some(Err(e)) => return Some(Err(e)),
            None => return None,
        }
    }
    Some(Ok(()))
}

fn flush_pending_to_smoltcp(tcp: &mut TcpSocket, pending: &mut Vec<u8>) -> Result<(), tcp::SendError> {
    while !pending.is_empty() {
        let sent = tcp.send_slice(pending)?;
        if sent == 0 {
            break;
        }
        pending.drain(..sent);
    }
    Ok(())
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

fn tcp_packet_endpoints(pkt: &[u8]) -> Option<(SocketAddr, SocketAddr)> {
    match pkt.first().map(|value| value >> 4) {
        Some(4) => {
            let offset = ipv4_transport_offset(pkt, 6)?;
            if pkt.len() < offset + 4 {
                return None;
            }
            let src_ip = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
            let dst_ip = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
            let src_port = u16::from_be_bytes([pkt[offset], pkt[offset + 1]]);
            let dst_port = u16::from_be_bytes([pkt[offset + 2], pkt[offset + 3]]);
            Some((SocketAddr::new(IpAddr::V4(src_ip), src_port), SocketAddr::new(IpAddr::V4(dst_ip), dst_port)))
        }
        Some(6) => {
            let offset = ipv6_transport_offset(pkt, 6)?;
            if pkt.len() < offset + 4 {
                return None;
            }
            let mut src_ip = [0u8; 16];
            src_ip.copy_from_slice(&pkt[8..24]);
            let mut dst_ip = [0u8; 16];
            dst_ip.copy_from_slice(&pkt[24..40]);
            let src_port = u16::from_be_bytes([pkt[offset], pkt[offset + 1]]);
            let dst_port = u16::from_be_bytes([pkt[offset + 2], pkt[offset + 3]]);
            Some((
                SocketAddr::new(IpAddr::V6(src_ip.into()), src_port),
                SocketAddr::new(IpAddr::V6(dst_ip.into()), dst_port),
            ))
        }
        _ => None,
    }
}

/// Extract the TCP destination port from a raw IPv4 or IPv6 packet, or `None`
/// if the packet is not a plain TCP transport packet.
#[cfg(test)]
fn tcp_dst_port(pkt: &[u8]) -> Option<u16> {
    Some(tcp_packet_endpoints(pkt)?.1.port())
}

/// Return `true` if the raw IPv4 or IPv6 packet is a pure TCP SYN
/// (SYN=1, ACK=0).
fn is_tcp_syn(pkt: &[u8]) -> bool {
    let Some(offset) = tcp_header_offset(pkt) else {
        return false;
    };
    pkt[offset + 13] & 0x12 == 0x02
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct TcpFlowKey {
    src: SocketAddr,
    dst: SocketAddr,
}

fn tcp_syn_flow_key(pkt: &[u8]) -> Option<TcpFlowKey> {
    if !is_tcp_syn(pkt) {
        return None;
    }
    let (src, dst) = tcp_packet_endpoints(pkt)?;
    Some(TcpFlowKey { src, dst })
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

#[cfg(test)]
fn icmpv6_checksum(src_ip: [u8; 16], dst_ip: [u8; 16], payload: &[u8]) -> u16 {
    let payload_len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (payload_len >> 16) + (payload_len & 0xFFFF);
    sum += u32::from(58u16);
    sum += checksum_sum(payload);
    finalize_checksum(sum)
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

#[cfg(test)]
fn build_udp_port_unreachable(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    const QUOTED_UDP_PAYLOAD_LEN: usize = 8;

    let original = build_udp_response(src, dst, payload);
    if original.is_empty() {
        return Vec::new();
    }

    match (src, dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let quoted_len = original.len().min(20 + 8 + QUOTED_UDP_PAYLOAD_LEN);
            let icmp_len = 8usize + quoted_len;
            let total_len = 20usize + icmp_len;
            let total_len_u16 = match u16::try_from(total_len) {
                Ok(value) => value,
                Err(_) => return Vec::new(),
            };
            let mut pkt = vec![0u8; total_len];
            let outer_src = dst.ip().octets();
            let outer_dst = src.ip().octets();

            pkt[0] = 0x45;
            pkt[2..4].copy_from_slice(&total_len_u16.to_be_bytes());
            pkt[8] = 64;
            pkt[9] = 1;
            pkt[12..16].copy_from_slice(&outer_src);
            pkt[16..20].copy_from_slice(&outer_dst);

            pkt[20] = 3;
            pkt[21] = 3;
            pkt[28..28 + quoted_len].copy_from_slice(&original[..quoted_len]);

            let icmp_checksum = finalize_checksum(checksum_sum(&pkt[20..]));
            pkt[22..24].copy_from_slice(&icmp_checksum.to_be_bytes());

            let header_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
            pkt[10..12].copy_from_slice(&header_checksum.to_be_bytes());

            pkt
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let quoted_len = original.len().min(40 + 8 + QUOTED_UDP_PAYLOAD_LEN);
            let icmp_len = 8usize + quoted_len;
            let icmp_len_u16 = match u16::try_from(icmp_len) {
                Ok(value) => value,
                Err(_) => return Vec::new(),
            };
            let mut pkt = vec![0u8; 40 + icmp_len];
            let outer_src = dst.ip().octets();
            let outer_dst = src.ip().octets();

            pkt[0] = 0x60;
            pkt[4..6].copy_from_slice(&icmp_len_u16.to_be_bytes());
            pkt[6] = 58;
            pkt[7] = 64;
            pkt[8..24].copy_from_slice(&outer_src);
            pkt[24..40].copy_from_slice(&outer_dst);

            pkt[40] = 1;
            pkt[41] = 4;
            pkt[48..48 + quoted_len].copy_from_slice(&original[..quoted_len]);

            let icmp_checksum = icmpv6_checksum(outer_src, outer_dst, &pkt[40..]);
            pkt[42..44].copy_from_slice(&icmp_checksum.to_be_bytes());

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

fn tcp_target_endpoint(tcp: &TcpSocket) -> Option<SocketAddr> {
    tcp.local_endpoint().map(endpoint_to_socketaddr)
}

fn tcp_session_target_addr(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    tcp: &TcpSocket,
) -> Option<SocketAddr> {
    tcp_target_endpoint(tcp).map(|target| resolve_mapped_target(stats, dns_cache, target))
}

fn socketaddr_to_listen_endpoint(addr: SocketAddr) -> IpListenEndpoint {
    let ip = match addr.ip() {
        IpAddr::V4(v4) => {
            let [a, b, c, d] = v4.octets();
            IpAddress::v4(a, b, c, d)
        }
        IpAddr::V6(v6) => {
            let [a, b, c, d, e, f, g, h] = v6.segments();
            IpAddress::v6(a, b, c, d, e, f, g, h)
        }
    };
    IpListenEndpoint { addr: Some(ip), port: addr.port() }
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
            if udp_associations.get(&src).is_some_and(|association| association.id == association_id) {
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

    // Tracks pending LISTEN sockets added on-demand per TCP flow.
    // Key: observed SYN flow tuple, Value: (smoltcp SocketHandle, creation time).
    // Retransmitted SYNs reuse the same entry, while concurrent HTTPS flows are
    // allowed to allocate independent LISTEN sockets even on the same dst port.
    let mut pending_listens: HashMap<TcpFlowKey, (SocketHandle, StdInstant)> = HashMap::new();
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
                        if let Some(flow_key) = tcp_syn_flow_key(pkt) {
                            if let std::collections::hash_map::Entry::Vacant(e) = pending_listens.entry(flow_key) {
                                let mut sock = TcpSocket::new(
                                    tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
                                    tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
                                );
                                if sock.listen(socketaddr_to_listen_endpoint(flow_key.dst)).is_ok() {
                                    let h = socket_set.add(sock);
                                    e.insert((h, StdInstant::now()));
                                    debug!("Added LISTEN socket for flow {} -> {}", flow_key.src, flow_key.dst);
                                } else {
                                    warn!(
                                        "listen({}) failed for flow {} -> {}",
                                        flow_key.dst.port(),
                                        flow_key.src,
                                        flow_key.dst
                                    );
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

                    #[allow(clippy::map_entry)] // async creation prevents entry API
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
        if loop_iteration.is_multiple_of(PENDING_LISTEN_GC_INTERVAL) {
            let now = StdInstant::now();
            pending_listens.retain(|flow_key, (handle, created_at)| {
                if now.duration_since(*created_at) > PENDING_LISTEN_TIMEOUT {
                    debug!(
                        "GC stale LISTEN socket for flow {} -> {} (age {:?})",
                        flow_key.src,
                        flow_key.dst,
                        now.duration_since(*created_at)
                    );
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
                        match tcp_session_target_addr(&stats, &mut dns_cache, tcp) {
                            Some(target) => {
                                new_sessions.push((handle, target));
                            }
                            None => {
                                error!("TCP socket {:?} active but local_endpoint is None — skipped", handle);
                            }
                        }
                    }
                }
            }

            // Spawn a TcpSession for each newly active socket (Decision C).
            for (handle, target_addr) in new_sessions {
                // Remove the LISTEN tracking entry for this socket handle.
                let pending_key = pending_listens
                    .iter()
                    .find_map(|(key, (pending_handle, _))| (*pending_handle == handle).then_some(*key));
                if let Some(pending_key) = pending_key {
                    pending_listens.remove(&pending_key);
                }

                let target = TargetAddr::Ip(target_addr);
                let (smoltcp_side, session_side) = tokio::io::duplex(DUPLEX_BUF);
                let child_cancel = cancel.child_token();
                let session_inst = TcpSession::new(proxy_sockaddr, auth.clone(), target);
                let child_cancel_clone = child_cancel.clone();
                let jh = tokio::spawn(async move {
                    let mut session_side = session_side;
                    session_inst.run(&mut session_side, child_cancel_clone).await
                });

                let entry =
                    SessionEntry {
                        smoltcp_side,
                        cancel: child_cancel,
                        handle: jh,
                        pending_to_session: Vec::new(),
                        pending_to_smoltcp: Vec::new(),
                        upstream_closed: false,
                    };
                if let Some(evicted_h) = sessions.insert(handle, entry) {
                    socket_set.remove(evicted_h);
                    debug!("Evicted session socket {:?} removed from socket_set", evicted_h);
                }
                info!("TCP session spawned: remote={}", target_addr);
            }
        }

        // ── Phase 4: pump duplex bridges (Decision A) ─────────────────────────
        //
        // Split into two sub-phases to avoid holding borrows across `.await`:
        //   4a — synchronous: read smoltcp sockets, write session→smoltcp
        //   4b — async: write smoltcp→session_side (async write_all)
        //   4c — close sessions that have ended

        to_remove.clear();

        for (handle, session) in sessions.iter_mut() {
            let tcp = socket_set.get_mut::<TcpSocket>(handle);

            if let Some(result) =
                flush_pending_to_session(&mut session.smoltcp_side, &mut session.pending_to_session)
            {
                if let Err(e) = result {
                    debug!("session pending flush error: {} — closing session {:?}", e, handle);
                    to_remove.push(handle);
                    continue;
                }
            }

            if session.pending_to_session.is_empty() {
                // smoltcp → session: read from smoltcp and push to the session
                // duplex stream without stalling the entire io loop.
                let mut tmp = [0u8; PUMP_CHUNK];
                if let Ok(n) = tcp.recv_slice(&mut tmp) {
                    if n > 0 {
                        match try_write_duplex(&mut session.smoltcp_side, &tmp[..n]) {
                            Some(Ok(0)) => {
                                debug!("session duplex stream accepted zero bytes — closing session {:?}", handle);
                                to_remove.push(handle);
                                continue;
                            }
                            Some(Ok(sent)) => {
                                if sent < n {
                                    session.pending_to_session.extend_from_slice(&tmp[sent..n]);
                                }
                            }
                            Some(Err(e)) => {
                                debug!("smoltcp_side write error: {} — closing session {:?}", e, handle);
                                to_remove.push(handle);
                                continue;
                            }
                            None => {
                                session.pending_to_session.extend_from_slice(&tmp[..n]);
                            }
                        }
                    }
                }
            }

            if let Err(e) = flush_pending_to_smoltcp(tcp, &mut session.pending_to_smoltcp) {
                debug!("smoltcp pending flush error: {} — closing session {:?}", e, handle);
                to_remove.push(handle);
                continue;
            }

            if session.upstream_closed && session.pending_to_smoltcp.is_empty() && tcp.is_open() {
                tcp.close();
            }

            if session.pending_to_smoltcp.is_empty() && !session.upstream_closed {
                // session → smoltcp: non-blocking read from DuplexStream.
                let mut tmp2 = [0u8; PUMP_CHUNK];
                match try_read_duplex(&mut session.smoltcp_side, &mut tmp2) {
                    Some(Ok(0)) => {
                        // session_side EOF → TcpSession task has finished sending bytes to the client.
                        session.upstream_closed = true;
                        if tcp.is_open() {
                            tcp.close();
                        }
                    }
                    Some(Ok(n)) => match tcp.send_slice(&tmp2[..n]) {
                        Ok(sent) => {
                            if sent < n {
                                session.pending_to_smoltcp.extend_from_slice(&tmp2[sent..n]);
                            }
                        }
                        Err(e) => {
                            debug!("smoltcp send error: {} — closing session {:?}", e, handle);
                            to_remove.push(handle);
                            continue;
                        }
                    },
                    Some(Err(e)) => {
                        debug!("smoltcp_side read error: {} — closing session {:?}", e, handle);
                        to_remove.push(handle);
                        continue;
                    }
                    None => {} // no data yet
                }
            }

            // smoltcp socket closed by remote side.
            if !tcp.is_active() &&
                session.pending_to_session.is_empty() &&
                session.pending_to_smoltcp.is_empty() &&
                !to_remove.contains(&handle)
            {
                to_remove.push(handle);
            }
        }
        // sessions.iter_mut() borrow ends here.

        // 4b: close and remove ended sessions.
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
    use std::net::Ipv6Addr;

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
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)),
                443,
            )))
            .expect("first listener");
        second
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)),
                443,
            )))
            .expect("second listener");

        let first_handle = socket_set.add(first);
        let second_handle = socket_set.add(second);

        let syn = build_ipv4_tcp_syn_packet(
            Ipv4Addr::new(10, 0, 0, 1),
            Ipv4Addr::new(203, 0, 113, 20),
            51000,
            443,
        );
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
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)),
                443,
            )))
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

        assert_eq!(
            socket.remote_endpoint().map(endpoint_to_socketaddr),
            Some(client),
        );
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
            addrs
                .push(smoltcp::wire::IpCidr::new(IpAddress::v6(a, b, c, d, e, f, g, h), 128))
                .unwrap();
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
        socket
            .listen(socketaddr_to_listen_endpoint(destination))
            .expect("listener");

        let handle = socket_set.add(socket);
        device.rx_queue.push_back(build_ipv6_tcp_syn_packet(client_ip, destination_ip, 51000, 443));

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let socket = socket_set.get::<TcpSocket>(handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let target = tcp_session_target_addr(&stats, &mut dns_cache, &socket).expect("session target");

        assert_eq!(
            socket.remote_endpoint().map(endpoint_to_socketaddr),
            Some(client),
        );
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

}
