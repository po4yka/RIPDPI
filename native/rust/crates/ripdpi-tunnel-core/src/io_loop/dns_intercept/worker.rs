use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsResolver;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::dns_cache::DnsCache;
use crate::split_dns::{DnsPolicyPlane, SplitDnsPolicy};
use crate::stats::SplitDnsDecisionKind;
use crate::{Stats, TunDevice};

use super::super::IO_PHASE_WORK_BUDGET;

use super::super::send_dns_servfail;
use super::{
    DirectDnsRequest, DnsRequest, DnsResponse, MapDnsRuntime, direct_dns, handle_dns_result, parse_dns_query,
    sync_direct_dns_mapping_generation,
};

pub(in crate::io_loop) fn spawn_dns_worker(
    resolver: EncryptedDnsResolver,
    cancel: CancellationToken,
    timeout: Duration,
) -> (tokio::sync::mpsc::Sender<DnsRequest>, tokio::sync::mpsc::Receiver<DnsResponse>) {
    let (req_tx, mut req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(super::super::DNS_QUEUE_CAPACITY);
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(super::super::DNS_QUEUE_CAPACITY);
    tokio::spawn(async move {
        loop {
            // biased; keeps the cancellation arm in pole position so the worker stops
            // promptly on shutdown instead of draining queued DNS requests first.
            tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                request = req_rx.recv() => {
                    let Some(request) = request else {
                        break;
                    };
                    let response = tokio::select! {
                        biased;
                        _ = cancel.cancelled() => break,
                        response = resolve_request(request, &resolver, timeout) => response,
                    };
                    tokio::select! {
                        biased;
                        _ = cancel.cancelled() => break,
                        sent = resp_tx.send(response) => {
                            if sent.is_err() {
                                break;
                            }
                        }
                    }
                }
            }
        }
    });
    (req_tx, resp_rx)
}

/// # Cancel safety
///
/// cancel-safe: direct and encrypted exchanges own their request I/O and do
/// not publish cache/shared state. Cancellation drops that I/O before the
/// response is sent to the tunnel loop.
async fn resolve_request(request: DnsRequest, resolver: &EncryptedDnsResolver, timeout: Duration) -> DnsResponse {
    let resolver_endpoint_label = resolver.endpoint_label();
    let request_generation = request.direct.as_ref().map(|direct| direct.generation);
    if let Some(direct) = request.direct.as_ref() {
        match direct_dns::exchange(&request.query, &direct.candidates, direct.generation, timeout).await {
            Ok(success) => {
                return DnsResponse {
                    src: request.src,
                    query: request.query,
                    host: request.host,
                    upstream: Ok(success),
                    resolver_error_kind: None,
                    resolver_endpoint_label: Some(resolver_endpoint_label),
                    direct_generation: request_generation,
                    direct_fallback: false,
                };
            }
            Err(error) => {
                return DnsResponse {
                    src: request.src,
                    query: request.query,
                    host: request.host,
                    upstream: Err(error.to_string()),
                    resolver_error_kind: None,
                    resolver_endpoint_label: Some(resolver_endpoint_label),
                    direct_generation: request_generation,
                    direct_fallback: false,
                };
            }
        }
    }
    let (direct_fallback, upstream) =
        (false, resolver.exchange_with_metadata(&request.query).await.map_err(|err| (err.kind(), err.to_string())));
    let (resolver_error_kind, upstream) = match upstream {
        Ok(success) => (None, Ok(success)),
        Err((kind, message)) => (Some(kind), Err(message)),
    };
    DnsResponse {
        src: request.src,
        query: request.query,
        host: request.host,
        upstream,
        resolver_error_kind,
        resolver_endpoint_label: Some(resolver_endpoint_label),
        direct_generation: request_generation,
        direct_fallback,
    }
}

/// Route a DNS query packet: enqueue to the resolver channel, or send SERVFAIL
/// if the channel is full/closed or the resolver is not configured.
///
/// When the resolver channel is closed, `dns_req_tx` and `dns_resp_rx` are set
/// to `None` so that the caller stops attempting to send further queries.
#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) fn route_dns_packet(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns_runtime: Option<MapDnsRuntime>,
    dns_cache: Option<&DnsCache>,
    split_dns_policy: Option<&SplitDnsPolicy>,
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
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

/// Process one bounded batch of pending DNS responses from the receiver channel.
///
/// When the channel is disconnected, `dns_req_tx` and `dns_resp_rx` are set
/// to `None`.
pub(in crate::io_loop) fn drain_dns_responses(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &mut DnsCache,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    active_direct_generation: &mut Option<u64>,
) {
    for _ in 0..IO_PHASE_WORK_BUDGET {
        let dns_response = match dns_resp_rx.as_mut() {
            Some(receiver) => match receiver.try_recv() {
                Ok(response) => Some(response),
                Err(tokio::sync::mpsc::error::TryRecvError::Empty) => None,
                Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                    stats.record_dns_failure(None, "dns worker exited unexpectedly", None);
                    *dns_req_tx = None;
                    *dns_resp_rx = None;
                    None
                }
            },
            None => None,
        };
        let Some(response) = dns_response else {
            break;
        };
        handle_dns_response(device, stats, mapdns, cache, active_direct_generation, response);
    }
}

pub(in crate::io_loop) fn handle_dns_response(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &mut DnsCache,
    active_direct_generation: &mut Option<u64>,
    response: DnsResponse,
) {
    sync_direct_dns_mapping_generation(Some(cache), active_direct_generation);
    if response.direct_fallback {
        stats.record_split_dns_decision(SplitDnsDecisionKind::DirectProxyFallback, Some("direct_transport_failed"));
    }
    if response
        .direct_generation
        .is_some_and(|generation| !crate::tunnel_api::direct_dns_binding::is_direct_dns_generation_current(generation))
    {
        stats.record_direct_dns_stale_response();
        cache.reset_unleased();
        send_dns_servfail(
            device,
            stats,
            mapdns,
            cache,
            response.src,
            &response.query,
            response.host.as_deref(),
            "direct DNS generation stale",
        );
    } else {
        if response.direct_generation.is_some() && !response.direct_fallback && response.upstream.is_ok() {
            stats.record_direct_dns_success();
        }
        if let Some(generation) = response.direct_generation
            && active_direct_generation.replace(generation) != Some(generation)
        {
            cache.reset_unleased();
        }
        handle_dns_result(device, stats, mapdns, cache, response);
    }
}
