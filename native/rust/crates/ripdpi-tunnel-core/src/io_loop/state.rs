use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_collections::bounded_heap::BoundedHeap;
use smoltcp::iface::{Interface, SocketSet};
use tokio::sync::mpsc::{Receiver, Sender};
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::io_loop::tcp_accept::PendingListener;
use crate::session::udp::UdpMemoryBudget;
use crate::{ActiveSessions, Stats, TunDevice};

use super::dns_intercept::{DnsRequest, DnsResponse};
use super::packet::TcpFlowKey;
use super::retransmit::RetransmitTracker;
use super::udp_assoc::{UdpAssociation, UdpEvent, UdpEvictionEntry};

mod pending_uid;
mod runtime;

#[cfg(test)]
pub(in crate::io_loop) use pending_uid::{PENDING_UID_CAPACITY, PENDING_UID_POOL_CAPACITY};
pub(in crate::io_loop) use pending_uid::{PendingUidPackets, PendingUidRetainOutcome};
pub(in crate::io_loop) use runtime::LoopRuntime;

pub(in crate::io_loop) struct LoopState {
    pub(in crate::io_loop) device: TunDevice,
    pub(in crate::io_loop) iface: Interface,
    pub(in crate::io_loop) socket_set: SocketSet<'static>,
    pub(in crate::io_loop) sessions: ActiveSessions,
    pub(in crate::io_loop) cancel: CancellationToken,
    pub(in crate::io_loop) stats: Arc<Stats>,
    pub(in crate::io_loop) dns_cache: Option<DnsCache>,
    pub(in crate::io_loop) runtime: LoopRuntime,
    pub(in crate::io_loop) pending_listens: HashMap<TcpFlowKey, PendingListener>,
    pub(in crate::io_loop) tcp_admission_cursor: usize,
    pub(in crate::io_loop) loop_iteration: u32,
    pub(in crate::io_loop) udp_tx: Sender<UdpEvent>,
    pub(in crate::io_loop) udp_rx: Receiver<UdpEvent>,
    pub(in crate::io_loop) udp_associations: HashMap<SocketAddr, UdpAssociation>,
    pub(in crate::io_loop) udp_eviction_heap: BoundedHeap<UdpEvictionEntry>,
    pub(in crate::io_loop) udp_memory_budget: UdpMemoryBudget,
    pub(in crate::io_loop) next_udp_association_id: u64,
    pub(in crate::io_loop) pending_uid_packets: PendingUidPackets,
    pub(in crate::io_loop) dns_req_tx: Option<Sender<DnsRequest>>,
    pub(in crate::io_loop) dns_resp_rx: Option<Receiver<DnsResponse>>,
    pub(in crate::io_loop) active_direct_dns_generation: Option<u64>,
    pub(in crate::io_loop) tun_read_buf: Vec<u8>,
    pub(in crate::io_loop) retransmit_tracker: RetransmitTracker,
    pub(in crate::io_loop) last_loss_emit_iteration: u32,
}
