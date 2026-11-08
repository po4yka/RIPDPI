use crate::io_loop::udp_assoc::UdpAssociations;
use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_state::UdpAssociationKey;
use super::super::event_handling::UdpEvent;
use super::super::eviction::{UdpEvictionEntry, evict_if_over_capacity, record_udp_activity};
use super::allocation::alloc_association;
use super::quic_sni::record_quic_sni_if_present;

#[allow(clippy::too_many_arguments)]
pub(super) fn ensure_udp_association(
    associations: &mut UdpAssociations,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    memory_budget: &UdpMemoryBudget,
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: &Auth,
    key: &UdpAssociationKey,
    resolved_dst: SocketAddr,
    payload: &[u8],
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
    idle_timeout: Duration,
    connect_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
    stats: &Arc<Stats>,
) {
    if associations.get(key).is_some() {
        return;
    }

    let src = key.src;
    record_quic_sni_if_present(stats, payload);
    evict_if_over_capacity(associations, eviction_heap, dns_cache, closed_sources);
    let association = alloc_association(
        next_id,
        memory_budget,
        proxy_addr,
        auth.with_app_scope(proxy_addr, key.app_scope.as_deref()),
        src,
        resolved_dst,
        idle_timeout,
        connect_timeout,
        protect_path,
        cancel,
        udp_tx,
    );
    associations.insert(key, association);
    record_udp_activity(associations, eviction_heap, key);
}
