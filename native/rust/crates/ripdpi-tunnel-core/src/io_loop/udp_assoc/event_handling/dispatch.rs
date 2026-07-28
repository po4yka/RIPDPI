use std::collections::HashMap;
use std::net::SocketAddr;

use ripdpi_collections::bounded_heap::BoundedHeap;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::super::bridge::enqueue_tun_packet;
use super::super::association_removal::remove_association;
use super::super::association_state::UdpAssociation;
use super::super::eviction::{UdpEvictionEntry, record_udp_activity};
use super::event::UdpEvent;

pub(in crate::io_loop) fn handle_udp_event(
    device: &mut TunDevice,
    stats: &Stats,
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    dns_cache: &mut Option<DnsCache>,
    event: UdpEvent,
) {
    match event {
        UdpEvent::Packet { src, association_id, raw } => {
            if associations.get(&src).is_some_and(|association| association.id == association_id) {
                record_udp_activity(associations, eviction_heap, src);
                enqueue_tun_packet(device, stats, raw);
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if associations.get(&src).is_some_and(|association| association.id == association_id) {
                remove_association(associations, dns_cache, src);
            }
        }
    }
}
