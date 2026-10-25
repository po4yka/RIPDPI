use std::time::Duration;

use super::{DnsRequest, DnsResponse, direct_dns};
use ripdpi_dns_resolver::EncryptedDnsResolver;

/// # Cancel safety
///
/// cancel-safe: direct and encrypted exchanges own their request I/O and do
/// not publish cache/shared state. Cancellation drops that I/O before the
/// response is sent to the tunnel loop.
pub(super) async fn resolve_request(
    request: DnsRequest,
    resolver: &EncryptedDnsResolver,
    timeout: Duration,
) -> DnsResponse {
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
                    tcp_reply: request.tcp_reply,
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
                    tcp_reply: request.tcp_reply,
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
        tcp_reply: request.tcp_reply,
    }
}
