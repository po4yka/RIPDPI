mod config;
mod direct_dns;
mod mapping;
mod protect_hooks;
mod responses;
mod types;
mod wire;
mod worker;

use std::net::SocketAddr;

#[cfg(test)]
pub(super) use self::config::mapdns_resolver_transport;
pub(super) use self::config::{build_encrypted_dns_resolver, parse_dns_cache, parse_mapdns_runtime};
pub(super) use self::mapping::{resolve_mapped_target, sync_direct_dns_mapping_generation};
pub(super) use self::responses::handle_dns_result;
pub(super) use self::types::{DirectDnsRequest, DnsRequest, DnsResponse};
pub(super) use self::wire::dns_query_name;
pub(crate) use self::wire::parse_dns_query;
pub(super) use self::worker::{drain_dns_responses, handle_dns_response, route_dns_packet, spawn_dns_worker};

#[derive(Debug, Clone, Copy)]
pub(super) struct MapDnsRuntime {
    pub(super) intercept_addr: SocketAddr,
    pub(super) synthetic_net: u32,
    pub(super) synthetic_mask: u32,
    pub(super) intercept_port: u16,
}

#[cfg(test)]
mod tests;
