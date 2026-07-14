use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_state::UdpAssociation;
use super::super::event_handling::UdpEvent;
use super::super::eviction::{UdpEvictionEntry, evict_if_over_capacity, record_udp_activity};
use super::allocation::alloc_association;
use super::quic_sni::record_quic_sni_if_present;

#[allow(clippy::too_many_arguments)]
pub(super) fn ensure_udp_association(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    memory_budget: &UdpMemoryBudget,
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    resolved_dst: SocketAddr,
    payload: &[u8],
    dns_cache: &mut Option<DnsCache>,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
    stats: &Arc<Stats>,
) {
    #[allow(clippy::map_entry)]
    if associations.contains_key(&src) {
        return;
    }

    record_quic_sni_if_present(stats, payload);
    evict_if_over_capacity(associations, eviction_heap, dns_cache);
    let association = alloc_association(
        next_id,
        memory_budget,
        proxy_addr,
        auth.clone(),
        src,
        resolved_dst,
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
    );
    associations.insert(src, association);
    record_udp_activity(associations, eviction_heap, src);
}
