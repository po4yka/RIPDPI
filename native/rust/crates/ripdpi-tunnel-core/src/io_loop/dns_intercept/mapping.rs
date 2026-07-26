use std::net::SocketAddr;
#[cfg(test)]
use std::sync::Arc;

#[cfg(test)]
use crate::Stats;
#[cfg(test)]
use crate::dns_cache::DnsCache;

#[cfg(test)]
use super::resolve_mapped_destination;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(in crate::io_loop) struct ResolvedMappedTarget {
    pub(in crate::io_loop) addr: SocketAddr,
    pub(in crate::io_loop) host: Option<String>,
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
