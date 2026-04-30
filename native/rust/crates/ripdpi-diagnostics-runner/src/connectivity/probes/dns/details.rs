use std::collections::BTreeSet;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::types::{ProbeDetail, ProbeResult};
use crate::util::{format_result_set, ip_set};

use super::classify_dns_latency_quality;

pub(super) struct DnsProbeDetailInputs<'a> {
    pub(super) udp_server: &'a str,
    pub(super) udp_result: &'a Result<Vec<String>, String>,
    pub(super) udp_latency_ms: &'a str,
    pub(super) encrypted_endpoint: &'a EncryptedDnsEndpoint,
    pub(super) encrypted_bootstrap_ips: &'a [String],
    pub(super) selected_bootstrap_ips: &'a [String],
    pub(super) encrypted_addresses: &'a str,
    pub(super) encrypted_latency_ms: &'a str,
    pub(super) injection_suspected: bool,
    pub(super) expected: &'a BTreeSet<String>,
    pub(super) oracle_assessment: &'a DnsOracleAssessment<DnsOracleResponse>,
}

pub(super) fn build_dns_probe_details(inputs: DnsProbeDetailInputs<'_>) -> Vec<ProbeDetail> {
    vec![
        ProbeDetail { key: "udpServer".to_string(), value: inputs.udp_server.to_string() },
        ProbeDetail { key: "udpAddresses".to_string(), value: format_result_set(inputs.udp_result) },
        ProbeDetail { key: "udpLatencyMs".to_string(), value: inputs.udp_latency_ms.to_string() },
        ProbeDetail {
            key: "encryptedResolverId".to_string(),
            value: inputs.encrypted_endpoint.resolver_id.clone().unwrap_or_default(),
        },
        ProbeDetail {
            key: "encryptedProtocol".to_string(),
            value: inputs.encrypted_endpoint.protocol.as_str().to_string(),
        },
        ProbeDetail {
            key: "encryptedEndpoint".to_string(),
            value: inputs
                .encrypted_endpoint
                .doh_url
                .clone()
                .unwrap_or_else(|| format!("{}:{}", inputs.encrypted_endpoint.host, inputs.encrypted_endpoint.port)),
        },
        ProbeDetail { key: "encryptedHost".to_string(), value: inputs.encrypted_endpoint.host.clone() },
        ProbeDetail { key: "encryptedPort".to_string(), value: inputs.encrypted_endpoint.port.to_string() },
        ProbeDetail {
            key: "encryptedTlsServerName".to_string(),
            value: inputs.encrypted_endpoint.tls_server_name.clone().unwrap_or_default(),
        },
        ProbeDetail { key: "encryptedBootstrapIps".to_string(), value: inputs.encrypted_bootstrap_ips.join("|") },
        ProbeDetail {
            key: "encryptedBootstrapValidated".to_string(),
            value: (inputs.oracle_assessment.selected.is_some() && !inputs.selected_bootstrap_ips.is_empty())
                .to_string(),
        },
        ProbeDetail {
            key: "encryptedDohUrl".to_string(),
            value: inputs.encrypted_endpoint.doh_url.clone().unwrap_or_default(),
        },
        ProbeDetail {
            key: "encryptedDnscryptProviderName".to_string(),
            value: inputs.encrypted_endpoint.dnscrypt_provider_name.clone().unwrap_or_default(),
        },
        ProbeDetail {
            key: "encryptedDnscryptPublicKey".to_string(),
            value: inputs.encrypted_endpoint.dnscrypt_public_key.clone().unwrap_or_default(),
        },
        ProbeDetail { key: "encryptedAddresses".to_string(), value: inputs.encrypted_addresses.to_string() },
        ProbeDetail { key: "encryptedLatencyMs".to_string(), value: inputs.encrypted_latency_ms.to_string() },
        ProbeDetail {
            key: "dnsLatencyQuality".to_string(),
            value: classify_dns_latency_quality(inputs.udp_latency_ms, inputs.encrypted_latency_ms),
        },
        ProbeDetail { key: "dnsInjectionSuspected".to_string(), value: inputs.injection_suspected.to_string() },
        ProbeDetail {
            key: "expected".to_string(),
            value: if inputs.expected.is_empty() {
                "[]".to_string()
            } else {
                inputs.expected.iter().cloned().collect::<Vec<_>>().join("|")
            },
        },
        ProbeDetail {
            key: "resolverFallbackUsed".to_string(),
            value: inputs.oracle_assessment.fallback_resolver_used().unwrap_or_default(),
        },
    ]
}

#[inline(never)]
pub(super) fn append_injection_profile_details(
    result: &mut ProbeResult,
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    udp_latency_ms: &str,
    encrypted_latency_ms: &str,
) {
    let udp_ms: u64 = udp_latency_ms.parse().unwrap_or(0);
    let enc_ms: u64 = encrypted_latency_ms.parse().unwrap_or(0);
    let ratio_x100: u64 = if udp_ms > 0 { (enc_ms * 100) / udp_ms } else { 0 };
    result.details.push(ProbeDetail { key: "injectionLatencyRatio".to_string(), value: ratio_x100.to_string() });

    let empty = vec![];
    let udp_set = ip_set(udp_result.as_ref().unwrap_or(&empty));
    let enc_set = ip_set(encrypted_result.as_ref().unwrap_or(&empty));
    let forged: Vec<String> = udp_set.difference(&enc_set).cloned().collect();
    if !forged.is_empty() {
        result.details.push(ProbeDetail { key: "forgedAddresses".to_string(), value: forged.join(",") });
    }
}
