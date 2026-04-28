//! Feature-gated backend that routes DoH and DoT queries through `hickory-resolver`
//! instead of the manual reqwest/tokio-rustls implementations.
//!
//! DNSCrypt always stays on the manual path because hickory-resolver does not support it.

use std::net::SocketAddr;
use std::time::Duration;

use hickory_proto::op::{Message, OpCode, ResponseCode};
use hickory_proto::rr::{Name, RecordType};
use hickory_resolver::config::{NameServerConfig, NameServerConfigGroup, ResolverConfig, ResolverOpts};
use hickory_resolver::name_server::TokioConnectionProvider;
use hickory_resolver::proto::xfer::Protocol;
use hickory_resolver::Resolver;

use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError};

/// Perform a DoH exchange via hickory-resolver.
pub(crate) async fn exchange_doh(
    endpoint: &EncryptedDnsEndpoint,
    query_bytes: &[u8],
    timeout: Duration,
) -> Result<Vec<u8>, EncryptedDnsError> {
    exchange_via_hickory(endpoint, query_bytes, timeout, Protocol::Https).await
}

/// Perform a DoT exchange via hickory-resolver.
pub(crate) async fn exchange_dot(
    endpoint: &EncryptedDnsEndpoint,
    query_bytes: &[u8],
    timeout: Duration,
) -> Result<Vec<u8>, EncryptedDnsError> {
    exchange_via_hickory(endpoint, query_bytes, timeout, Protocol::Tls).await
}

async fn exchange_via_hickory(
    endpoint: &EncryptedDnsEndpoint,
    query_bytes: &[u8],
    timeout: Duration,
    protocol: Protocol,
) -> Result<Vec<u8>, EncryptedDnsError> {
    // 1. Parse the incoming raw DNS query to extract the name and record type.
    let query_msg = Message::from_vec(query_bytes).map_err(|e| EncryptedDnsError::DnsParse(e.to_string()))?;
    let query = query_msg
        .queries
        .first()
        .ok_or_else(|| EncryptedDnsError::DnsParse("query contains no questions".to_string()))?;
    let name: Name = query.name().clone();
    let record_type: RecordType = query.query_type();

    // 2. Build NameServerConfig from endpoint bootstrap IPs.
    let tls_name = endpoint.tls_server_name.clone().unwrap_or_else(|| endpoint.host.clone());

    let servers: Vec<NameServerConfig> = endpoint
        .bootstrap_ips
        .iter()
        .map(|ip| {
            let mut ns = NameServerConfig::new(SocketAddr::new(*ip, endpoint.port), protocol);
            ns.tls_dns_name = Some(tls_name.clone());
            if matches!(protocol, Protocol::Https) {
                // Extract the path component from the DoH URL, or default to /dns-query.
                ns.http_endpoint = endpoint
                    .doh_url
                    .as_deref()
                    .and_then(|u| u.find("/dns-query").map(|idx| u[idx..].to_string()))
                    .or_else(|| Some("/dns-query".to_string()));
            }
            ns
        })
        .collect();

    let group = NameServerConfigGroup::from(servers);
    let config = ResolverConfig::from_parts(None, vec![], group);

    // 3. Build resolver with cache disabled and custom timeout.
    let mut opts = ResolverOpts::default();
    opts.timeout = timeout;
    opts.attempts = 1; // We handle retries at ResolverPool level.
    opts.cache_size = 0; // Disable cache -- we need raw bytes per query.
    opts.use_hosts_file = Default::default();
    opts.recursion_desired = query_msg.metadata.recursion_desired;

    let resolver = Resolver::builder_with_config(config, TokioConnectionProvider::default()).with_options(opts).build();

    // 4. Perform the lookup via hickory-resolver.
    let lookup =
        resolver.lookup(name.clone(), record_type).await.map_err(|e| EncryptedDnsError::Request(e.to_string()))?;

    // 5. Reconstruct a DNS wire-format response from the parsed records.
    //    This is the key challenge: hickory-resolver returns parsed Record objects,
    //    not raw bytes. We build a new Message preserving the original query ID.
    let mut response = Message::response(query_msg.metadata.id, OpCode::Query);
    response.metadata.recursion_desired = query_msg.metadata.recursion_desired;
    response.metadata.recursion_available = true;
    response.metadata.response_code = ResponseCode::NoError;

    // Copy original questions into response.
    for q in &query_msg.queries {
        response.add_query(q.clone());
    }

    // Copy answer records from the lookup result.
    for record in lookup.records() {
        response.add_answer(record.clone());
    }

    response.to_vec().map_err(|e| EncryptedDnsError::DnsParse(e.to_string()))
}
