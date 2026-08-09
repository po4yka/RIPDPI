//! Feature-gated backend that routes DoH and DoT queries through `hickory-resolver`
//! instead of the manual reqwest/tokio-rustls implementations.
//!
//! DNSCrypt always stays on the manual path because hickory-resolver does not support it.

use std::sync::Arc;
use std::time::Duration;

use hickory_proto::op::{Message, OpCode, ResponseCode};
use hickory_proto::rr::{Name, RecordType};
use hickory_resolver::Resolver;
use hickory_resolver::config::{ConnectionConfig, NameServerConfig, ResolveHosts, ResolverConfig, ResolverOpts};
use hickory_resolver::lookup::Lookup;
use hickory_resolver::net::runtime::TokioRuntimeProvider;
use url::Url;

use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError};

#[derive(Clone, Copy)]
enum HickoryProtocol {
    Doh,
    Dot,
}

/// Perform a DoH exchange via hickory-resolver.
pub(crate) async fn exchange_doh(
    endpoint: &EncryptedDnsEndpoint,
    query_bytes: &[u8],
    timeout: Duration,
) -> Result<Vec<u8>, EncryptedDnsError> {
    exchange_via_hickory(endpoint, query_bytes, timeout, HickoryProtocol::Doh).await
}

/// Perform a DoT exchange via hickory-resolver.
pub(crate) async fn exchange_dot(
    endpoint: &EncryptedDnsEndpoint,
    query_bytes: &[u8],
    timeout: Duration,
) -> Result<Vec<u8>, EncryptedDnsError> {
    exchange_via_hickory(endpoint, query_bytes, timeout, HickoryProtocol::Dot).await
}

async fn exchange_via_hickory(
    endpoint: &EncryptedDnsEndpoint,
    query_bytes: &[u8],
    timeout: Duration,
    protocol: HickoryProtocol,
) -> Result<Vec<u8>, EncryptedDnsError> {
    // 1. Parse the incoming raw DNS query to extract the name and record type.
    let query_msg = Message::from_vec(query_bytes).map_err(|e| EncryptedDnsError::DnsParse(e.to_string()))?;
    let query = query_msg
        .queries
        .first()
        .ok_or_else(|| EncryptedDnsError::DnsParse("query contains no questions".to_string()))?;
    let name: Name = query.name.clone();
    let record_type: RecordType = query.query_type;

    // 2. Build NameServerConfig from endpoint bootstrap IPs.
    if endpoint.bootstrap_ips.is_empty() {
        return Err(EncryptedDnsError::InvalidEndpoint("hickory backend requires bootstrap IPs".to_string()));
    }
    let tls_name = endpoint.tls_server_name.clone().unwrap_or_else(|| endpoint.host.clone());

    let servers: Vec<NameServerConfig> = endpoint
        .bootstrap_ips
        .iter()
        .map(|ip| {
            let mut connection = match protocol {
                HickoryProtocol::Doh => ConnectionConfig::https(
                    Arc::from(tls_name.as_str()),
                    Some(Arc::from(doh_path(endpoint.doh_url.as_deref()).as_str())),
                ),
                HickoryProtocol::Dot => ConnectionConfig::tls(Arc::from(tls_name.as_str())),
            };
            connection.port = endpoint.port;
            NameServerConfig::new(*ip, true, vec![connection])
        })
        .collect();

    let config = ResolverConfig::from_parts(None, vec![], servers);

    // 3. Build resolver with cache disabled and custom timeout.
    let mut opts = ResolverOpts::default();
    opts.timeout = timeout;
    opts.attempts = 1; // We handle retries at ResolverPool level.
    opts.cache_size = 0; // Disable cache -- we need raw bytes per query.
    opts.use_hosts_file = ResolveHosts::default();
    opts.recursion_desired = query_msg.metadata.recursion_desired;

    let resolver = Resolver::builder_with_config(config, TokioRuntimeProvider::default())
        .with_options(opts)
        .build()
        .map_err(|e| EncryptedDnsError::Request(e.to_string()))?;

    // 4. Perform the lookup via hickory-resolver.
    let lookup: Lookup =
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
    response.add_answers(lookup.answers().iter().cloned());
    response.add_authorities(lookup.authorities().iter().cloned());
    response.add_additionals(lookup.additionals().iter().cloned());

    response.to_vec().map_err(|e| EncryptedDnsError::DnsParse(e.to_string()))
}

fn doh_path(doh_url: Option<&str>) -> String {
    let Some(url) = doh_url.and_then(|value| Url::parse(value).ok()) else {
        return "/dns-query".to_string();
    };
    let path = url.path();
    if path.is_empty() || path == "/" { "/dns-query".to_string() } else { path.to_string() }
}
