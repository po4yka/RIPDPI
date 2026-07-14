use std::collections::HashMap;
use std::net::SocketAddr;

use crate::TunDevice;
use crate::dns_cache::DnsCache;
use ripdpi_collections::bounded_heap::BoundedHeap;

use super::super::bridge::enqueue_tun_packet;
use super::association_removal::remove_association;
use super::association_state::UdpAssociation;
use super::eviction::{UdpEvictionEntry, record_udp_activity};

pub(in crate::io_loop) enum UdpEvent {
    Packet { src: SocketAddr, association_id: u64, raw: Vec<u8> },
    Closed { src: SocketAddr, association_id: u64 },
}

pub(in crate::io_loop) fn handle_udp_event(
    device: &mut TunDevice,
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    dns_cache: &mut Option<DnsCache>,
    event: UdpEvent,
) {
    match event {
        UdpEvent::Packet { src, association_id, raw } => {
            if associations.get(&src).is_some_and(|association| association.id == association_id) {
                record_udp_activity(associations, eviction_heap, src);
                enqueue_tun_packet(device, raw);
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if associations.get(&src).is_some_and(|association| association.id == association_id) {
                remove_association(associations, dns_cache, src);
            }
        }
    }
}
