use std::collections::HashMap;
use std::net::SocketAddr;

use crate::dns_cache::DnsCache;

use super::association_state::UdpAssociation;

pub(super) fn remove_association(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    dns_cache: &mut Option<DnsCache>,
    src: SocketAddr,
) {
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
        if let Some(cache) = dns_cache.as_mut() {
            for ip in association.leased_synthetic_ips {
                cache.unpin(ip);
            }
        }
        // Drop the per-app attribution cache entry so a later flow to the same
        // destination (possibly a different app) re-resolves its owner.
        ripdpi_flow_app_attribution::evict_flow(association.dest.ip());
    }
}
