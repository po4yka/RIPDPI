use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use lru::LruCache;
use tokio_util::sync::CancellationToken;

use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

mod spawn;

use super::association_state::{
    OutboundDatagram, UDP_ATTRIBUTION_ID_CAPACITY, UDP_OUTBOUND_QUEUE_CAPACITY, UdpAssociation, now_millis,
};
use super::event_handling::UdpEvent;
use spawn::{UdpWorkerConfig, spawn_udp_worker};

/// Register a UDP association and spawn its SOCKS5 setup/pump worker.
///
/// # Cancel safety
///
/// Synchronous and cancel-safe. The non-cancel-safe
/// [`UdpSession::connect`](crate::session::UdpSession::connect)
/// sequence runs to completion in the spawned worker, never in the single task
/// that owns the TUN/smoltcp state. Cancellation is observed after setup and by
/// every subsequent cancel-safe send/receive operation.
#[allow(clippy::too_many_arguments)]
pub(super) fn spawn_udp_association(
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    _dest: SocketAddr,
    association_id: u64,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    memory_budget: UdpMemoryBudget,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> UdpAssociation {
    let last_activity = Arc::new(std::sync::atomic::AtomicU64::new(now_millis()));
    let (outbound, outbound_rx) = tokio::sync::mpsc::channel::<OutboundDatagram>(UDP_OUTBOUND_QUEUE_CAPACITY);
    let worker = spawn_udp_worker(
        UdpWorkerConfig {
            proxy_addr,
            auth,
            protect_path: protect_path.map(str::to_owned),
            src,
            association_id,
            idle_timeout,
            memory_budget,
        },
        outbound_rx,
        Arc::clone(&last_activity),
        cancel.clone(),
        udp_tx.clone(),
    );

    UdpAssociation {
        id: association_id,
        activity_generation: 0,
        outbound,
        cancel,
        last_activity,
        worker,
        leased_synthetic_ips: HashSet::new(),
        attribution_ids: LruCache::new(UDP_ATTRIBUTION_ID_CAPACITY),
    }
}
