use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsResolver;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;

use super::worker::resolve_request;
use super::{DnsRequest, DnsResponse};

/// Upper bound on lookups in flight at once, so one slow upstream cannot stall
/// every other query while a flood of queries still cannot spawn without limit.
const MAX_IN_FLIGHT_DNS_REQUESTS: usize = 32;

pub(in crate::io_loop) fn spawn_dns_worker(
    resolver: EncryptedDnsResolver,
    cancel: CancellationToken,
    timeout: Duration,
) -> (tokio::sync::mpsc::Sender<DnsRequest>, tokio::sync::mpsc::Receiver<DnsResponse>) {
    let (req_tx, mut req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(super::super::DNS_QUEUE_CAPACITY);
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(super::super::DNS_QUEUE_CAPACITY);
    tokio::spawn(async move {
        // Dropping the set on exit aborts lookups that are still in flight.
        let mut in_flight = JoinSet::new();
        loop {
            // biased; keeps the cancellation arm in pole position so the worker stops
            // promptly on shutdown instead of draining queued DNS requests first.
            tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                _ = resp_tx.closed() => break,
                Some(joined) = in_flight.join_next(), if !in_flight.is_empty() => {
                    if let Err(error) = joined
                        && error.is_panic()
                    {
                        tracing::warn!("DNS lookup task panicked; its query gets no response: {error}");
                    }
                }
                request = req_rx.recv(), if in_flight.len() < MAX_IN_FLIGHT_DNS_REQUESTS => {
                    let Some(request) = request else {
                        break;
                    };
                    in_flight.spawn(resolve_and_send(request, resolver.clone(), timeout, resp_tx.clone()));
                }
            }
        }
    });
    (req_tx, resp_rx)
}

/// # Cancel safety
///
/// cancel-safe: the lookup owns its request I/O (see `resolve_request`), and an
/// aborted send drops only this lookup's response.
async fn resolve_and_send(
    request: DnsRequest,
    resolver: EncryptedDnsResolver,
    timeout: Duration,
    resp_tx: tokio::sync::mpsc::Sender<DnsResponse>,
) {
    let response = resolve_request(request, &resolver, timeout).await;
    // A closed receiver means the tunnel loop is gone; the dispatcher stops on it too.
    let _ = resp_tx.send(response).await;
}
