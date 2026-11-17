use std::net::SocketAddr;
use std::sync::Arc;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::super::bridge::enqueue_tun_packet;
use super::super::super::packet::build_udp_response;
use super::super::DnsResponse;
use super::servfail::servfail_payload;
use super::udp_fit::fit_udp_reply;

pub(in crate::io_loop) fn handle_dns_failure(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    dns_cache: &DnsCache,
    mut response: DnsResponse,
    reason: &str,
) {
    if let Some(payload) = servfail_payload(dns_cache, &response.query, reason) {
        deliver_dns_payload(
            device,
            stats,
            &response.query,
            response.src,
            response.dst,
            response.tcp_reply.take(),
            payload,
        );
    }
}

pub(super) fn deliver_dns_payload(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    query: &[u8],
    client: SocketAddr,
    server: SocketAddr,
    tcp_reply: Option<tokio::sync::oneshot::Sender<Vec<u8>>>,
    payload: Vec<u8>,
) {
    if let Some(reply) = tcp_reply {
        let _ = reply.send(payload);
    } else if let Some(payload) = fit_udp_reply(query, payload, device.mtu) {
        let raw = build_udp_response(server, client, &payload);
        enqueue_tun_packet(device, stats, raw);
    }
}
