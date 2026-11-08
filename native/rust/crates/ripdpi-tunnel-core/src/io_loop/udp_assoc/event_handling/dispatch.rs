use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap};
use std::net::SocketAddr;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::super::bridge::enqueue_tun_packet;
use super::super::association_removal::remove_association;
use super::super::association_state::UdpAssociation;
use super::super::eviction::{UdpEvictionEntry, record_udp_activity};
use super::event::UdpEvent;

#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) fn handle_udp_event(
    device: &mut TunDevice,
    stats: &Stats,
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
    event: UdpEvent,
    mut error_allowed: impl FnMut(&ripdpi_flow_app_attribution::FlowRegistrationId) -> bool,
) {
    match event {
        UdpEvent::Packet { src, association_id, raw } => {
            if associations.get(&src).is_some_and(|association| association.id == association_id) {
                record_udp_activity(associations, eviction_heap, src);
                enqueue_tun_packet(device, stats, raw);
            }
        }
        UdpEvent::SetupFailed { src, association_id } => {
            if let Some(association) = associations.get_mut(&src).filter(|association| association.id == association_id)
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
                remove_association(associations, dns_cache, closed_sources, src);
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if associations.get(&src).is_some_and(|association| association.id == association_id) {
                remove_association(associations, dns_cache, closed_sources, src);
            }
        }
    }
}
