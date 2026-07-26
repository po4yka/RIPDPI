use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tracing::warn;

use crate::Stats;
use crate::dns_cache::DnsCache;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(in crate::io_loop) struct ResolvedMappedTarget {
    pub(in crate::io_loop) addr: SocketAddr,
    pub(in crate::io_loop) host: Option<String>,
}

pub(in crate::io_loop) fn resolve_mapped_destination(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    active_direct_generation: Option<&mut Option<u64>>,
    dst: SocketAddr,
) -> Option<ResolvedMappedTarget> {
    let Some(cache) = dns_cache.as_mut() else {
        return Some(ResolvedMappedTarget { addr: dst, host: None });
    };
    let IpAddr::V4(v4) = dst.ip() else {
        return Some(ResolvedMappedTarget { addr: dst, host: None });
    };
    let ip = u32::from(v4);
    if !cache.contains_mapped_ip(ip) {
        return Some(ResolvedMappedTarget { addr: dst, host: None });
    }
    if let Some(active_generation) = active_direct_generation {
        sync_direct_dns_mapping_generation(Some(cache), active_generation);
    }
    let Some(entry) = cache.lookup(ip) else {
        warn!("mapdns reverse lookup miss for synthetic target {}; dropping connection", dst);
        return None;
    };
    stats.record_last_host(Some(&entry.host));
    Some(ResolvedMappedTarget {
        addr: SocketAddr::new(IpAddr::V4(Ipv4Addr::from(entry.real_ip)), dst.port()),
        host: Some(entry.host),
    })
}

#[cfg(test)]
pub(in crate::io_loop) fn resolve_mapped_target(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    active_direct_generation: Option<&mut Option<u64>>,
    dst: SocketAddr,
) -> Option<SocketAddr> {
    resolve_mapped_destination(stats, dns_cache, active_direct_generation, dst).map(|target| target.addr)
}

pub(in crate::io_loop) fn sync_direct_dns_mapping_generation(
    cache: Option<&mut DnsCache>,
    active_generation: &mut Option<u64>,
) {
    let current_generation = crate::tunnel_api::direct_dns_binding::current_direct_dns_generation();
    if *active_generation == current_generation {
        return;
    }
    if let Some(cache) = cache {
        cache.reset_unleased();
    }
    *active_generation = current_generation;
}
