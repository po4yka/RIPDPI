use std::net::SocketAddr;
use std::sync::Arc;

use tracing::debug;

use crate::dns_cache::DnsCache;
use crate::split_dns::{DnsPolicyPlane, SplitDnsPolicy};
use crate::stats::SplitDnsDecisionKind;
use crate::{Stats, TunDevice};

use super::super::send_dns_servfail;
use super::{DirectDnsRequest, DnsRequest, MapDnsRuntime, parse_dns_query};

#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) fn route_dns_packet(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns_runtime: Option<MapDnsRuntime>,
    dns_cache: Option<&DnsCache>,
    split_dns_policy: Option<&SplitDnsPolicy>,
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<super::DnsResponse>>,
    src: SocketAddr,
    payload: &[u8],
    host: Option<String>,
) {
    let mut direct = None;
    if let Some(policy) = split_dns_policy {
        let parsed = parse_dns_query(payload);
        let decision = policy.evaluate(parsed.as_ref().map_or("", |query| query.host.as_str()));
        if let Some(reason) = decision.reason {
            debug!(reason, "split DNS request kept on encrypted proxy plane");
        }
        if decision.plane == DnsPolicyPlane::Block {
            stats.record_split_dns_decision(SplitDnsDecisionKind::Block, decision.reason);
            if let (Some(mapdns), Some(parsed)) = (mapdns_runtime, parsed) {
                match parsed.refused_response() {
                    Ok(refused) => {
                        let raw = super::super::packet::build_udp_response(mapdns.intercept_addr, src, &refused);
                        super::super::bridge::enqueue_tun_packet(device, raw);
                    }
                    Err(error) => {
                        stats.record_dns_response_failure("failed to encode split DNS REFUSED response");
                        if let Some(cache) = dns_cache {
                            match cache.servfail_response(payload) {
                                Ok(servfail) => {
                                    let raw =
                                        super::super::packet::build_udp_response(mapdns.intercept_addr, src, &servfail);
                                    super::super::bridge::enqueue_tun_packet(device, raw);
                                }
                                Err(servfail_error) => debug!(%servfail_error, "dropping split DNS block response"),
                            }
                        } else {
                            debug!(%error, "dropping split DNS block response without DNS cache");
                        }
                    }
                }
            }
            return;
        }
        direct = if decision.plane == DnsPolicyPlane::Direct {
            crate::tunnel_api::direct_dns_binding::current_direct_dns_generation().map(|generation| DirectDnsRequest {
                generation,
                candidates: policy.direct_resolver_candidates().to_vec().into_boxed_slice(),
            })
        } else {
            None
        };
        if decision.plane == DnsPolicyPlane::Direct && direct.is_none() {
            stats.record_split_dns_decision(
                SplitDnsDecisionKind::DirectProxyFallback,
                Some("direct_underlay_unavailable"),
            );
            if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache) {
                send_dns_servfail(
                    device,
                    stats,
                    mapdns,
                    cache,
                    src,
                    payload,
                    host.as_deref(),
                    "direct DNS underlay unavailable",
                );
            }
            return;
        }
        if decision.plane != DnsPolicyPlane::Direct {
            stats.record_split_dns_decision(SplitDnsDecisionKind::ProxyEncrypted, decision.reason);
        }
    }
    match (&mapdns_runtime, dns_cache, dns_req_tx.as_ref()) {
        (Some(_), Some(_), Some(request_tx)) => {
            let request = DnsRequest { src, query: payload.to_vec(), host, direct };
            match request_tx.try_send(request) {
                Ok(()) => {}
                Err(tokio::sync::mpsc::error::TrySendError::Full(request)) => {
                    if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache) {
                        send_dns_servfail(
                            device,
                            stats,
                            mapdns,
                            cache,
                            request.src,
                            &request.query,
                            request.host.as_deref(),
                            "dns worker queue full",
                        );
                    }
                }
                Err(tokio::sync::mpsc::error::TrySendError::Closed(request)) => {
                    if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache) {
                        send_dns_servfail(
                            device,
                            stats,
                            mapdns,
                            cache,
                            request.src,
                            &request.query,
                            request.host.as_deref(),
                            "dns worker unavailable",
                        );
                    }
                    *dns_req_tx = None;
                    *dns_resp_rx = None;
                }
            }
        }
        (Some(mapdns), Some(cache), None) => {
            send_dns_servfail(
                device,
                stats,
                *mapdns,
                cache,
                src,
                payload,
                host.as_deref(),
                "encrypted DNS resolver is not configured",
            );
        }
        _ => {
            debug!("DNS intercept hit without mapdns runtime; dropping packet");
        }
    }
}
