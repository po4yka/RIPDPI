use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant as StdInstant};

use ripdpi_collections::bounded_heap::BoundedHeap;
use smoltcp::iface::{Interface, SocketHandle, SocketSet};
use tokio::sync::mpsc::{Receiver, Sender};
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::{ActiveSessions, Stats, TunDevice};

use super::dns_intercept::{DnsRequest, DnsResponse, MapDnsRuntime};
use super::packet::TcpFlowKey;
use super::tun_egress_interceptor::{RawTunPacketInjector, TunEgressInterceptor};
use super::tun_ingress_interceptor::{RawSynAckPacketInjector, TunIngressInterceptor};
use super::udp_assoc::{shutdown_udp_associations, UdpAssociation, UdpEvent, UdpEvictionEntry};

pub(in crate::io_loop) struct LoopRuntime {
    pub(in crate::io_loop) proxy_sockaddr: SocketAddr,
    pub(in crate::io_loop) auth: Auth,
    pub(in crate::io_loop) mapdns_runtime: Option<MapDnsRuntime>,
    pub(in crate::io_loop) mapdns_classify: Option<(u32, u32, u16)>,
    pub(in crate::io_loop) filter_injected_resets: bool,
    pub(in crate::io_loop) tun_ingress_interceptor: TunIngressInterceptor<RawSynAckPacketInjector>,
    pub(in crate::io_loop) tun_egress_interceptor: TunEgressInterceptor<RawTunPacketInjector>,
    pub(in crate::io_loop) udp_idle_timeout: Duration,
}

pub(in crate::io_loop) struct LoopState {
    pub(in crate::io_loop) device: TunDevice,
    pub(in crate::io_loop) iface: Interface,
    pub(in crate::io_loop) socket_set: SocketSet<'static>,
    pub(in crate::io_loop) sessions: ActiveSessions,
    pub(in crate::io_loop) cancel: CancellationToken,
    pub(in crate::io_loop) stats: Arc<Stats>,
    pub(in crate::io_loop) dns_cache: Option<DnsCache>,
    pub(in crate::io_loop) runtime: LoopRuntime,
    pub(in crate::io_loop) pending_listens: HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    pub(in crate::io_loop) loop_iteration: u32,
    pub(in crate::io_loop) udp_tx: Sender<UdpEvent>,
    pub(in crate::io_loop) udp_rx: Receiver<UdpEvent>,
    pub(in crate::io_loop) udp_associations: HashMap<SocketAddr, UdpAssociation>,
    pub(in crate::io_loop) udp_eviction_heap: BoundedHeap<UdpEvictionEntry>,
    pub(in crate::io_loop) next_udp_association_id: u64,
    pub(in crate::io_loop) dns_req_tx: Option<Sender<DnsRequest>>,
    pub(in crate::io_loop) dns_resp_rx: Option<Receiver<DnsResponse>>,
    pub(in crate::io_loop) tun_read_buf: Vec<u8>,
}

impl LoopState {
    pub(in crate::io_loop) async fn shutdown(&mut self) {
        super::bridge::shutdown_active_sessions(&mut self.sessions, &mut self.socket_set, &mut self.dns_cache).await;
        shutdown_udp_associations(&mut self.udp_associations).await;
    }
}
