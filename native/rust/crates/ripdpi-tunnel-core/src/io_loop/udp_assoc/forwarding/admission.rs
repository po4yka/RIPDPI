use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use crate::dns_cache::DnsCache;
use crate::session::TargetAddr;

use super::super::association_state::{UDP_DESTINATION_CAPACITY, UdpAssociation, UdpDestination};
use super::super::destination_expiry::expire_udp_source_destinations_at;

#[allow(clippy::too_many_arguments)]
pub(super) fn prepare_udp_destination(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    cache: &mut Option<DnsCache>,
    src: SocketAddr,
    target: TargetAddr,
    resolved_dest: SocketAddr,
    response_source: SocketAddr,
    synthetic_ip: Option<u32>,
    idle_timeout: Duration,
    now: u64,
) -> Option<Arc<UdpDestination>> {
    let association = associations.get_mut(&src)?;
    let needs_expiry = association
        .destinations
        .get(&response_source)
        .map_or(association.destinations.len() >= UDP_DESTINATION_CAPACITY, |current| {
            !current.is_live() || current.is_idle_at(idle_timeout, now)
        });
    if needs_expiry {
        expire_udp_source_destinations_at(src, association, cache, idle_timeout, now);
    }
    if let Some(current) = association.destinations.get(&response_source) {
        return (current.is_live() && current.target == target && current.synthetic_ip == synthetic_ip)
            .then(|| Arc::clone(current));
    }
    (association.destinations.len() < UDP_DESTINATION_CAPACITY)
        .then(|| Arc::new(UdpDestination::new_at(target, resolved_dest, response_source, synthetic_ip, now)))
}

#[cfg(test)]
mod tests;
