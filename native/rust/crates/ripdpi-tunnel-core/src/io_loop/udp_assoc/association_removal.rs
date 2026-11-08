use crate::io_loop::udp_assoc::UdpAssociations;
use std::net::SocketAddr;

use crate::dns_cache::DnsCache;

use super::association_state::UdpAssociationKey;

pub(super) fn remove_association(
    associations: &mut UdpAssociations,
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
    key: impl Into<UdpAssociationKey>,
) {
    let key = key.into();
    if let Some(association) = associations.remove(&key) {
        if !associations.contains_key(&key.src) {
            closed_sources.push(key.src);
        }
        association.cancel.cancel();
        for destination in association.destinations.values() {
            destination.retire();
        }
        if let Some(cache) = dns_cache.as_mut() {
            for ip in association.leased_synthetic_ips {
                cache.unpin(ip);
            }
        }
        for (_request, registration_id) in association.attribution_ids {
            ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
        }
    }
}
