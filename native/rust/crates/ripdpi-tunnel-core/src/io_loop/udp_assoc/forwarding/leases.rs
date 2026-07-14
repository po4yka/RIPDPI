use std::collections::HashMap;
use std::net::SocketAddr;

use crate::dns_cache::DnsCache;

use super::super::association_state::UdpAssociation;

pub(super) fn lease_udp_attribution(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    src: SocketAddr,
    token: ripdpi_flow_app_attribution::FlowAttributionToken,
) {
    if let Some(association) = associations.get_mut(&src) {
        association.attribution_tokens.insert(token);
    }
}

pub(in crate::io_loop::udp_assoc) fn lease_udp_mapping(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    dns_cache: &mut Option<DnsCache>,
    src: SocketAddr,
    synthetic_ip: Option<u32>,
) {
    let (Some(association), Some(cache), Some(ip)) = (associations.get_mut(&src), dns_cache.as_mut(), synthetic_ip)
    else {
        return;
    };
    if association.leased_synthetic_ips.insert(ip) && !cache.pin(ip) {
        association.leased_synthetic_ips.remove(&ip);
    }
}
