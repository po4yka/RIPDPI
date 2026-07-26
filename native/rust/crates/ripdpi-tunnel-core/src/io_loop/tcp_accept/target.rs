use std::net::IpAddr;
#[cfg(test)]
use std::net::SocketAddr;
use std::sync::Arc;

use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::Stats;
use crate::dns_cache::DnsCache;

#[cfg(test)]
use crate::io_loop::dns_intercept::resolve_mapped_target;
use crate::io_loop::dns_intercept::{ResolvedMappedTarget, resolve_mapped_destination};

use super::tcp_target_endpoint;

#[cfg(test)]
pub(crate) fn tcp_session_target_addr(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    active_direct_generation: Option<&mut Option<u64>>,
    tcp: &TcpSocket,
) -> Option<SocketAddr> {
    tcp_target_endpoint(tcp)
        .and_then(|target| resolve_mapped_target(stats, dns_cache, active_direct_generation, target))
}

pub(super) fn tcp_session_target(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    active_direct_generation: Option<&mut Option<u64>>,
    tcp: &TcpSocket,
) -> Option<ResolvedMappedTarget> {
    tcp_target_endpoint(tcp)
        .and_then(|target| resolve_mapped_destination(stats, dns_cache, active_direct_generation, target))
}

pub(super) fn pinned_synthetic_ip(dns_cache: &Option<DnsCache>, tcp: &TcpSocket) -> Option<u32> {
    tcp_target_endpoint(tcp).and_then(|sa| match sa.ip() {
        IpAddr::V4(v4) => {
            let ip = u32::from(v4);
            dns_cache.as_ref()?.contains_mapped_ip(ip).then_some(ip)
        }
        IpAddr::V6(_) => None,
    })
}

pub(super) fn pin_synthetic_ip(dns_cache: &mut Option<DnsCache>, synthetic_ip: Option<u32>) {
    if let (Some(cache), Some(ip)) = (dns_cache.as_mut(), synthetic_ip) {
        cache.pin(ip);
    }
}
