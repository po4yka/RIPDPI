use ripdpi_proxy_config::ProxyEncryptedDnsContext;

use crate::connectivity::{classify_dns_latency_quality, is_dns_injection_suspected};
use crate::strategy::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

use super::classification::StrategyDnsClassification;
use super::resolution::SystemDnsResolution;

pub(super) fn build_dns_integrity_result(
    target: &DomainTarget,
    resolver_context: &ProxyEncryptedDnsContext,
    resolver_label: &str,
    system_resolution: &SystemDnsResolution,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
    classification: &StrategyDnsClassification,
) -> ProbeResult {
    let encrypted_latency_ms = oracle_assessment.preferred_latency_ms().to_string();
    let encrypted_addresses_detail = if classification.encrypted_addresses.is_empty() {
        "dns_oracle_unavailable".to_string()
    } else {
        classification.encrypted_addresses.join("|")
    };
    let system_dns_error_kind = system_resolution
        .error_kind
        .map(|error_kind| ProbeDetail { key: "systemDnsErrorKind".to_string(), value: error_kind.code().to_string() });

    let mut result = ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.host.clone(),
        outcome: classification.outcome.to_string(),
        details: vec![
            ProbeDetail { key: "udpAddresses".to_string(), value: system_resolution.addresses_detail() },
            ProbeDetail { key: "udpLatencyMs".to_string(), value: system_resolution.latency_ms.clone() },
            ProbeDetail {
                key: "encryptedResolverId".to_string(),
                value: resolver_context.resolver_id.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedProtocol".to_string(), value: resolver_context.protocol.clone() },
            ProbeDetail { key: "encryptedEndpoint".to_string(), value: resolver_label.to_string() },
            ProbeDetail { key: "encryptedHost".to_string(), value: resolver_context.host.clone() },
            ProbeDetail { key: "encryptedPort".to_string(), value: resolver_context.port.to_string() },
            ProbeDetail {
                key: "encryptedTlsServerName".to_string(),
                value: resolver_context.tls_server_name.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedBootstrapIps".to_string(), value: resolver_context.bootstrap_ips.join("|") },
            ProbeDetail {
                key: "encryptedBootstrapValidated".to_string(),
                value: (oracle_assessment.selected.is_some() && !resolver_context.bootstrap_ips.is_empty()).to_string(),
            },
            ProbeDetail {
                key: "encryptedDohUrl".to_string(),
                value: resolver_context.doh_url.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedDnscryptProviderName".to_string(),
                value: resolver_context.dnscrypt_provider_name.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedDnscryptPublicKey".to_string(),
                value: resolver_context.dnscrypt_public_key.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedAddresses".to_string(), value: encrypted_addresses_detail },
            ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms.clone() },
            ProbeDetail {
                key: "dnsLatencyQuality".to_string(),
                value: classify_dns_latency_quality(&system_resolution.latency_ms, encrypted_latency_ms.as_str()),
            },
            ProbeDetail {
                key: "dnsInjectionSuspected".to_string(),
                value: is_dns_injection_suspected(&system_resolution.latency_ms, classification.outcome).to_string(),
            },
            ProbeDetail {
                key: "resolverFallbackUsed".to_string(),
                value: oracle_assessment.fallback_resolver_used().unwrap_or_default(),
            },
        ],
    };
    if let Some(detail) = system_dns_error_kind {
        result.details.push(detail);
    }
    result.details.extend(oracle_assessment.detail_entries());
    result
}
