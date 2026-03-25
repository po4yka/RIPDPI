use std::io::{self, Write};
use std::pin::Pin;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::task::{Context, Poll, Waker};
use std::time::Duration;

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, Interest, ReadBuf};
use tracing::{debug, warn};

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

pub(super) fn try_write_tun_packet(tun: &AsyncFd<std::fs::File>, stats: &Arc<Stats>, raw: &[u8], context: &str) {
    if raw.is_empty() {
        return;
    }

    match tun.try_io(Interest::WRITABLE, |inner| {
        let mut file = inner;
        file.write_all(raw)
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
    tun: &AsyncFd<std::fs::File>,
    stats: &Arc<Stats>,
    device: &mut TunDevice,
) -> io::Result<()> {
    while let Some(pkt) = device.tx_queue.pop_front() {
        loop {
            match tun.try_io(Interest::WRITABLE, |inner| {
                let mut file = inner;
                file.write_all(&pkt)
            }) {
                Ok(()) => {
                    stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                    stats.rx_bytes.fetch_add(pkt.len() as u64, Ordering::Relaxed);
                    break;
                }
                Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                    let _ = tun.writable().await?;
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
