use std::net::{IpAddr, SocketAddr};

use crate::probe_context::ProbeExecutionContext;
use crate::strategy::adapters::dns::resolve_via_encrypted_dns;
use crate::strategy::adapters::dns_oracle::{
    DnsOracleAssessment, DnsOracleConfig, DnsOracleResponse, evaluate_dns_oracles,
};
use crate::strategy::adapters::transport::{DnsResolveError, TargetAddress, domain_connect_target, resolve_addresses};
use crate::types::DomainTarget;

pub(super) struct SystemDnsResolution {
    pub(super) targets: Vec<SocketAddr>,
    pub(super) latency_ms: String,
    pub(super) error_kind: Option<DnsResolveError>,
}

impl SystemDnsResolution {
    pub(super) fn failed(&self) -> bool {
        self.targets.is_empty()
    }

    pub(super) fn addresses_detail(&self) -> String {
        self.targets.iter().map(ToString::to_string).collect::<Vec<_>>().join("|")
    }
}

pub(super) fn should_skip_strategy_dns_target(target: &DomainTarget) -> bool {
    target.host.parse::<IpAddr>().is_ok() || target.host.eq_ignore_ascii_case("localhost")
}

pub(super) fn resolve_system_target(target: &DomainTarget) -> SystemDnsResolution {
    let system_started = std::time::Instant::now();
    let (targets, error_kind) = match domain_connect_target(target) {
        TargetAddress::Ip(ip) => (vec![SocketAddr::new(ip, target.https_port.unwrap_or(443))], None),
        TargetAddress::Host(host) => {
            match resolve_addresses(&TargetAddress::Host(host), target.https_port.unwrap_or(443)) {
                Ok(targets) if targets.is_empty() => (targets, Some(DnsResolveError::Failure)),
                Ok(targets) => (targets, None),
                Err(error) => (Vec::new(), Some(error)),
            }
        }
    };
    let latency_ms = system_started.elapsed().as_millis().to_string();
    SystemDnsResolution { targets, latency_ms, error_kind }
}

pub(super) fn fallback_encrypted_dns_assessment(
    target: &DomainTarget,
    resolver_endpoint: ripdpi_dns_resolver::EncryptedDnsEndpoint,
    primary_resolver_id: Option<&str>,
    probe_context: &ProbeExecutionContext,
    is_cancelled: &impl Fn() -> bool,
) -> DnsOracleAssessment<DnsOracleResponse> {
    let fallback_endpoints = probe_context.approved_fallback_resolvers(primary_resolver_id);
    evaluate_dns_oracles(
        resolver_endpoint,
        &fallback_endpoints,
        fallback_endpoints.len(),
        DnsOracleConfig::default(),
        is_cancelled,
        |endpoint, _| {
            resolve_via_encrypted_dns(&target.host, endpoint.clone(), probe_context.transport())
                .map(|addresses| DnsOracleResponse { addresses, raw_response: None })
        },
        |answer| answer.addresses.clone(),
    )
}
