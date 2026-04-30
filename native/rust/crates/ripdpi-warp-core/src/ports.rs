use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

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
    free_ports: Arc<RwLock<VecDeque<u16>>>,
}

impl VirtualPortPool {
    pub(crate) fn new(protocol: PortProtocol) -> Self {
        let mut ports = VecDeque::new();
        for port in MIN_VIRTUAL_PORT..MAX_VIRTUAL_PORT {
            ports.push_back(port);
        }
        Self { protocol, free_ports: Arc::new(RwLock::new(ports)) }
    }

    pub(crate) async fn acquire(&self) -> io::Result<VirtualPort> {
        self.free_ports
            .write()
            .await
            .pop_front()
            .map(|port| VirtualPort::new(port, self.protocol))
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "virtual port pool exhausted"))
    }

    pub(crate) async fn release(&self, port: VirtualPort) {
        self.free_ports.write().await.push_back(port.num());
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
