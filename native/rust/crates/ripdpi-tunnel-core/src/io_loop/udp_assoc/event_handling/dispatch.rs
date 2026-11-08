use crate::io_loop::udp_assoc::UdpAssociations;
use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::net::SocketAddr;
use std::sync::Arc;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::super::bridge::enqueue_tun_packet;
use super::super::association_removal::remove_association;
use super::super::eviction::{UdpEvictionEntry, record_udp_activity};
use super::event::UdpEvent;

#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) fn handle_udp_event(
    device: &mut TunDevice,
    stats: &Stats,
    associations: &mut UdpAssociations,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
    event: UdpEvent,
    mut error_allowed: impl FnMut(&ripdpi_flow_app_attribution::FlowRegistrationId) -> bool,
) {
    match event {
        UdpEvent::Packet { src, association_id, destination, raw } => {
            if associations.key_by_id(association_id).is_some_and(|key| key.src == src)
                && destination.is_live()
                && associations.get_by_id(association_id).is_some_and(|association| {
                    association.id == association_id
                        && association
                            .destinations
                            .get(&destination.response_source)
                            .is_some_and(|current| Arc::ptr_eq(current, &destination))
                })
            {
                destination.touch();
                if let Some(key) = associations.key_by_id(association_id).cloned() {
                    record_udp_activity(associations, eviction_heap, key);
                }
                enqueue_tun_packet(device, stats, raw);
            }
        }
        UdpEvent::RemoteError { src, association_id, registration_id, destination, raw } => {
            if associations.key_by_id(association_id).is_some_and(|key| key.src == src)
                && destination.is_live()
                && associations.get_by_id(association_id).is_some_and(|association| {
                    association.id == association_id
                        && association
                            .destinations
                            .get(&destination.response_source)
                            .is_some_and(|current| Arc::ptr_eq(current, &destination))
                        && association.attribution_ids.peek(&registration_id.request()) == Some(&registration_id)
                })
                && !matches!(
                    ripdpi_flow_app_attribution::lookup_registered_flow_uid(&registration_id),
                    ripdpi_flow_app_attribution::FlowUidLookup::Missing
                )
                && error_allowed(&registration_id)
            {
                enqueue_tun_packet(device, stats, raw);
            }
        }
        UdpEvent::SetupFailed { src, association_id } => {
            if associations.key_by_id(association_id).is_some_and(|key| key.src == src)
                && let Some(association) = associations.get_by_id_mut(association_id)
            {
                if let Some((registration_id, raw)) = association.pending_error.take()
                    && association.attribution_ids.peek(&registration_id.request()) == Some(&registration_id)
                    && !matches!(
                        ripdpi_flow_app_attribution::lookup_registered_flow_uid(&registration_id),
                        ripdpi_flow_app_attribution::FlowUidLookup::Missing
                    )
                    && error_allowed(&registration_id)
                {
                    enqueue_tun_packet(device, stats, raw);
                }
                if let Some(key) = associations.key_by_id(association_id).cloned() {
                    remove_association(associations, dns_cache, closed_sources, key);
                }
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if let Some(key) = associations.key_by_id(association_id).filter(|key| key.src == src).cloned() {
                remove_association(associations, dns_cache, closed_sources, key);
            }
        }
    }
}
