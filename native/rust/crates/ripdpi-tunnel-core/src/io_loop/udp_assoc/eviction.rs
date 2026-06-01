use std::collections::HashMap;
use std::net::SocketAddr;

use ripdpi_collections::bounded_heap::BoundedHeap;

use super::association_state::{UdpAssociation, remove_association};

/// Default maximum number of concurrent UDP associations.
pub(in crate::io_loop) const DEFAULT_MAX_UDP_ASSOCIATIONS: usize = 512;

/// Eviction priority entry for UDP associations.
///
/// Orders by `last_activity_epoch` (oldest first = smallest = evicted first).
#[derive(Debug, Eq, PartialEq, Ord, PartialOrd)]
pub(in crate::io_loop) struct UdpEvictionEntry {
    pub last_activity_epoch: u64,
    pub addr: SocketAddr,
}

/// Evict the least-recently-active UDP association if the map exceeds capacity.
pub(super) fn evict_if_over_capacity(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
) {
    if let Some(entry) = eviction_heap.pop() {
        remove_association(associations, entry.addr);
    }
}
