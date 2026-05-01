mod config;
mod mapping;
mod responses;
mod wire;
mod worker;

use std::net::SocketAddr;

use ripdpi_dns_resolver::{EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess};

pub(super) use self::config::{build_encrypted_dns_resolver, parse_dns_cache, parse_mapdns_runtime};
pub(super) use self::mapping::resolve_mapped_target;
pub(super) use self::responses::handle_dns_result;
pub(super) use self::wire::dns_query_name;
pub(super) use self::worker::{drain_dns_responses, route_dns_packet, spawn_dns_worker};

#[derive(Debug, Clone, Copy)]
pub(super) struct MapDnsRuntime {
    pub(super) intercept_addr: SocketAddr,
    pub(super) synthetic_net: u32,
    pub(super) synthetic_mask: u32,
    pub(super) intercept_port: u16,
}

#[derive(Debug, Clone)]
pub(super) struct DnsRequest {
    pub(super) src: SocketAddr,
    pub(super) query: Vec<u8>,
    pub(super) host: Option<String>,
}

#[derive(Debug, Clone)]
pub(super) struct DnsResponse {
    pub(super) src: SocketAddr,
    pub(super) query: Vec<u8>,
    pub(super) host: Option<String>,
    pub(super) upstream: Result<EncryptedDnsExchangeSuccess, String>,
    pub(super) resolver_error_kind: Option<EncryptedDnsErrorKind>,
}

#[cfg(test)]
mod tests;
