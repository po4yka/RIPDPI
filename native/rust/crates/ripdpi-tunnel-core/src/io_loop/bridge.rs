use std::io;
use std::pin::Pin;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::task::{Context, Poll, Waker};
use std::time::Duration;

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf};
use tracing::{debug, warn};
use tun_rs::AsyncDevice;

use crate::{ActiveSessions, Stats, TunDevice};

use super::PUMP_CHUNK;

pub(super) struct NoopWaker;

impl std::task::Wake for NoopWaker {
    fn wake(self: Arc<Self>) {}
    fn wake_by_ref(self: &Arc<Self>) {}
}

pub(super) fn try_read_duplex(stream: &mut tokio::io::DuplexStream, buf: &mut [u8]) -> Option<io::Result<usize>> {
    let waker = Waker::from(Arc::new(NoopWaker));
    let mut cx = Context::from_waker(&waker);
    let mut rb = ReadBuf::new(buf);
    match Pin::new(stream).poll_read(&mut cx, &mut rb) {
        Poll::Ready(Ok(())) => Some(Ok(rb.filled().len())),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

pub(super) fn try_write_duplex(stream: &mut tokio::io::DuplexStream, buf: &[u8]) -> Option<io::Result<usize>> {
    let waker = Waker::from(Arc::new(NoopWaker));
    let mut cx = Context::from_waker(&waker);
    match Pin::new(stream).poll_write(&mut cx, buf) {
        Poll::Ready(Ok(n)) => Some(Ok(n)),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

pub(super) fn flush_pending_to_session(
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

pub(super) fn flush_pending_to_smoltcp(tcp: &mut TcpSocket, pending: &mut Vec<u8>) -> Result<(), tcp::SendError> {
    while !pending.is_empty() {
        let sent = tcp.send_slice(pending)?;
        if sent == 0 {
            break;
        }
        pending.drain(..sent);
    }
    Ok(())
}

pub(super) fn enqueue_tun_packet(device: &mut TunDevice, raw: Vec<u8>, context: &str) {
    if raw.is_empty() {
        return;
    }
    debug!(bytes = raw.len(), "{context} response queued for tun flush");
    device.tx_queue.push_back(raw);
}

pub(super) async fn pump_active_sessions(socket_set: &mut SocketSet<'static>, sessions: &mut ActiveSessions) {
    let mut to_remove: Vec<_> = Vec::new();

    for (handle, session) in sessions.iter_mut() {
        let tcp = socket_set.get_mut::<TcpSocket>(handle);

        if let Some(Err(err)) = flush_pending_to_session(&mut session.smoltcp_side, &mut session.pending_to_session) {
            debug!("session pending flush error: {} — closing session {:?}", err, handle);
            to_remove.push(handle);
            continue;
        }

        if session.pending_to_session.is_empty() {
            let mut tmp = [0u8; PUMP_CHUNK];
            if let Ok(read) = tcp.recv_slice(&mut tmp) {
                if read > 0 {
                    debug!("read {read} bytes from smoltcp socket {:?}", handle);
                    match try_write_duplex(&mut session.smoltcp_side, &tmp[..read]) {
                        Some(Ok(0)) => {
                            debug!("session duplex stream accepted zero bytes — closing session {:?}", handle);
                            to_remove.push(handle);
                            continue;
                        }
                        Some(Ok(sent)) => {
                            debug!("wrote {sent} bytes into session duplex {:?}", handle);
                            if sent < read {
                                session.pending_to_session.extend_from_slice(&tmp[sent..read]);
                            }
                        }
                        Some(Err(err)) => {
                            debug!("smoltcp_side write error: {} — closing session {:?}", err, handle);
                            to_remove.push(handle);
                            continue;
                        }
                        None => {
                            session.pending_to_session.extend_from_slice(&tmp[..read]);
                        }
                    }
                }
            }
        }

        if let Err(err) = flush_pending_to_smoltcp(tcp, &mut session.pending_to_smoltcp) {
            debug!("smoltcp pending flush error: {} — closing session {:?}", err, handle);
            to_remove.push(handle);
            continue;
        }

        if session.upstream_closed && session.pending_to_smoltcp.is_empty() && tcp.is_open() {
            tcp.close();
        }

        if session.pending_to_smoltcp.is_empty() && !session.upstream_closed {
            let mut tmp = [0u8; PUMP_CHUNK];
            match try_read_duplex(&mut session.smoltcp_side, &mut tmp) {
                Some(Ok(0)) => {
                    debug!("session duplex reached EOF {:?}", handle);
                    session.upstream_closed = true;
                    if tcp.is_open() {
                        tcp.close();
                    }
                }
                Some(Ok(read)) => match tcp.send_slice(&tmp[..read]) {
                    Ok(sent) => {
                        debug!(
                            "read {read} bytes from session duplex and enqueued {sent} bytes to smoltcp {:?}",
                            handle
                        );
                        if sent < read {
                            session.pending_to_smoltcp.extend_from_slice(&tmp[sent..read]);
                        }
                    }
                    Err(err) => {
                        debug!("smoltcp send error: {} — closing session {:?}", err, handle);
                        to_remove.push(handle);
                        continue;
                    }
                },
                Some(Err(err)) => {
                    debug!("smoltcp_side read error: {} — closing session {:?}", err, handle);
                    to_remove.push(handle);
                    continue;
                }
                None => {}
            }
        }

        if !tcp.is_active()
            && session.pending_to_session.is_empty()
            && session.pending_to_smoltcp.is_empty()
            && !to_remove.contains(&handle)
        {
            to_remove.push(handle);
        }
    }

    for handle in to_remove.drain(..) {
        if let Some(mut entry) = sessions.remove(handle) {
            entry.smoltcp_side.shutdown().await.ok();
        }
        {
            let tcp = socket_set.get_mut::<TcpSocket>(handle);
            if tcp.is_active() {
                tcp.close();
            }
        }
        socket_set.remove(handle);
    }
}

pub(super) async fn flush_device_tx_queue(
    tun: &AsyncDevice,
    stats: &Arc<Stats>,
    device: &mut TunDevice,
) -> io::Result<()> {
    while let Some(pkt) = device.tx_queue.pop_front() {
        loop {
            match tun.try_send(&pkt) {
                Ok(_n) => {
                    stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                    stats.rx_bytes.fetch_add(pkt.len() as u64, Ordering::Relaxed);
                    break;
                }
                Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                    tun.writable().await?;
                }
                Err(err) => {
                    warn!("TUN write error: {} (packet dropped)", err);
                    break;
                }
            }
        }
    }

    Ok(())
}

pub(super) async fn shutdown_active_sessions(sessions: &mut ActiveSessions, socket_set: &mut SocketSet<'static>) {
    let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
    for handle in handles {
        if let Some(mut entry) = sessions.remove(handle) {
            entry.cancel.cancel();
            entry.smoltcp_side.shutdown().await.ok();
            let _ = tokio::time::timeout(Duration::from_secs(5), entry.handle).await;
        }
        socket_set.remove(handle);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;
    use std::net::Ipv4Addr;

    use smoltcp::iface::{Interface, SocketSet};
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};
    use smoltcp::time::Instant;
    use smoltcp::wire::IpAddress;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::task::JoinHandle;
    use tokio_util::sync::CancellationToken;

    use crate::{ActiveSessions, SessionEntry, TunDevice};

    use super::super::packet::{build_ipv4_tcp_syn_packet, checksum_sum, finalize_checksum};
    use super::super::tcp_accept::ensure_pending_listen_for_syn;
    use super::super::TCP_SOCKET_BUF;

    // ── Helpers ──────────────────────────────────────────────────────────────

    fn build_ipv4_tcp_ack_packet(
        src_ip: Ipv4Addr,
        dst_ip: Ipv4Addr,
        src_port: u16,
        dst_port: u16,
        seq: u32,
        ack: u32,
    ) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&src_ip.octets());
        pkt[16..20].copy_from_slice(&dst_ip.octets());
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[24..28].copy_from_slice(&seq.to_be_bytes());
        pkt[28..32].copy_from_slice(&ack.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x10; // ACK
        let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
        pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
        let mut sum = checksum_sum(&src_ip.octets());
        sum += checksum_sum(&dst_ip.octets());
        sum += u32::from(6u16);
        sum += u32::from((pkt.len() - 20) as u16);
        sum += checksum_sum(&pkt[20..]);
        let tcp_checksum = finalize_checksum(sum);
        pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
        pkt
    }

    fn build_ipv4_tcp_psh_packet(
        src_ip: Ipv4Addr,
        dst_ip: Ipv4Addr,
        src_port: u16,
        dst_port: u16,
        seq: u32,
        ack: u32,
        payload: &[u8],
    ) -> Vec<u8> {
        let total_len = 40 + payload.len();
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&src_ip.octets());
        pkt[16..20].copy_from_slice(&dst_ip.octets());
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[24..28].copy_from_slice(&seq.to_be_bytes());
        pkt[28..32].copy_from_slice(&ack.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x18; // PSH+ACK
        pkt[40..].copy_from_slice(payload);
        let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
        pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
        let mut sum = checksum_sum(&src_ip.octets());
        sum += checksum_sum(&dst_ip.octets());
        sum += u32::from(6u16);
        sum += u32::from((pkt.len() - 20) as u16);
        sum += checksum_sum(&pkt[20..]);
        let tcp_checksum = finalize_checksum(sum);
        pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
        pkt
    }

    fn tcp_seq_ack(pkt: &[u8]) -> (u32, u32) {
        let ihl = ((pkt[0] & 0x0f) as usize) * 4;
        let seq = u32::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5], pkt[ihl + 6], pkt[ihl + 7]]);
        let ack = u32::from_be_bytes([pkt[ihl + 8], pkt[ihl + 9], pkt[ihl + 10], pkt[ihl + 11]]);
        (seq, ack)
    }

    /// Set up a smoltcp Interface + SocketSet with a TCP socket in ESTABLISHED state.
    /// Returns (socket_set, handle, server_seq) and the TunDevice for further interaction.
    fn establish_tcp_connection(
        device: &mut TunDevice,
    ) -> (Interface, SocketSet<'static>, smoltcp::iface::SocketHandle, u32) {
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
        });
        iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("default route");
        iface.set_any_ip(true);

        let mut socket_set = SocketSet::new(vec![]);
        let mut pending_listens = std::collections::HashMap::new();

        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let target_ip = Ipv4Addr::new(127, 0, 0, 1);

        // SYN -> creates pending listen -> smoltcp accepts
        let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, 51000, 443);
        ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), device, &mut socket_set);

        // Get SYN-ACK
        let syn_ack = device.tx_queue.pop_front().expect("syn-ack from smoltcp");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);

        // ACK to complete handshake
        let ack = build_ipv4_tcp_ack_packet(client_ip, target_ip, 51000, 443, 1, server_seq + 1);
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), device, &mut socket_set);
        // Drain any ACK produced by smoltcp
        device.tx_queue.clear();

        // Get the socket handle from pending_listens
        let handle = pending_listens.values().next().map(|(h, _)| *h).expect("pending listen handle");
        let tcp = socket_set.get::<TcpSocket>(handle);
        assert_eq!(tcp.state(), tcp::State::Established, "TCP socket should be in ESTABLISHED state");

        (iface, socket_set, handle, server_seq)
    }

    // ── Pure function tests ──────────────────────────────────────────────────

    #[tokio::test]
    async fn u10_try_read_duplex_returns_data() {
        let (mut a, mut b) = tokio::io::duplex(1024);
        b.write_all(b"hello").await.unwrap();
        // Allow the write to propagate
        tokio::task::yield_now().await;

        let mut buf = [0u8; 64];
        let result = try_read_duplex(&mut a, &mut buf);
        assert!(matches!(result, Some(Ok(5))));
        assert_eq!(&buf[..5], b"hello");
    }

    #[tokio::test]
    async fn u11_try_read_duplex_pending_when_empty() {
        let (mut a, _b) = tokio::io::duplex(1024);
        let mut buf = [0u8; 64];
        let result = try_read_duplex(&mut a, &mut buf);
        assert!(result.is_none(), "expected Pending (None) when no data available");
    }

    #[tokio::test]
    async fn u12_try_read_duplex_eof_on_closed() {
        let (mut a, b) = tokio::io::duplex(1024);
        drop(b);
        // Allow the drop to propagate
        tokio::task::yield_now().await;

        let mut buf = [0u8; 64];
        let result = try_read_duplex(&mut a, &mut buf);
        assert!(matches!(result, Some(Ok(0))), "expected EOF (0 bytes) when writer is dropped");
    }

    #[tokio::test]
    async fn u13_try_write_duplex_returns_count() {
        let (mut a, _b) = tokio::io::duplex(1024);
        let result = try_write_duplex(&mut a, b"hello");
        assert!(matches!(result, Some(Ok(5))));
    }

    #[tokio::test]
    async fn u14_try_write_duplex_pending_when_full() {
        let (mut a, _b) = tokio::io::duplex(16);
        // Fill the buffer
        let big = vec![0u8; 16];
        let _ = try_write_duplex(&mut a, &big);
        // Next write should return Pending
        let result = try_write_duplex(&mut a, &[1u8]);
        assert!(result.is_none(), "expected Pending (None) when buffer is full");
    }

    #[tokio::test]
    async fn u15_flush_pending_to_session_drains_all() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        let mut pending = vec![1u8; 100];
        let result = flush_pending_to_session(&mut a, &mut pending);
        assert!(matches!(result, Some(Ok(()))));
        assert!(pending.is_empty(), "pending should be fully drained");

        let mut recv = [0u8; 100];
        let n = b.read(&mut recv).await.unwrap();
        assert_eq!(n, 100);
        assert!(recv.iter().all(|&b| b == 1));
    }

    #[tokio::test]
    async fn u16_flush_pending_to_session_partial_on_backpressure() {
        let (mut a, _b) = tokio::io::duplex(16);
        let mut pending = vec![2u8; 256];
        let result = flush_pending_to_session(&mut a, &mut pending);
        // Should return None (Pending) after partial drain, or Ok if it fit
        // With 16-byte buffer, 256 bytes won't fully fit
        assert!(result.is_none() || matches!(result, Some(Ok(()))));
        // If None, some data remains
        if result.is_none() {
            assert!(!pending.is_empty(), "pending should have remaining data when backpressured");
            assert!(pending.len() < 256, "some data should have been drained");
        }
    }

    #[tokio::test]
    async fn u17_flush_pending_to_session_write_zero_errors() {
        let (mut a, b) = tokio::io::duplex(1024);
        drop(b);
        tokio::task::yield_now().await;

        let mut pending = vec![3u8; 10];
        let result = flush_pending_to_session(&mut a, &mut pending);
        // Closed stream should produce an error
        assert!(matches!(result, Some(Err(_))));
    }

    #[test]
    fn u18_flush_pending_to_smoltcp_drains() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
        });
        iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("default route");
        iface.set_any_ip(true);

        let mut socket_set = SocketSet::new(vec![]);
        let mut pending_listens = std::collections::HashMap::new();

        let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 99), Ipv4Addr::new(127, 0, 0, 1), 51000, 443);
        ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        let ack = build_ipv4_tcp_ack_packet(
            Ipv4Addr::new(10, 0, 0, 99),
            Ipv4Addr::new(127, 0, 0, 1),
            51000,
            443,
            1,
            server_seq + 1,
        );
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        device.tx_queue.clear();

        let handle = pending_listens.values().next().map(|(h, _)| *h).expect("pending listen handle");

        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        let mut pending = vec![42u8; 100];
        let result = flush_pending_to_smoltcp(tcp, &mut pending);
        assert!(result.is_ok());
        assert!(pending.is_empty(), "pending should be fully drained into smoltcp send buffer");
    }

    #[test]
    fn u19_flush_pending_to_smoltcp_partial_on_full() {
        // Create a TCP socket with a tiny send buffer to force partial drain
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
        });
        iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("default route");
        iface.set_any_ip(true);

        let mut socket_set = SocketSet::new(vec![]);

        // Manually create a socket with tiny (32-byte) send buffer
        let mut socket =
            TcpSocket::new(tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]), tcp::SocketBuffer::new(vec![0u8; 32]));
        use super::super::tcp_accept::socketaddr_to_listen_endpoint;
        use std::net::{IpAddr, SocketAddr};
        socket
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 8443)))
            .expect("listen");
        let handle = socket_set.add(socket);

        // Drive through SYN -> SYN-ACK -> ACK handshake
        let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 99), Ipv4Addr::new(127, 0, 0, 1), 52000, 8443);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        let ack = build_ipv4_tcp_ack_packet(
            Ipv4Addr::new(10, 0, 0, 99),
            Ipv4Addr::new(127, 0, 0, 1),
            52000,
            8443,
            1,
            server_seq + 1,
        );
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        device.tx_queue.clear();

        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        assert_eq!(tcp.state(), tcp::State::Established);

        let mut pending = vec![7u8; 256];
        let result = flush_pending_to_smoltcp(tcp, &mut pending);
        // With a 32-byte send buffer, 256 bytes can't all fit -- send_slice returns 0 -> break
        assert!(result.is_ok());
        assert!(!pending.is_empty(), "with 32-byte send buffer, not all 256 bytes should fit");
    }

    #[test]
    fn u20_enqueue_tun_packet_adds_to_queue() {
        let mut device = TunDevice::new(1500);
        enqueue_tun_packet(&mut device, vec![10, 20, 30], "test");
        assert_eq!(device.tx_queue.len(), 1);
        assert_eq!(device.tx_queue.front().unwrap(), &vec![10, 20, 30]);
    }

    #[test]
    fn u21_enqueue_tun_packet_ignores_empty() {
        let mut device = TunDevice::new(1500);
        enqueue_tun_packet(&mut device, vec![], "test");
        assert!(device.tx_queue.is_empty(), "empty packet should not be enqueued");
    }

    // ── Session pump tests ───────────────────────────────────────────────────

    #[tokio::test]
    async fn u22_pump_forwards_smoltcp_to_session() {
        let mut device = TunDevice::new(1500);
        let (mut iface, mut socket_set, handle, server_seq) = establish_tcp_connection(&mut device);

        let cancel = CancellationToken::new();
        let (smoltcp_side, mut session_side) = tokio::io::duplex(super::super::DUPLEX_BUF);
        let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
        let entry = SessionEntry {
            smoltcp_side,
            cancel,
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
        };
        let mut sessions = ActiveSessions::new(8);
        sessions.insert(handle, entry);

        // Inject a PSH+ACK data packet from client into smoltcp
        let payload = b"hello from client";
        let psh = build_ipv4_tcp_psh_packet(
            Ipv4Addr::new(10, 0, 0, 99),
            Ipv4Addr::new(127, 0, 0, 1),
            51000,
            443,
            1,
            server_seq + 1,
            payload,
        );
        device.rx_queue.push_back(psh);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // Pump should forward data from smoltcp TCP recv buffer to session duplex
        pump_active_sessions(&mut socket_set, &mut sessions).await;

        // Read from session side
        let mut buf = [0u8; 64];
        // The data should be available immediately or after a short yield
        tokio::task::yield_now().await;
        let result = try_read_duplex(&mut session_side, &mut buf);
        match result {
            Some(Ok(n)) => {
                assert_eq!(&buf[..n], payload);
            }
            other => {
                // Data may be in pending_to_session if duplex was not immediately ready
                let entry = sessions.get_mut(handle).expect("session still exists");
                assert!(
                    !entry.pending_to_session.is_empty() || other.is_some(),
                    "data should have been forwarded to session or buffered in pending"
                );
            }
        }

        // Cleanup
        let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
        for h in handles {
            if let Some(entry) = sessions.remove(h) {
                entry.cancel.cancel();
                entry.handle.abort();
            }
            socket_set.remove(h);
        }
    }

    #[tokio::test]
    async fn u23_pump_forwards_session_to_smoltcp() {
        let mut device = TunDevice::new(1500);
        let (mut iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

        let cancel = CancellationToken::new();
        let (smoltcp_side, mut session_side) = tokio::io::duplex(super::super::DUPLEX_BUF);
        let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
        let entry = SessionEntry {
            smoltcp_side,
            cancel,
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
        };
        let mut sessions = ActiveSessions::new(8);
        sessions.insert(handle, entry);

        // Write data from session side into duplex
        session_side.write_all(b"response data").await.unwrap();
        tokio::task::yield_now().await;

        // Pump should forward from session duplex into smoltcp send buffer
        pump_active_sessions(&mut socket_set, &mut sessions).await;

        // Poll smoltcp to produce the TCP packet
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // The device tx_queue should now have a TCP packet containing our data
        assert!(!device.tx_queue.is_empty(), "smoltcp should have produced a TCP data packet");

        // Cleanup
        let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
        for h in handles {
            if let Some(entry) = sessions.remove(h) {
                entry.cancel.cancel();
                entry.handle.abort();
            }
            socket_set.remove(h);
        }
    }

    #[tokio::test]
    async fn u24_pump_removes_closed_session() {
        let mut device = TunDevice::new(1500);
        let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

        let cancel = CancellationToken::new();
        let (smoltcp_side, session_side) = tokio::io::duplex(super::super::DUPLEX_BUF);
        let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
        let entry = SessionEntry {
            smoltcp_side,
            cancel,
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
        };
        let mut sessions = ActiveSessions::new(8);
        sessions.insert(handle, entry);

        // Drop session side to cause EOF
        drop(session_side);
        tokio::task::yield_now().await;

        // Pump should detect the closed duplex
        pump_active_sessions(&mut socket_set, &mut sessions).await;

        // The session should have upstream_closed set to true, or be removed entirely
        // depending on whether the TCP socket is still active
        if let Some(entry) = sessions.get_mut(handle) {
            assert!(entry.upstream_closed, "upstream_closed should be set when session side is dropped");
        }
        // If session was removed, that's also valid
    }

    #[tokio::test]
    async fn u25_pump_handles_partial_writes() {
        let mut device = TunDevice::new(1500);
        let (mut iface, mut socket_set, handle, server_seq) = establish_tcp_connection(&mut device);

        let cancel = CancellationToken::new();
        // Use a tiny duplex buffer to force backpressure
        let (smoltcp_side, _session_side) = tokio::io::duplex(8);
        let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
        let entry = SessionEntry {
            smoltcp_side,
            cancel,
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
        };
        let mut sessions = ActiveSessions::new(8);
        sessions.insert(handle, entry);

        // Inject a large payload into smoltcp to exceed the tiny duplex buffer
        let payload = vec![0xAB; 128];
        let psh = build_ipv4_tcp_psh_packet(
            Ipv4Addr::new(10, 0, 0, 99),
            Ipv4Addr::new(127, 0, 0, 1),
            51000,
            443,
            1,
            server_seq + 1,
            &payload,
        );
        device.rx_queue.push_back(psh);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // Pump -- the tiny buffer should cause pending_to_session accumulation
        pump_active_sessions(&mut socket_set, &mut sessions).await;

        // The session should still exist (not errored out) -- data is either
        // in pending_to_session or was partially written
        assert!(sessions.contains(handle), "session should survive backpressure");

        // Cleanup
        let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
        for h in handles {
            if let Some(entry) = sessions.remove(h) {
                entry.cancel.cancel();
                entry.handle.abort();
            }
            socket_set.remove(h);
        }
    }

    #[tokio::test]
    async fn u26_pump_upstream_closed_closes_tcp() {
        let mut device = TunDevice::new(1500);
        let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

        let cancel = CancellationToken::new();
        let (smoltcp_side, _session_side) = tokio::io::duplex(super::super::DUPLEX_BUF);
        let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
        let entry = SessionEntry {
            smoltcp_side,
            cancel,
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: true, // Simulate upstream already closed
        };
        let mut sessions = ActiveSessions::new(8);
        sessions.insert(handle, entry);

        // Pump should call tcp.close() since upstream_closed and pending empty
        pump_active_sessions(&mut socket_set, &mut sessions).await;

        // Check that the TCP socket was closed (state should transition from Established)
        // The socket might be removed or in a closing state
        if socket_set.iter().any(|(h, _)| h == handle) {
            let tcp = socket_set.get::<TcpSocket>(handle);
            assert_ne!(
                tcp.state(),
                tcp::State::Established,
                "TCP should no longer be in Established state after upstream close"
            );
        }

        // Cleanup
        let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
        for h in handles {
            if let Some(entry) = sessions.remove(h) {
                entry.cancel.cancel();
                entry.handle.abort();
            }
        }
    }

    #[tokio::test]
    async fn u27_shutdown_cancels_all() {
        let mut device = TunDevice::new(1500);
        let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

        let cancel = CancellationToken::new();
        let child_cancel = cancel.child_token();
        let (smoltcp_side, _session_side) = tokio::io::duplex(super::super::DUPLEX_BUF);
        let session_cancel = child_cancel.clone();
        let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async move {
            session_cancel.cancelled().await;
            Ok(())
        });
        let entry = SessionEntry {
            smoltcp_side,
            cancel: child_cancel.clone(),
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
        };
        let mut sessions = ActiveSessions::new(8);
        sessions.insert(handle, entry);

        assert!(!child_cancel.is_cancelled());

        shutdown_active_sessions(&mut sessions, &mut socket_set).await;

        assert!(child_cancel.is_cancelled(), "session cancel token must be cancelled after shutdown");
        assert!(sessions.is_empty(), "all sessions should be removed after shutdown");
    }
}
