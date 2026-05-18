use std::collections::BTreeSet;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::connectivity::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::connectivity::adapters::util::{classify_dns_answer_overlap, format_result_set, ip_set, DnsAnswerOverlap};
use crate::types::{ProbeDetail, ProbeResult};

use super::classify_dns_latency_quality;

pub(super) struct DnsProbeDetailInputs<'a> {
    pub(super) udp_server: &'a str,
    pub(super) udp_result: &'a Result<Vec<String>, String>,
    pub(super) udp_latency_ms: &'a str,
    pub(super) udp_attempt_count: usize,
    pub(super) udp_success_count: usize,
    pub(super) udp_error_kind: Option<&'a str>,
    pub(super) udp_retry_recovered: bool,
    pub(super) udp_cache_hit: bool,
    pub(super) encrypted_endpoint: &'a EncryptedDnsEndpoint,
    pub(super) encrypted_bootstrap_ips: &'a [String],
    pub(super) selected_bootstrap_ips: &'a [String],
    pub(super) encrypted_result: &'a Result<Vec<String>, String>,
    pub(super) encrypted_addresses: &'a str,
    pub(super) encrypted_latency_ms: &'a str,
    pub(super) injection_suspected: bool,
    pub(super) expected: &'a BTreeSet<String>,
    pub(super) oracle_assessment: &'a DnsOracleAssessment<DnsOracleResponse>,
}

pub(super) fn build_dns_probe_details(inputs: DnsProbeDetailInputs<'_>) -> Vec<ProbeDetail> {
    let analytics = dns_answer_analytics(inputs.udp_result, inputs.encrypted_result);
    vec![
        ProbeDetail { key: "udpServer".to_string(), value: inputs.udp_server.to_string() },
        ProbeDetail { key: "udpAddresses".to_string(), value: format_result_set(inputs.udp_result) },
        ProbeDetail { key: "udpLatencyMs".to_string(), value: inputs.udp_latency_ms.to_string() },
        ProbeDetail { key: "udpAttemptCount".to_string(), value: inputs.udp_attempt_count.to_string() },
        ProbeDetail { key: "udpSuccessCount".to_string(), value: inputs.udp_success_count.to_string() },
        ProbeDetail { key: "udpErrorKind".to_string(), value: inputs.udp_error_kind.unwrap_or("none").to_string() },
        ProbeDetail { key: "udpRetryRecovered".to_string(), value: inputs.udp_retry_recovered.to_string() },
        ProbeDetail { key: "udpCacheHit".to_string(), value: inputs.udp_cache_hit.to_string() },
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
        ProbeDetail { key: "dnsAnswerOverlap".to_string(), value: analytics.overlap },
        ProbeDetail { key: "udpAnswerSetSize".to_string(), value: analytics.udp_count.to_string() },
        ProbeDetail { key: "encryptedAnswerSetSize".to_string(), value: analytics.encrypted_count.to_string() },
        ProbeDetail { key: "answerIntersectionCount".to_string(), value: analytics.intersection_count.to_string() },
        ProbeDetail { key: "answerOnlyUdpCount".to_string(), value: analytics.only_udp_count.to_string() },
        ProbeDetail { key: "answerOnlyEncryptedCount".to_string(), value: analytics.only_encrypted_count.to_string() },
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

struct DnsAnswerAnalytics {
    overlap: String,
    udp_count: usize,
    encrypted_count: usize,
    intersection_count: usize,
    only_udp_count: usize,
    only_encrypted_count: usize,
}

fn dns_answer_analytics(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
) -> DnsAnswerAnalytics {
    let empty = Vec::new();
    let udp_answers = udp_result.as_ref().unwrap_or(&empty);
    let encrypted_answers = encrypted_result.as_ref().unwrap_or(&empty);
    let udp_set = ip_set(udp_answers);
    let encrypted_set = ip_set(encrypted_answers);
    let overlap = match (udp_result, encrypted_result) {
        (Ok(udp), Ok(encrypted)) => match classify_dns_answer_overlap(udp, encrypted) {
            DnsAnswerOverlap::Match => "match",
            DnsAnswerOverlap::CompatibleDivergence => "compatible_divergence",
            DnsAnswerOverlap::SinkholeSubstitution => "sinkhole_substitution",
        },
        (Ok(_), Err(_)) => "oracle_unavailable",
        (Err(_), Ok(_)) => "udp_unavailable",
        (Err(_), Err(_)) => "unavailable",
    }
    .to_string();
    DnsAnswerAnalytics {
        overlap,
        udp_count: udp_set.len(),
        encrypted_count: encrypted_set.len(),
        intersection_count: udp_set.intersection(&encrypted_set).count(),
        only_udp_count: udp_set.difference(&encrypted_set).count(),
        only_encrypted_count: encrypted_set.difference(&udp_set).count(),
    }
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

#[cfg(test)]
mod tests {
    use super::dns_answer_analytics;

    #[test]
    fn dns_answer_analytics_counts_overlap_and_divergence() {
        let udp = Ok(vec!["172.217.20.78".to_string()]);
        let encrypted = Ok(vec!["172.217.20.78".to_string(), "142.250.75.78".to_string()]);

        let analytics = dns_answer_analytics(&udp, &encrypted);

        assert_eq!(analytics.overlap, "match");
        assert_eq!(analytics.udp_count, 1);
        assert_eq!(analytics.encrypted_count, 2);
        assert_eq!(analytics.intersection_count, 1);
        assert_eq!(analytics.only_udp_count, 0);
        assert_eq!(analytics.only_encrypted_count, 1);
    }

    #[test]
    fn dns_answer_analytics_labels_cdn_compatible_divergence() {
        let udp = Ok(vec!["172.217.20.78".to_string()]);
        let encrypted = Ok(vec!["142.250.21.100".to_string(), "142.250.21.101".to_string()]);

        let analytics = dns_answer_analytics(&udp, &encrypted);

        assert_eq!(analytics.overlap, "compatible_divergence");
        assert_eq!(analytics.intersection_count, 0);
        assert_eq!(analytics.only_udp_count, 1);
        assert_eq!(analytics.only_encrypted_count, 2);
    }

    #[test]
    fn dns_answer_analytics_marks_oracle_unavailable() {
        let udp = Ok(vec!["104.16.132.229".to_string()]);
        let encrypted = Err("timeout".to_string());

        let analytics = dns_answer_analytics(&udp, &encrypted);

        assert_eq!(analytics.overlap, "oracle_unavailable");
        assert_eq!(analytics.udp_count, 1);
        assert_eq!(analytics.encrypted_count, 0);
    }
}
