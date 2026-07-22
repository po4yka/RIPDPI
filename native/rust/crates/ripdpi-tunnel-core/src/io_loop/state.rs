use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant as StdInstant;

use ripdpi_collections::bounded_heap::BoundedHeap;
use smoltcp::iface::{Interface, SocketHandle, SocketSet};
use tokio::sync::mpsc::{Receiver, Sender};
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::session::udp::UdpMemoryBudget;
use crate::{ActiveSessions, Stats, TunDevice};

use super::dns_intercept::{DnsRequest, DnsResponse};
use super::packet::TcpFlowKey;
use super::retransmit::RetransmitTracker;
use super::udp_assoc::{UdpAssociation, UdpEvent, UdpEvictionEntry};

mod runtime;

pub(in crate::io_loop) use runtime::LoopRuntime;

pub(in crate::io_loop) const PENDING_UID_UDP_CAPACITY: usize = 256;
pub(in crate::io_loop) const PENDING_UID_UDP_POOL_CAPACITY: usize = PENDING_UID_UDP_CAPACITY + 1;

pub(in crate::io_loop) struct PendingUidUdpPackets {
    queued: VecDeque<Vec<u8>>,
    free: Vec<Vec<u8>>,
    packet_capacity: usize,
}

impl PendingUidUdpPackets {
    pub(in crate::io_loop) fn new(packet_capacity: usize) -> Self {
        let free = (0..PENDING_UID_UDP_POOL_CAPACITY).map(|_| Vec::with_capacity(packet_capacity)).collect();
        Self { queued: VecDeque::with_capacity(PENDING_UID_UDP_CAPACITY), free, packet_capacity }
    }

    pub(in crate::io_loop) fn retain(&mut self, packet: &[u8]) -> bool {
        if packet.len() > self.packet_capacity {
            return false;
        }
        let buffer =
            if self.queued.len() == PENDING_UID_UDP_CAPACITY { self.queued.pop_front() } else { self.free.pop() };
        let Some(mut buffer) = buffer else {
            return false;
        };
        buffer.clear();
        buffer.extend_from_slice(packet);
        self.queued.push_back(buffer);
        true
    }

    pub(in crate::io_loop) fn pop_front(&mut self) -> Option<Vec<u8>> {
        self.queued.pop_front()
    }

    pub(in crate::io_loop) fn recycle(&mut self, mut packet: Vec<u8>) {
        packet.clear();
        self.free.push(packet);
    }

    pub(in crate::io_loop) fn len(&self) -> usize {
        self.queued.len()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn is_empty(&self) -> bool {
        self.queued.is_empty()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn free_len(&self) -> usize {
        self.free.len()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn back_ptr(&self) -> Option<*const u8> {
        self.queued.back().map(Vec::as_ptr)
    }
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
    pub(in crate::io_loop) tcp_admission_cursor: usize,
    pub(in crate::io_loop) loop_iteration: u32,
    pub(in crate::io_loop) udp_tx: Sender<UdpEvent>,
    pub(in crate::io_loop) udp_rx: Receiver<UdpEvent>,
    pub(in crate::io_loop) udp_associations: HashMap<SocketAddr, UdpAssociation>,
    pub(in crate::io_loop) udp_eviction_heap: BoundedHeap<UdpEvictionEntry>,
    pub(in crate::io_loop) udp_memory_budget: UdpMemoryBudget,
    pub(in crate::io_loop) next_udp_association_id: u64,
    pub(in crate::io_loop) pending_uid_udp_packets: PendingUidUdpPackets,
    pub(in crate::io_loop) dns_req_tx: Option<Sender<DnsRequest>>,
    pub(in crate::io_loop) dns_resp_rx: Option<Receiver<DnsResponse>>,
    pub(in crate::io_loop) tun_read_buf: Vec<u8>,
    pub(in crate::io_loop) retransmit_tracker: RetransmitTracker,
    pub(in crate::io_loop) last_loss_emit_iteration: u32,
}
