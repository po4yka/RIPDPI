use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tracing::warn;

use crate::dns_cache::DnsCache;
use crate::Stats;

pub(in crate::io_loop) fn resolve_mapped_target(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    dst: SocketAddr,
) -> Option<SocketAddr> {
    let Some(cache) = dns_cache.as_mut() else {
        return Some(dst);
    };
    let IpAddr::V4(v4) = dst.ip() else {
        return Some(dst);
    };
    let ip = u32::from(v4);
    if !cache.contains_mapped_ip(ip) {
        return Some(dst);
    }
    let Some(entry) = cache.lookup(ip) else {
        warn!("mapdns reverse lookup miss for synthetic target {}; dropping connection", dst);
        return None;
    };
    stats.record_last_host(Some(&entry.host));
    Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(entry.real_ip)), dst.port()))
}
