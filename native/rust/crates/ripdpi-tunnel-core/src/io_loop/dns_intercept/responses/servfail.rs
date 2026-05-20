use std::net::SocketAddr;

use tracing::debug;

use crate::dns_cache::DnsCache;
use crate::TunDevice;

use super::super::super::bridge::enqueue_tun_packet;
use super::super::super::packet::build_udp_response;
use super::super::MapDnsRuntime;

pub(super) fn send_servfail(
    device: &mut TunDevice,
    mapdns: MapDnsRuntime,
    dns_cache: &DnsCache,
    dst: SocketAddr,
    query: &[u8],
    reason: &str,
) {
    match dns_cache.servfail_response(query) {
        Ok(servfail) => {
            let raw = build_udp_response(mapdns.intercept_addr, dst, &servfail);
            enqueue_tun_packet(device, raw, "dns-servfail");
        }
        Err(servfail_err) => debug!("failed to synthesize SERVFAIL after {reason}: {servfail_err}"),
    }
}
