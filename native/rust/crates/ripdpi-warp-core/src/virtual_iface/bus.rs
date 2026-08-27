use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use bytes::Bytes;
use tokio::sync::broadcast;

use crate::ports::{PortForwardConfig, PortProtocol, VirtualPort};

#[derive(Debug, Clone)]
pub(crate) enum Event {
    Shutdown,
    ClientConnectionInitiated(PortForwardConfig, VirtualPort),
    ClientConnectionDropped(VirtualPort),
    LocalData(PortForwardConfig, VirtualPort, Bytes),
    RemoteData(VirtualPort, Bytes),
    RemoteUdpDatagram(VirtualPort, SocketAddr, Bytes),
    InboundInternetPacket(PortProtocol, Bytes),
    OutboundInternetPacket(Bytes),
    VirtualDeviceFed(PortProtocol),
}

#[derive(Clone)]
pub(crate) struct Bus {
    counter: Arc<AtomicU64>,
    tx: broadcast::Sender<(u64, Event)>,
}

impl Bus {
    pub(crate) fn new() -> Self {
        let (tx, _) = broadcast::channel(1024);
        Self { counter: Arc::new(AtomicU64::new(0)), tx }
    }

    pub(crate) fn new_endpoint(&self) -> BusEndpoint {
        let id = self.counter.fetch_add(1, Ordering::Relaxed);
        BusEndpoint { id, tx: self.tx.clone(), rx: self.tx.subscribe() }
    }

    pub(crate) fn shutdown(&self) {
        let _ = self.tx.send((u64::MAX, Event::Shutdown));
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
