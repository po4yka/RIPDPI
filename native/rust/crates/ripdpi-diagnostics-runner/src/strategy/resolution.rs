use std::net::{IpAddr, SocketAddr};

use crate::strategy::adapters::dns::{build_fallback_encrypted_dns_endpoints, resolve_via_encrypted_dns};
use crate::strategy::adapters::dns_oracle::{evaluate_dns_oracles, DnsOracleAssessment, DnsOracleResponse};
use crate::strategy::adapters::transport::{direct_transport, domain_connect_target, resolve_addresses, TargetAddress};
use crate::types::DomainTarget;

pub(super) struct SystemDnsResolution {
    pub(super) targets: Vec<SocketAddr>,
    pub(super) latency_ms: String,
}

impl SystemDnsResolution {
    pub(super) fn failed(&self) -> bool {
        self.targets.is_empty()
    }

    pub(super) fn addresses_detail(&self) -> String {
        if self.failed() {
            "nxdomain".to_string()
        } else {
            self.targets.iter().map(ToString::to_string).collect::<Vec<_>>().join("|")
        }
    }
}

pub(super) fn should_skip_strategy_dns_target(target: &DomainTarget) -> bool {
    target.host.parse::<IpAddr>().is_ok() || target.host.eq_ignore_ascii_case("localhost")
}

pub(super) fn resolve_system_target(target: &DomainTarget) -> SystemDnsResolution {
    let system_started = std::time::Instant::now();
    let targets = match domain_connect_target(target) {
        TargetAddress::Ip(ip) => vec![SocketAddr::new(ip, target.https_port.unwrap_or(443))],
        TargetAddress::Host(host) => {
            resolve_addresses(&TargetAddress::Host(host), target.https_port.unwrap_or(443)).unwrap_or_default()
        }
    };
    let latency_ms = system_started.elapsed().as_millis().to_string();
    SystemDnsResolution { targets, latency_ms }
}

pub(super) fn fallback_encrypted_dns_assessment(
    target: &DomainTarget,
    resolver_endpoint: ripdpi_dns_resolver::EncryptedDnsEndpoint,
    primary_resolver_id: Option<&str>,
) -> DnsOracleAssessment<DnsOracleResponse> {
    let fallback_endpoints = build_fallback_encrypted_dns_endpoints(primary_resolver_id);
    evaluate_dns_oracles(
        resolver_endpoint,
        &fallback_endpoints,
        fallback_endpoints.len(),
        |endpoint| {
            resolve_via_encrypted_dns(&target.host, endpoint.clone(), &direct_transport())
                .map(|addresses| DnsOracleResponse { addresses, raw_response: None })
        },
        |answer| answer.addresses.clone(),
    )
}
