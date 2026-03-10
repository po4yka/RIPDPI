//! Core tunnel event loop — io_loop_task.
//!
//! Implements the 6-phase loop from spec.md v2.  One tokio task drives the
//! entire smoltcp TCP/IP stack and bridges sessions to/from the SOCKS5 proxy.

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::task::{Context, Poll, Waker};
use std::time::Duration;

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
fn try_read_duplex(
    stream: &mut tokio::io::DuplexStream,
    buf: &mut [u8],
) -> Option<io::Result<usize>> {
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

/// Extract the TCP destination port from a raw IPv4 packet, or `None` if not TCP.
fn tcp_dst_port(pkt: &[u8]) -> Option<u16> {
    if pkt.len() < 20 || pkt[0] >> 4 != 4 || pkt[9] != 6 {
        return None;
    }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if pkt.len() < ihl + 4 {
        return None;
    }
    Some(u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]))
}

/// Return `true` if the raw IPv4 packet is a pure TCP SYN (SYN=1, ACK=0).
fn is_tcp_syn(pkt: &[u8]) -> bool {
    if pkt.len() < 20 || pkt[9] != 6 {
        return false;
    }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if pkt.len() < ihl + 14 {
        return false;
    }
    pkt[ihl + 13] & 0x12 == 0x02 // SYN=1, ACK=0
}

/// Build a raw IPv4/UDP packet for a DNS response.
///
/// `src` — the mapdns address (e.g. 198.18.0.0:53)
/// `dst` — the original query source (the TUN client)
fn build_udp_response(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let (src_ip, dst_ip) = match (src.ip(), dst.ip()) {
        (IpAddr::V4(s), IpAddr::V4(d)) => (s.octets(), d.octets()),
        _ => return Vec::new(), // IPv6 DNS intercept not implemented
    };
    let udp_len = (8 + payload.len()) as u16;
    let total_len = (20 + 8 + payload.len()) as u16;
    let mut pkt = vec![0u8; total_len as usize];
    // IPv4 header
    pkt[0] = 0x45;
    pkt[2] = (total_len >> 8) as u8;
    pkt[3] = total_len as u8;
    pkt[8] = 64; // TTL
    pkt[9] = 17; // UDP
    pkt[12..16].copy_from_slice(&src_ip);
    pkt[16..20].copy_from_slice(&dst_ip);
    // UDP header
    let sp = src.port().to_be_bytes();
    let dp = dst.port().to_be_bytes();
    pkt[20..22].copy_from_slice(&sp);
    pkt[22..24].copy_from_slice(&dp);
    pkt[24..26].copy_from_slice(&(udp_len).to_be_bytes());
    // payload
    pkt[28..28 + payload.len()].copy_from_slice(payload);
    pkt
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
        (Some(u), Some(p)) => Auth::UserPass {
            username: u.clone(),
            password: p.clone(),
        },
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

// ── UDP session helper ────────────────────────────────────────────────────────

/// Spawn a fire-and-forget UDP relay task.
///
/// On response the task builds a raw IP/UDP packet and sends it via `udp_tx`
/// so the io_loop can write it back to TUN.
#[allow(clippy::too_many_arguments)]
fn spawn_udp_session(
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    dst: SocketAddr,
    payload: Vec<u8>,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    stats: &Arc<Stats>,
) {
    let stats = Arc::clone(stats);
    tokio::spawn(async move {
        let session = UdpSession::new(proxy_addr, auth);
        match session.relay_once(dst, &payload, cancel).await {
            Ok(Some((resp_payload, from))) => {
                let raw = build_udp_response(from, src, &resp_payload);
                if !raw.is_empty() {
                    stats
                        .rx_packets
                        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    stats
                        .rx_bytes
                        .fetch_add(raw.len() as u64, std::sync::atomic::Ordering::Relaxed);
                    let _ = udp_tx.send(raw).await;
                }
            }
            Ok(None) => {
                debug!("UDP relay to {} timed out or cancelled", dst);
            }
            Err(e) => {
                debug!("UDP relay to {} error: {}", dst, e);
            }
        }
    });
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

    // Mapdns parameters (Decision B).
    let (mapdns_net, mapdns_mask, mapdns_port, mapdns_active) = if let Some(m) = &config.mapdns {
        let net = m
            .address
            .parse::<Ipv4Addr>()
            .map(u32::from)
            .unwrap_or(0xC612_0000);
        let mask = m
            .netmask
            .as_deref()
            .and_then(|s| s.parse::<Ipv4Addr>().ok())
            .map(u32::from)
            .unwrap_or(0xFFFE_0000);
        (net, mask, m.port, m.cache_size > 0)
    } else {
        (0, 0, 53, false)
    };

    let max_sessions = config.misc.max_session_count as usize;

    // Tracks pending LISTEN sockets added on-demand per TCP SYN.
    // Key: TCP destination port, Value: smoltcp SocketHandle.
    // When the socket transitions to ESTABLISHED, the entry is removed.
    let mut pending_listens: HashMap<u16, SocketHandle> = HashMap::new();

    // Channel for UDP session tasks to return raw IP packets to write to TUN.
    let (udp_tx, mut udp_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(256);

    // Read buffer — sized for max MTU + overhead.
    let mtu = config.tunnel.mtu as usize;
    let mut buf = vec![0u8; mtu + 64];

    // Handles to remove at end of Phase 4.
    let mut to_remove: Vec<SocketHandle> = Vec::new();

    info!(
        "io_loop started (proxy={}, max_sessions={})",
        proxy_sockaddr, max_sessions
    );

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
            match classify_ip_packet(pkt, mapdns_net, mapdns_mask, mapdns_port) {
                IpClass::TcpOrOther => {
                    // On-demand LISTEN socket creation for new TCP flows.
                    if is_tcp_syn(pkt) {
                        if let Some(dst_port) = tcp_dst_port(pkt) {
                            if let std::collections::hash_map::Entry::Vacant(e) =
                                pending_listens.entry(dst_port)
                            {
                                let mut sock = TcpSocket::new(
                                    tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
                                    tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
                                );
                                if sock.listen(dst_port).is_ok() {
                                    let h = socket_set.add(sock);
                                    e.insert(h);
                                    debug!("Added LISTEN socket for port {}", dst_port);
                                } else {
                                    warn!("listen({}) failed (port already bound?)", dst_port);
                                }
                            }
                        }
                    }
                    device.rx_queue.push_back(pkt.to_vec());
                }

                IpClass::UdpDns { src, payload } if mapdns_active => {
                    // Decision B: DNS intercept — handle via DnsCache.
                    if let Some(cache) = dns_cache.as_mut() {
                        let mut req = payload.clone();
                        let mut res = vec![0u8; 512];
                        match cache.handle(&mut req, &mut res) {
                            Ok(n) => {
                                let mapdns_addr = SocketAddr::new(
                                    IpAddr::V4(Ipv4Addr::from(mapdns_net)),
                                    mapdns_port,
                                );
                                let resp_pkt = build_udp_response(mapdns_addr, src, &res[..n]);
                                if !resp_pkt.is_empty() {
                                    // Best-effort write — silently ignore errors.
                                    if let Err(e) = tun.try_io(Interest::WRITABLE, |inner| {
                                        let mut f = inner;
                                        f.write_all(&resp_pkt)
                                    }) {
                                        debug!("DNS response write would-block (dropped): {:?}", e);
                                    }
                                }
                            }
                            Err(e) => {
                                debug!("DnsCache::handle error: {:?} (packet dropped)", e);
                            }
                        }
                    }
                }

                IpClass::UdpDns { src, payload } => {
                    // mapdns_active is false: relay as regular UDP toward port 53.
                    let dns_dst =
                        SocketAddr::new(IpAddr::V4(Ipv4Addr::from(mapdns_net)), mapdns_port);
                    spawn_udp_session(
                        proxy_sockaddr,
                        auth.clone(),
                        src,
                        dns_dst,
                        payload,
                        cancel.child_token(),
                        udp_tx.clone(),
                        &stats,
                    );
                }

                IpClass::Udp { src, dst, payload } => {
                    spawn_udp_session(
                        proxy_sockaddr,
                        auth.clone(),
                        src,
                        dst,
                        payload,
                        cancel.child_token(),
                        udp_tx.clone(),
                        &stats,
                    );
                }
            }
        }

        // ── Phase 2: advance smoltcp state machines ───────────────────────────
        iface.poll(Instant::now(), &mut device, &mut socket_set);

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
                                error!(
                                    "TCP socket {:?} active but remote_endpoint is None — skipped",
                                    handle
                                );
                            }
                        }
                    }
                }
            }

            // Spawn a TcpSession for each newly active socket (Decision C).
            for (handle, remote_addr) in new_sessions {
                // Remove the LISTEN tracking entry for this port.
                let port = socket_set
                    .get_mut::<TcpSocket>(handle)
                    .local_endpoint()
                    .map(|e| e.port)
                    .unwrap_or(0);
                pending_listens.remove(&port);

                let target = TargetAddr::Ip(remote_addr);
                let (smoltcp_side, session_side) = tokio::io::duplex(DUPLEX_BUF);
                let child_cancel = cancel.child_token();
                let session_inst = TcpSession::new(proxy_sockaddr, auth.clone(), target);
                let child_cancel_clone = child_cancel.clone();
                let jh = tokio::spawn(async move {
                    let mut session_side = session_side;
                    session_inst
                        .run(&mut session_side, child_cancel_clone)
                        .await
                });

                let entry = SessionEntry {
                    smoltcp_side,
                    cancel: child_cancel,
                    handle: jh,
                };
                sessions.insert(handle, entry);
                info!("TCP session spawned: remote={}", remote_addr);
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
                    debug!(
                        "smoltcp_side read error: {} — closing session {:?}",
                        e, handle
                    );
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
                    debug!(
                        "smoltcp_side write error: {} — closing session {:?}",
                        e, handle
                    );
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
                        stats
                            .rx_bytes
                            .fetch_add(pkt.len() as u64, Ordering::Relaxed);
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
        while let Ok(raw_pkt) = udp_rx.try_recv() {
            if let Err(e) = tun.try_io(Interest::WRITABLE, |inner| {
                let mut f = inner;
                f.write_all(&raw_pkt)
            }) {
                debug!("UDP response TUN write error: {:?}", e);
            }
        }

        tokio::select! {
            _ = tun.readable() => {},
            _ = tokio::time::sleep(smol_delay) => {},
            _ = udp_rx.recv() => {}, // wake up when a UDP response is ready
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

    info!("io_loop exited cleanly");
    Ok(())
}
