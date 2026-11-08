use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::time::Duration;

use crate::dns_cache::DnsCache;

use super::association_state::{UdpAssociation, now_millis};

pub(in crate::io_loop) fn expire_udp_destinations(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    cache: &mut Option<DnsCache>,
    idle_timeout: Duration,
) {
    expire_udp_destinations_at(associations, cache, idle_timeout, now_millis());
}

pub(super) fn expire_udp_destinations_at(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    cache: &mut Option<DnsCache>,
    idle_timeout: Duration,
    now: u64,
) {
    for (&src, association) in associations {
        expire_udp_source_destinations_at(src, association, cache, idle_timeout, now);
    }
}

pub(super) fn expire_udp_source_destinations_at(
    src: SocketAddr,
    association: &mut UdpAssociation,
    cache: &mut Option<DnsCache>,
    idle_timeout: Duration,
    now: u64,
) {
    let mut retired_ips = HashSet::new();
    let attribution_ids = &mut association.attribution_ids;
    association.destinations.retain(|&dst, destination| {
        if destination.is_live() && !destination.is_idle_at(idle_timeout, now) {
            return true;
        }
        destination.retire();
        if let Some(ip) = destination.synthetic_ip {
            retired_ips.insert(ip);
        }
        let request = ripdpi_flow_app_attribution::FlowResolveRequest { protocol: 17, local: src, remote: dst };
        if let Some(registration_id) = attribution_ids.pop(&request) {
            ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
        }
        false
    });
    for destination in association.destinations.values() {
        if let Some(ip) = destination.synthetic_ip {
            retired_ips.remove(&ip);
        }
    }
    for ip in retired_ips {
        if association.leased_synthetic_ips.remove(&ip)
            && let Some(cache) = cache.as_mut()
        {
            cache.unpin(ip);
        }
    }
}
