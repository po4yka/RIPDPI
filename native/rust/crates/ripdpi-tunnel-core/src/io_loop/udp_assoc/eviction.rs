use crate::io_loop::udp_assoc::UdpAssociations;
use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::net::SocketAddr;
use std::sync::atomic::Ordering;

use crate::dns_cache::DnsCache;

use super::association_removal::remove_association;
use super::association_state::{UdpAssociation, UdpAssociationKey, touch_udp_activity};

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
    associations: &mut UdpAssociations,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    key: impl Into<UdpAssociationKey>,
) {
    let key = key.into();
    let addr = key.src;
    if eviction_heap.len() >= UDP_EVICTION_HEAP_CAPACITY {
        rebuild_eviction_heap(associations, eviction_heap);
    }
    let Some(association) = associations.get_mut(&key) else {
        return;
    };
    touch_udp_activity(&association.last_activity);
    association.activity_generation = association.activity_generation.wrapping_add(1);
    debug_assert!(eviction_heap.len() < UDP_EVICTION_HEAP_CAPACITY);
    eviction_heap.push(Reverse(entry_for(addr, association)));
}

fn rebuild_eviction_heap(associations: &UdpAssociations, eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>) {
    eviction_heap.clear();
    for (key, association) in associations.iter() {
        eviction_heap.push(Reverse(entry_for(key.src, association)));
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
    associations: &mut UdpAssociations,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
) {
    evict_if_at_capacity(associations, eviction_heap, dns_cache, closed_sources, DEFAULT_MAX_UDP_ASSOCIATIONS);
}

pub(super) fn evict_if_at_capacity(
    associations: &mut UdpAssociations,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
    capacity: usize,
) {
    if associations.len() < capacity {
        return;
    }
    while let Some(Reverse(entry)) = eviction_heap.pop() {
        let is_current = associations.get_by_id(entry.association_id).is_some_and(|association| {
            association.id == entry.association_id && association.activity_generation == entry.activity_generation
        });
        if is_current {
            if let Some(key) = associations.key_by_id(entry.association_id).cloned() {
                remove_association(associations, dns_cache, closed_sources, key);
            }
            return;
        }
    }

    if let Some(key) = associations
        .iter()
        .min_by_key(|(_, association)| association.last_activity.load(Ordering::Relaxed))
        .map(|(key, _)| key.clone())
    {
        remove_association(associations, dns_cache, closed_sources, key);
    }
}
