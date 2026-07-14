use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::Ordering;

use ripdpi_collections::bounded_heap::BoundedHeap;

use crate::dns_cache::DnsCache;

use super::association_removal::remove_association;
use super::association_state::{UdpAssociation, touch_udp_activity};

/// Default maximum number of concurrent UDP associations.
pub(in crate::io_loop) const DEFAULT_MAX_UDP_ASSOCIATIONS: usize = 512;
pub(in crate::io_loop) const UDP_EVICTION_HEAP_CAPACITY: usize = DEFAULT_MAX_UDP_ASSOCIATIONS * 4;

/// Eviction priority entry for UDP associations.
///
/// Orders by `last_activity_epoch` (oldest first = smallest = evicted first).
#[derive(Debug, Eq, PartialEq, Ord, PartialOrd)]
pub(in crate::io_loop) struct UdpEvictionEntry {
    pub last_activity_epoch: u64,
    pub association_id: u64,
    pub activity_generation: u64,
    pub addr: SocketAddr,
}

pub(super) fn record_udp_activity(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    addr: SocketAddr,
) {
    if eviction_heap.is_full() {
        rebuild_eviction_heap(associations, eviction_heap);
    }
    let Some(association) = associations.get_mut(&addr) else {
        return;
    };
    touch_udp_activity(&association.last_activity);
    association.activity_generation = association.activity_generation.wrapping_add(1);
    let inserted = eviction_heap.push(entry_for(addr, association));
    debug_assert!(inserted, "compacted UDP eviction heap must have room for an activity update");
}

fn rebuild_eviction_heap(
    associations: &HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
) {
    for _ in eviction_heap.drain() {}
    for (&addr, association) in associations {
        let inserted = eviction_heap.push(entry_for(addr, association));
        debug_assert!(inserted, "UDP eviction heap capacity must exceed the association limit");
    }
}

fn entry_for(addr: SocketAddr, association: &UdpAssociation) -> UdpEvictionEntry {
    UdpEvictionEntry {
        last_activity_epoch: association.last_activity.load(Ordering::Relaxed),
        association_id: association.id,
        activity_generation: association.activity_generation,
        addr,
    }
}

/// Evict the least-recently-active live UDP association when the map is full.
pub(super) fn evict_if_over_capacity(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    dns_cache: &mut Option<DnsCache>,
) {
    evict_if_at_capacity(associations, eviction_heap, dns_cache, DEFAULT_MAX_UDP_ASSOCIATIONS);
}

pub(super) fn evict_if_at_capacity(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    dns_cache: &mut Option<DnsCache>,
    capacity: usize,
) {
    if associations.len() < capacity {
        return;
    }
    while let Some(entry) = eviction_heap.pop() {
        let is_current = associations.get(&entry.addr).is_some_and(|association| {
            association.id == entry.association_id && association.activity_generation == entry.activity_generation
        });
        if is_current {
            remove_association(associations, dns_cache, entry.addr);
            return;
        }
    }

    if let Some(addr) = associations
        .iter()
        .min_by_key(|(_, association)| association.last_activity.load(Ordering::Relaxed))
        .map(|(&addr, _)| addr)
    {
        remove_association(associations, dns_cache, addr);
    }
}
