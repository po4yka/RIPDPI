use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::session::Auth;

use super::super::association_state::UdpAssociation;
use super::super::event_handling::UdpEvent;
use super::super::eviction::{UdpEvictionEntry, evict_if_over_capacity};
use super::allocation::alloc_association;
use super::quic_sni::record_quic_sni_if_present;

/// IANA IP protocol number for UDP, for flow-attribution `note_flow`.
const PROTO_UDP: u8 = 17;

#[allow(clippy::too_many_arguments)]
pub(super) fn ensure_udp_association(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    resolved_dst: SocketAddr,
    payload: &[u8],
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

    // New UDP association: record the originating app's flow for per-app
    // attribution (once per association, not per datagram). `note_flow` only
    // locks a mutex and pushes to a queue (deduped by destination) -- no JNI
    // on this path; a background worker resolves off-path.
    ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, resolved_dst);
    record_quic_sni_if_present(stats, payload);
    if eviction_heap.is_full() {
        evict_if_over_capacity(associations, eviction_heap);
    }
    let association = alloc_association(
        next_id,
        proxy_addr,
        auth.clone(),
        src,
        resolved_dst,
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
    );
    eviction_heap
        .push(UdpEvictionEntry { addr: src, last_activity_epoch: association.last_activity.load(Ordering::Relaxed) });
    associations.insert(src, association);
}
