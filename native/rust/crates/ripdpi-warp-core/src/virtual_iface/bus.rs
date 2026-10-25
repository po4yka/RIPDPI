use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use bytes::Bytes;
use tokio::sync::{Notify, broadcast, mpsc};

use crate::ports::{PortForwardConfig, PortProtocol, VirtualPort, VirtualPortLease};

#[derive(Debug, Clone)]
pub(crate) enum Event {
    Shutdown,
    ClientConnectionDropped(VirtualPort),
    LocalData(PortForwardConfig, VirtualPort, Bytes),
    RemoteUdpDatagram(VirtualPort, SocketAddr, Bytes),
    InboundInternetPacket(PortProtocol, Bytes),
    OutboundInternetPacket(Bytes),
    VirtualDeviceFed(PortProtocol),
}

/// Remote chunks one TCP session may queue before the interface stops reading
/// its smoltcp socket, which closes the TCP window toward the peer.
const TCP_SESSION_QUEUE: usize = 8;

/// TCP session traffic from SOCKS handlers to the TCP interface. It uses a
/// lossless channel: the packet bus may drop events, but smoltcp has already
/// ACKed stream bytes, so losing one corrupts the stream.
pub(crate) enum TcpCommand {
    Open(PortForwardConfig, VirtualPortLease, mpsc::Sender<Bytes>),
    Data(VirtualPort, Bytes),
    Close(VirtualPort),
}

#[derive(Clone)]
pub(crate) struct Bus {
    counter: Arc<AtomicU64>,
    tx: broadcast::Sender<(u64, Event)>,
    tcp_tx: mpsc::UnboundedSender<TcpCommand>,
    tcp_rx: Arc<Mutex<Option<mpsc::UnboundedReceiver<TcpCommand>>>>,
    tcp_drained: Arc<Notify>,
}

impl Bus {
    pub(crate) fn new() -> Self {
        let (tx, _) = broadcast::channel(1024);
        let (tcp_tx, tcp_rx) = mpsc::unbounded_channel();
        Self {
            counter: Arc::new(AtomicU64::new(0)),
            tx,
            tcp_tx,
            tcp_rx: Arc::new(Mutex::new(Some(tcp_rx))),
            tcp_drained: Arc::new(Notify::new()),
        }
    }

    /// Register a TCP session with the TCP interface and return its lossless,
    /// back-pressured channel pair.
    pub(crate) fn open_tcp_session(&self, forward: PortForwardConfig, lease: VirtualPortLease) -> TcpSession {
        let (remote_tx, remote) = mpsc::channel(TCP_SESSION_QUEUE);
        let port = lease.port();
        let _ = self.tcp_tx.send(TcpCommand::Open(forward, lease, remote_tx));
        TcpSession { port, commands: self.tcp_tx.clone(), remote, drained: Arc::clone(&self.tcp_drained) }
    }

    /// The command receiver and drain signal of the single TCP interface on this bus.
    pub(crate) fn take_tcp_commands(&self) -> (mpsc::UnboundedReceiver<TcpCommand>, Arc<Notify>) {
        let mut slot = self.tcp_rx.lock().unwrap_or_else(PoisonError::into_inner);
        // Infallible: each runtime builds one fresh Bus and exactly one DynamicTcpInterface on it.
        let commands = slot.take().expect("one TCP interface per bus");
        (commands, Arc::clone(&self.tcp_drained))
    }

    pub(crate) fn new_endpoint(&self) -> BusEndpoint {
        let id = self.counter.fetch_add(1, Ordering::Relaxed);
        BusEndpoint { id, tx: self.tx.clone(), rx: self.tx.subscribe() }
    }

    pub(crate) fn shutdown(&self) {
        let _ = self.tx.send((u64::MAX, Event::Shutdown));
    }
}

/// One SOCKS handler's side of a TCP session. Dropping it closes the session.
pub(crate) struct TcpSession {
    port: VirtualPort,
    commands: mpsc::UnboundedSender<TcpCommand>,
    remote: mpsc::Receiver<Bytes>,
    drained: Arc<Notify>,
}

impl TcpSession {
    /// Queue client bytes for the virtual socket. `false` once the interface is gone.
    pub(crate) fn send_local(&self, data: Bytes) -> bool {
        self.commands.send(TcpCommand::Data(self.port, data)).is_ok()
    }

    /// Next chunk from the peer, in order; `None` once the virtual socket is closed.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: `mpsc::Receiver::recv` takes a chunk only when it completes.
    pub(crate) async fn recv_remote(&mut self) -> Option<Bytes> {
        let data = self.remote.recv().await;
        // Queue space opened up: let the interface read this socket again.
        self.drained.notify_one();
        data
    }
}

impl Drop for TcpSession {
    fn drop(&mut self) {
        let _ = self.commands.send(TcpCommand::Close(self.port));
    }
}

pub(crate) struct BusEndpoint {
    pub(super) id: u64,
    pub(super) tx: broadcast::Sender<(u64, Event)>,
    rx: broadcast::Receiver<(u64, Event)>,
}

impl BusEndpoint {
    pub(crate) fn send(&self, event: Event) {
        let _ = self.tx.send((self.id, event));
    }

    pub(crate) async fn recv(&mut self) -> Event {
        loop {
            match self.rx.recv().await {
                Ok((id, event)) if id != self.id => return event,
                Ok(_) => continue,
                Err(broadcast::error::RecvError::Lagged(n)) => {
                    tracing::warn!(dropped = n, "warp virtual-iface bus receiver lagged; events lost");
                    continue;
                }
                // Effectively unreachable: this endpoint holds its own `tx` sender, so the
                // channel cannot close while the endpoint is alive. Handled defensively.
                Err(broadcast::error::RecvError::Closed) => continue,
            }
        }
    }
}
