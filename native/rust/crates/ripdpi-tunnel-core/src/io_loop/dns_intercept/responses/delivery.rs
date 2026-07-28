use std::sync::Arc;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::super::bridge::enqueue_tun_packet;
use super::super::super::packet::build_udp_response;
use super::super::{DnsResponse, MapDnsRuntime};
use super::servfail::servfail_payload;

pub(in crate::io_loop) fn handle_dns_failure(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dns_cache: &DnsCache,
    mut response: DnsResponse,
    reason: &str,
) {
    stats.record_dns_failure(response.host.as_deref(), reason, None);
    if let Some(payload) = servfail_payload(dns_cache, &response.query, reason) {
        deliver_dns_payload(device, stats, mapdns, response.src, response.tcp_reply.take(), payload);
    }
}

pub(super) fn deliver_dns_payload(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dst: std::net::SocketAddr,
    tcp_reply: Option<tokio::sync::oneshot::Sender<Vec<u8>>>,
    payload: Vec<u8>,
) {
    if let Some(reply) = tcp_reply {
        let _ = reply.send(payload);
    } else {
        let raw = build_udp_response(mapdns.intercept_addr, dst, &payload);
        enqueue_tun_packet(device, stats, raw);
    }
}
