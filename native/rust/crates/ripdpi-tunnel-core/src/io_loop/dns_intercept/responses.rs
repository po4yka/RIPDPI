mod delivery;
mod servfail;

use std::sync::Arc;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::{DnsResponse, MapDnsRuntime};
use delivery::deliver_dns_payload;
pub(in crate::io_loop) use delivery::handle_dns_failure;
use servfail::servfail_payload;

pub(in crate::io_loop) fn handle_dns_result(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dns_cache: &mut DnsCache,
    mut response: DnsResponse,
) {
    let payload = match response.upstream {
        Ok(upstream) => match dns_cache.rewrite_response(&response.query, &upstream.response_bytes) {
            Ok(result) => {
                stats.record_dns_success(
                    &result.host,
                    result.cache_hits,
                    result.cache_misses,
                    Some(&upstream.endpoint_label),
                    Some(upstream.latency_ms),
                );
                Some(result.response)
            }
            Err(err) => {
                let message = err.to_string();
                stats.record_dns_failure(response.host.as_deref(), &message, Some(&upstream.endpoint_label));
                servfail_payload(dns_cache, &response.query, "rewrite error")
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
            servfail_payload(dns_cache, &response.query, "upstream failure")
        }
    };
    if let Some(payload) = payload {
        deliver_dns_payload(device, mapdns, response.src, response.tcp_reply.take(), payload);
    }
}
