use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::AtomicU64;

use lru::LruCache;
use tokio_util::sync::CancellationToken;

use ripdpi_flow_app_attribution::{FlowRegistrationId, FlowResolveRequest};

use super::{OutboundDatagram, UdpDestination};

pub(in crate::io_loop) struct UdpAssociation {
    pub(in crate::io_loop::udp_assoc) id: u64,
    pub(in crate::io_loop::udp_assoc) activity_generation: u64,
    pub(in crate::io_loop) pending_error: Option<(ripdpi_flow_app_attribution::FlowRegistrationId, Vec<u8>)>,
    pub(in crate::io_loop::udp_assoc) outbound: tokio::sync::mpsc::Sender<OutboundDatagram>,
    pub(in crate::io_loop::udp_assoc) cancel: CancellationToken,
    pub(in crate::io_loop::udp_assoc) last_activity: Arc<AtomicU64>,
    pub(in crate::io_loop::udp_assoc) worker: tokio::task::JoinHandle<()>,
    /// Kernel-visible destinations admitted before allocating per-target resources.
    pub(in crate::io_loop::udp_assoc) destinations: HashMap<std::net::SocketAddr, Arc<UdpDestination>>,
    /// One lease for each synthetic address used by a current destination.
    pub(in crate::io_loop::udp_assoc) leased_synthetic_ips: HashSet<u32>,
    /// One current generation per exact tuple, bounded independently of association lifetime.
    pub(in crate::io_loop::udp_assoc) attribution_ids: LruCache<FlowResolveRequest, FlowRegistrationId>,
}
