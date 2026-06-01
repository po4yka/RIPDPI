use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_dns_resolver::EncryptedDnsResolver;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::super::send_dns_servfail;
use super::{DnsRequest, DnsResponse, MapDnsRuntime, handle_dns_result};

pub(in crate::io_loop) fn spawn_dns_worker(
    resolver: EncryptedDnsResolver,
    cancel: CancellationToken,
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
                    let resolver_endpoint_label = resolver.endpoint_label();
                    let upstream =
                        resolver
                            .exchange_with_metadata(&request.query)
                            .await
                            .map_err(|err| {
                                let kind = err.kind();
                                (kind, err.to_string())
                            });
                    let (resolver_error_kind, upstream) = match upstream {
                        Ok(success) => (None, Ok(success)),
                        Err((kind, message)) => (Some(kind), Err(message)),
                    };
                    if resp_tx.send(DnsResponse {
                        src: request.src,
                        query: request.query,
                        host: request.host,
                        upstream,
                        resolver_error_kind,
                        resolver_endpoint_label: Some(resolver_endpoint_label),
                    }).await.is_err() {
                        break;
                    }
                }
            }
        }
    });
    (req_tx, resp_rx)
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
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
    src: SocketAddr,
    payload: &[u8],
    host: Option<String>,
) {
    let request = DnsRequest { src, query: payload.to_vec(), host };
    match (&mapdns_runtime, dns_cache, dns_req_tx.as_ref()) {
        (Some(_), Some(_), Some(request_tx)) => match request_tx.try_send(request) {
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
        },
        (Some(mapdns), Some(cache), None) => {
            send_dns_servfail(
                device,
                stats,
                *mapdns,
                cache,
                src,
                payload,
                request.host.as_deref(),
                "encrypted DNS resolver is not configured",
            );
        }
        _ => {
            debug!("DNS intercept hit without mapdns runtime; dropping packet");
        }
    }
}

/// Drain all pending DNS responses from the receiver channel and process them.
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
) {
    loop {
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
        handle_dns_result(device, stats, mapdns, cache, response);
    }
}
