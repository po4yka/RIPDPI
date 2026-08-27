use std::collections::HashSet;
use std::num::NonZeroUsize;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;

use lru::LruCache;
use tokio_util::sync::CancellationToken;

use ripdpi_flow_app_attribution::{FlowAttributionToken, FlowResolveRequest};

mod activity;
mod datagram;

pub(super) use activity::{now_millis, touch_udp_activity, udp_association_is_idle};
pub(super) use datagram::OutboundDatagram;

pub(super) const UDP_OUTBOUND_QUEUE_CAPACITY: usize = 16;

// At most 512 associations retain 32,768 tuple leases in total. Removing a lease
// only forces a fresh UID lookup; it never grants admission to an unknown flow.
pub(crate) const UDP_ATTRIBUTION_TOKEN_CAPACITY: NonZeroUsize = match NonZeroUsize::new(64) {
    Some(capacity) => capacity,
    // Infallible: the literal capacity 64 is non-zero.
    None => panic!("UDP attribution capacity must be non-zero"),
};

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
    /// One current generation per exact tuple, bounded independently of association lifetime.
    pub(super) attribution_tokens: LruCache<FlowResolveRequest, FlowAttributionToken>,
}
