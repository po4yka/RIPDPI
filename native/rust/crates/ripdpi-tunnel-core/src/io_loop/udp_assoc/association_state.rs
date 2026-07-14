use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;

use tokio_util::sync::CancellationToken;

mod activity;
mod datagram;

pub(super) use activity::{now_millis, touch_udp_activity, udp_association_is_idle};
pub(super) use datagram::OutboundDatagram;

pub(super) const UDP_OUTBOUND_QUEUE_CAPACITY: usize = 16;

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
