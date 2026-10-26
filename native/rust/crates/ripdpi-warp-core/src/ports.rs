use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use serde::Serialize;
use tokio::sync::RwLock;

pub(crate) const MIN_VIRTUAL_PORT: u16 = 1000;
pub(crate) const MAX_VIRTUAL_PORT: u16 = 60_999;

#[derive(Debug, Clone, Copy, Eq, PartialEq, Hash, Ord, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum PortProtocol {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct PortForwardConfig {
    pub(crate) destination: SocketAddr,
    pub(crate) protocol: PortProtocol,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub(crate) struct VirtualPort(u16, PortProtocol);

impl VirtualPort {
    pub(crate) fn new(port: u16, protocol: PortProtocol) -> Self {
        Self(port, protocol)
    }

    pub(crate) fn num(self) -> u16 {
        self.0
    }
}
#[derive(Clone)]
pub(crate) struct VirtualPortPool {
    protocol: PortProtocol,
    free_ports: Arc<Mutex<VecDeque<u16>>>,
}

#[derive(Clone)]
pub(crate) struct VirtualPortLease(Arc<VirtualPortLeaseInner>);

struct VirtualPortLeaseInner {
    port: VirtualPort,
    free_ports: Arc<Mutex<VecDeque<u16>>>,
    close_requested_at: Mutex<Option<tokio::time::Instant>>,
    /// Last data movement of a session in idle close; `None` before the idle close starts.
    idle_since: Mutex<Option<tokio::time::Instant>>,
}

impl std::fmt::Debug for VirtualPortLease {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_tuple("VirtualPortLease").field(&self.0.port).finish()
    }
}

impl VirtualPortLease {
    pub(crate) fn port(&self) -> VirtualPort {
        self.0.port
    }

    pub(crate) fn request_close(&self) {
        if let Ok(mut requested) = self.0.close_requested_at.lock() {
            requested.get_or_insert_with(tokio::time::Instant::now);
        }
    }

    pub(crate) fn close_requested_at(&self) -> io::Result<Option<tokio::time::Instant>> {
        self.0
            .close_requested_at
            .lock()
            .map(|requested| *requested)
            .map_err(|_| io::Error::other("virtual TCP close state poisoned"))
    }

    /// Start the idle close of a half-closed session: its close grace counts from
    /// the last data movement in either direction, not from this call.
    pub(crate) fn request_idle_close(&self) {
        if let Ok(mut idle) = self.0.idle_since.lock() {
            idle.get_or_insert_with(tokio::time::Instant::now);
        }
    }

    /// Data moved: restart the close grace of a session in idle close.
    pub(crate) fn record_activity(&self) {
        if let Ok(mut idle) = self.0.idle_since.lock()
            && let Some(since) = idle.as_mut()
        {
            *since = tokio::time::Instant::now();
        }
    }

    pub(crate) fn idle_since(&self) -> io::Result<Option<tokio::time::Instant>> {
        self.0.idle_since.lock().map(|idle| *idle).map_err(|_| io::Error::other("virtual TCP idle state poisoned"))
    }
}

impl Drop for VirtualPortLeaseInner {
    fn drop(&mut self) {
        // No async resources: the last socket/control owner returns this lease once.
        // Poisoning refuses future allocations instead of reusing an uncertain port.
        if let Ok(mut ports) = self.free_ports.lock() {
            ports.push_back(self.port.num());
        }
    }
}

impl VirtualPortPool {
    pub(crate) fn new(protocol: PortProtocol) -> io::Result<Self> {
        let count = u64::from(MAX_VIRTUAL_PORT - MIN_VIRTUAL_PORT);
        let offset = getrandom::u64().map_err(io::Error::other)? % count;
        Ok(Self::with_start(protocol, MIN_VIRTUAL_PORT + offset as u16))
    }

    fn with_start(protocol: PortProtocol, start: u16) -> Self {
        let mut ports = VecDeque::new();
        for port in MIN_VIRTUAL_PORT..MAX_VIRTUAL_PORT {
            ports.push_back(port);
        }
        // Start recreated stacks at unpredictable points in the existing port ring.
        ports.rotate_left(usize::from(start - MIN_VIRTUAL_PORT));
        Self { protocol, free_ports: Arc::new(Mutex::new(ports)) }
    }

    pub(crate) fn acquire(&self) -> io::Result<VirtualPortLease> {
        let port = self
            .free_ports
            .lock()
            .map_err(|_| io::Error::other("virtual port pool poisoned"))?
            .pop_front()
            .map(|port| VirtualPort::new(port, self.protocol))
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "virtual port pool exhausted"))?;
        Ok(VirtualPortLease(Arc::new(VirtualPortLeaseInner {
            port,
            free_ports: Arc::clone(&self.free_ports),
            close_requested_at: Mutex::new(None),
            idle_since: Mutex::new(None),
        })))
    }

    /// Ports not held by any lease.
    #[cfg(test)]
    pub(crate) fn free_len(&self) -> usize {
        self.free_ports.lock().map_or(0, |ports| ports.len())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
struct UdpAssociationKey {
    bind_port: u16,
    peer_addr: SocketAddr,
}

#[derive(Default)]
struct UdpAssociationPoolState {
    free_ports: VecDeque<u16>,
    by_key: HashMap<UdpAssociationKey, VirtualPort>,
    by_port: HashMap<u16, UdpAssociationKey>,
}

#[derive(Clone, Default)]
pub(crate) struct UdpAssociationPool {
    state: Arc<RwLock<UdpAssociationPoolState>>,
}

impl UdpAssociationPool {
    pub(crate) fn new() -> Self {
        let mut state = UdpAssociationPoolState::default();
        for port in MIN_VIRTUAL_PORT..MAX_VIRTUAL_PORT {
            state.free_ports.push_back(port);
        }
        Self { state: Arc::new(RwLock::new(state)) }
    }

    pub(crate) async fn acquire(&self, bind_port: u16, peer_addr: SocketAddr) -> io::Result<VirtualPort> {
        let key = UdpAssociationKey { bind_port, peer_addr };
        let mut state = self.state.write().await;
        if let Some(port) = state.by_key.get(&key).copied() {
            return Ok(port);
        }
        let port = state
            .free_ports
            .pop_front()
            .map(|raw| VirtualPort::new(raw, PortProtocol::Udp))
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "udp virtual port pool exhausted"))?;
        state.by_key.insert(key, port);
        state.by_port.insert(port.num(), key);
        Ok(port)
    }

    pub(crate) async fn peer_addr(&self, port: VirtualPort) -> Option<SocketAddr> {
        self.state.read().await.by_port.get(&port.num()).map(|key| key.peer_addr)
    }

    pub(crate) async fn release_association(&self, bind_port: u16) {
        let mut state = self.state.write().await;
        let ports: Vec<u16> =
            state.by_port.iter().filter_map(|(port, key)| (key.bind_port == bind_port).then_some(*port)).collect();
        for port in ports {
            if let Some(key) = state.by_port.remove(&port) {
                state.by_key.remove(&key);
                state.free_ports.push_back(port);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn virtual_port_pool_returns_released_ports_to_available_set() {
        let pool = VirtualPortPool::with_start(PortProtocol::Tcp, MIN_VIRTUAL_PORT);
        let first = pool.acquire().expect("first port");
        let second = pool.acquire().expect("second port");

        assert_eq!(first.port().num(), MIN_VIRTUAL_PORT);
        assert_eq!(second.port().num(), MIN_VIRTUAL_PORT + 1);
        assert_eq!(pool.free_ports.lock().expect("pool").len(), usize::from(MAX_VIRTUAL_PORT - MIN_VIRTUAL_PORT) - 2);

        drop(first);

        assert_eq!(pool.free_ports.lock().expect("pool").len(), usize::from(MAX_VIRTUAL_PORT - MIN_VIRTUAL_PORT) - 1);
    }

    #[test]
    fn shifted_tcp_pool_wraps_without_duplicates_or_lost_ports() {
        let pool = VirtualPortPool::with_start(PortProtocol::Tcp, MAX_VIRTUAL_PORT - 1);
        let mut leases = vec![pool.acquire().expect("last port")];
        assert_eq!(leases[0].port().num(), MAX_VIRTUAL_PORT - 1);
        for expected in MIN_VIRTUAL_PORT..MAX_VIRTUAL_PORT - 1 {
            let lease = pool.acquire().expect("remaining port");
            assert_eq!(lease.port().num(), expected);
            leases.push(lease);
        }
        assert_eq!(pool.acquire().expect_err("pool exhausted").kind(), io::ErrorKind::AddrNotAvailable);
        let retained = leases[0].clone();
        drop(leases.remove(0));
        assert!(pool.acquire().is_err(), "a live socket clone must retain the lease");
        drop(retained);
        assert_eq!(pool.acquire().expect("last owner released").port().num(), MAX_VIRTUAL_PORT - 1);
    }

    #[tokio::test]
    async fn udp_association_pool_keeps_stable_peer_mapping() {
        let pool = UdpAssociationPool::new();
        let bind_port = 49000;
        let peer = "127.0.0.1:53000".parse().expect("peer addr");

        let first = pool.acquire(bind_port, peer).await.expect("first udp port");
        let second = pool.acquire(bind_port, peer).await.expect("same udp port");

        assert_eq!(first, second);
        assert_eq!(pool.peer_addr(first).await, Some(peer));
    }

    #[tokio::test]
    async fn udp_association_release_frees_only_matching_bind_port() {
        let pool = UdpAssociationPool::new();
        let first_peer = "127.0.0.1:53000".parse().expect("first peer addr");
        let second_peer = "127.0.0.1:53001".parse().expect("second peer addr");
        let first = pool.acquire(49000, first_peer).await.expect("first udp port");
        let second = pool.acquire(49001, second_peer).await.expect("second udp port");

        pool.release_association(49000).await;

        assert_eq!(pool.peer_addr(first).await, None);
        assert_eq!(pool.peer_addr(second).await, Some(second_peer));
    }
}
