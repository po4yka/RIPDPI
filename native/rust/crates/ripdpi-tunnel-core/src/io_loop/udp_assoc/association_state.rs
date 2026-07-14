use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio_util::sync::CancellationToken;

use crate::session::udp::UdpMemoryBudget;

pub(super) const UDP_OUTBOUND_QUEUE_CAPACITY: usize = 16;

pub(super) struct OutboundDatagram {
    pub(super) dest: SocketAddr,
    pub(super) payload: Vec<u8>,
    _queued_bytes: tokio::sync::OwnedSemaphorePermit,
}

impl OutboundDatagram {
    pub(super) fn try_new(dest: SocketAddr, payload: &[u8], memory_budget: &UdpMemoryBudget) -> Option<Self> {
        let queued_bytes = memory_budget.try_reserve_queued_bytes(payload.len())?;
        Some(Self { dest, payload: payload.to_vec(), _queued_bytes: queued_bytes })
    }
}

/// Returns milliseconds since the Unix epoch, or 0 on clock failure.
pub(super) fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub(in crate::io_loop) struct UdpAssociation {
    pub(super) id: u64,
    pub(super) activity_generation: u64,
    pub(super) outbound: tokio::sync::mpsc::Sender<OutboundDatagram>,
    pub(super) cancel: CancellationToken,
    pub(super) last_activity: Arc<AtomicU64>,
    pub(super) worker: tokio::task::JoinHandle<()>,
    /// Synthetic MapDNS addresses used by this association. Each entry owns
    /// one cache lease until association removal or shutdown.
    pub(super) leased_synthetic_ips: HashSet<u32>,
    /// Exact attribution generations observed on this multiplexed association.
    pub(super) attribution_tokens: HashSet<ripdpi_flow_app_attribution::FlowAttributionToken>,
}

pub(super) fn touch_udp_activity(last_activity: &Arc<AtomicU64>) {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    last_activity.store(now_millis(), Ordering::Relaxed);
}

pub(super) fn udp_association_is_idle(last_activity: &Arc<AtomicU64>, idle_timeout: Duration) -> bool {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    now_millis().saturating_sub(last_activity.load(Ordering::Relaxed)) >= idle_timeout.as_millis() as u64
}
