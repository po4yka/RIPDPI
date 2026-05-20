mod servfail;

use std::sync::Arc;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::bridge::enqueue_tun_packet;
use super::super::packet::build_udp_response;
use super::{DnsResponse, MapDnsRuntime};
use servfail::send_servfail;

pub(in crate::io_loop) fn handle_dns_result(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dns_cache: &mut DnsCache,
    response: DnsResponse,
) {
    match response.upstream {
        Ok(upstream) => match dns_cache.rewrite_response(&response.query, &upstream.response_bytes) {
            Ok(result) => {
                stats.record_dns_success(
                    &result.host,
                    result.cache_hits,
                    result.cache_misses,
                    Some(&upstream.endpoint_label),
                    Some(upstream.latency_ms),
                );
                let raw = build_udp_response(mapdns.intercept_addr, response.src, &result.response);
                enqueue_tun_packet(device, raw, "dns");
            }
            Err(err) => {
                let message = err.to_string();
                stats.record_dns_failure(response.host.as_deref(), &message, Some(&upstream.endpoint_label));
                send_servfail(device, mapdns, dns_cache, response.src, &response.query, "rewrite error");
            }
        },
        Err(err) => {
            let formatted_error = match response.resolver_error_kind {
                Some(kind) => format!("{kind:?}: {err}"),
                None => err,
            };
            stats.record_dns_failure(
                response.host.as_deref(),
                &formatted_error,
                response.resolver_endpoint_label.as_deref(),
            );
            send_servfail(device, mapdns, dns_cache, response.src, &response.query, "upstream failure");
        }
    }
}
