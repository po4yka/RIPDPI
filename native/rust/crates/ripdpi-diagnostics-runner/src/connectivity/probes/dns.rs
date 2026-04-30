use std::collections::BTreeSet;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::dns::*;
use crate::dns_analysis::{analyze_dns_response, compare_dns_responses, parse_record_set};
use crate::dns_oracle::{evaluate_dns_oracles, DnsOracleAssessment, DnsOracleResponse, DnsOracleTrust};
use crate::transport::TransportConfig;
use crate::types::{DnsTarget, ProbeDetail, ProbeResult, ScanPathMode};
use crate::util::*;

use super::super::trigger_fuzzing::append_dns_trigger_fuzzing_details;
use super::support::{push_detail, push_joined_str_detail, push_joined_string_detail};

/// Classify DNS latency into a human-readable quality tier.
///
/// Thresholds:
/// - UDP > 3000ms => "throttled"
/// - encrypted < 100ms => "fast"
/// - encrypted 100..=500ms => "normal"
/// - encrypted > 500ms => "slow"
/// - parse failure => "unknown"
pub fn classify_dns_latency_quality(udp_latency_ms: &str, encrypted_latency_ms: &str) -> String {
    let udp: u64 = udp_latency_ms.parse().unwrap_or(0);
    let encrypted: u64 = encrypted_latency_ms.parse().unwrap_or(0);
    if udp > 3000 {
        return "throttled".to_string();
    }
    if encrypted == 0 && udp == 0 {
        return "unknown".to_string();
    }
    // When UDP is suspiciously fast relative to encrypted, flag as injected.
    if udp > 0 {
        let ratio = (encrypted as f64) / (udp as f64);
        if ratio >= 20.0 {
            return "injected".to_string();
        }
    }
    match encrypted {
        0..=99 => "fast".to_string(),
        100..=500 => "normal".to_string(),
        _ => "slow".to_string(),
    }
}

/// Returns `true` when the UDP DNS response arrived suspiciously fast (<=5ms)
/// while the encrypted resolver returned different answers -- a strong signal
/// of in-path DNS injection (e.g., middlebox DPI equipment racing forged responses).
pub fn is_dns_injection_suspected(udp_latency_ms: &str, outcome: &str) -> bool {
    let udp: u64 = udp_latency_ms.parse().unwrap_or(u64::MAX);
    udp <= 5 && is_suspected_dns_tampering_outcome(outcome)
}

pub fn run_dns_probe(target: &DnsTarget, transport: &TransportConfig, path_mode: &ScanPathMode) -> ProbeResult {
    let udp_server = target.udp_server.clone().unwrap_or_else(|| DEFAULT_DNS_SERVER.to_string());
    let (encrypted_endpoint, encrypted_bootstrap_ips) = match encrypted_dns_endpoint_for_target(target) {
        Ok(value) => value,
        Err(err) => return dns_probe_unavailable_result(target, err),
    };
    let udp_started = std::time::Instant::now();
    let (udp_result, raw_udp_response) = resolve_via_udp_with_raw(&target.domain, &udp_server, transport);
    let udp_latency_ms = udp_started.elapsed().as_millis().to_string();
    let target_uses_default_resolver =
        target.encrypted_host.is_none() && target.encrypted_doh_url.is_none() && target.encrypted_protocol.is_none();
    let fallback_endpoints = if target_uses_default_resolver {
        build_fallback_encrypted_dns_endpoints(encrypted_endpoint.resolver_id.as_deref())
    } else {
        Vec::new()
    };
    let oracle_assessment = evaluate_dns_oracles(
        encrypted_endpoint.clone(),
        &fallback_endpoints,
        2,
        |endpoint| {
            let (result, raw_response) =
                resolve_via_encrypted_dns_with_raw(&target.domain, endpoint.clone(), transport);
            result.map(|addresses| DnsOracleResponse { addresses, raw_response })
        },
        |answer| answer.addresses.clone(),
    );
    let encrypted_result = oracle_result_for_probe(&oracle_assessment);
    let raw_encrypted_response =
        oracle_assessment.selected.as_ref().and_then(|selected| selected.value.raw_response.clone());
    let encrypted_latency_ms = oracle_assessment.preferred_latency_ms().to_string();

    let expected: BTreeSet<String> = target.expected_ips.iter().cloned().collect();
    let outcome = classify_dns_probe_outcome(&udp_result, &encrypted_result, path_mode, &udp_latency_ms, &expected);
    let injection_suspected = is_dns_injection_suspected(&udp_latency_ms, &outcome);
    let selected_endpoint =
        oracle_assessment.selected.as_ref().map_or(&encrypted_endpoint, |selected| &selected.endpoint);
    let selected_bootstrap_ips = selected_endpoint.bootstrap_ips.iter().map(ToString::to_string).collect::<Vec<_>>();
    let encrypted_addresses = match &encrypted_result {
        Ok(addresses) if !addresses.is_empty() => addresses.join("|"),
        Ok(_) => "[]".to_string(),
        Err(err) => err.clone(),
    };

    let mut result = ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome,
        details: build_dns_probe_details(DnsProbeDetailInputs {
            udp_server: &udp_server,
            udp_result: &udp_result,
            udp_latency_ms: &udp_latency_ms,
            encrypted_endpoint: &encrypted_endpoint,
            encrypted_bootstrap_ips: &encrypted_bootstrap_ips,
            selected_bootstrap_ips: &selected_bootstrap_ips,
            encrypted_addresses: &encrypted_addresses,
            encrypted_latency_ms: &encrypted_latency_ms,
            injection_suspected,
            expected: &expected,
            oracle_assessment: &oracle_assessment,
        }),
    };
    append_dns_classifier_details(
        &mut result,
        &target.domain,
        &udp_result,
        &encrypted_result,
        selected_endpoint,
        transport,
        &oracle_assessment,
    );
    result.details.extend(oracle_assessment.detail_entries());

    if is_suspected_dns_tampering_outcome(result.outcome.as_str()) {
        append_injection_profile_details(
            &mut result,
            &udp_result,
            &encrypted_result,
            &udp_latency_ms,
            &encrypted_latency_ms,
        );
    }

    if let Some(raw) = raw_udp_response.as_deref() {
        append_udp_response_analysis(&mut result, raw);
    }

    if let (Some(udp_raw), Some(enc_raw)) = (raw_udp_response.as_deref(), raw_encrypted_response.as_deref()) {
        append_record_comparison_details(&mut result, udp_raw, enc_raw);
    }

    if result.outcome != "dns_match" {
        append_dns_trigger_fuzzing_details(
            &mut result.details,
            target,
            transport,
            result.outcome.as_str(),
            &encrypted_result,
        );
    }

    result
}

fn oracle_result_for_probe(assessment: &DnsOracleAssessment<DnsOracleResponse>) -> Result<Vec<String>, String> {
    match assessment.trust {
        DnsOracleTrust::TrustedAgreement | DnsOracleTrust::PrimaryOnly => assessment
            .selected
            .as_ref()
            .map(|selected| selected.value.addresses.clone())
            .ok_or_else(|| "dns_oracle_unavailable".to_string()),
        DnsOracleTrust::SingleFallback => Err("dns_oracle_unavailable".to_string()),
        DnsOracleTrust::Disagreement => Err("dns_oracle_disagreement".to_string()),
        DnsOracleTrust::Unavailable => Err("dns_oracle_unavailable".to_string()),
    }
}

fn dns_probe_unavailable_result(target: &DnsTarget, err: String) -> ProbeResult {
    ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome: "dns_unavailable".to_string(),
        details: vec![ProbeDetail { key: "encryptedDnsError".to_string(), value: err }],
    }
}

struct DnsProbeDetailInputs<'a> {
    udp_server: &'a str,
    udp_result: &'a Result<Vec<String>, String>,
    udp_latency_ms: &'a str,
    encrypted_endpoint: &'a EncryptedDnsEndpoint,
    encrypted_bootstrap_ips: &'a [String],
    selected_bootstrap_ips: &'a [String],
    encrypted_addresses: &'a str,
    encrypted_latency_ms: &'a str,
    injection_suspected: bool,
    expected: &'a BTreeSet<String>,
    oracle_assessment: &'a DnsOracleAssessment<DnsOracleResponse>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DnsAnswerClass {
    Clean,
    Poisoned,
    Divergent,
}

impl DnsAnswerClass {
    fn as_str(self) -> &'static str {
        match self {
            Self::Clean => "CLEAN",
            Self::Poisoned => "POISONED",
            Self::Divergent => "DIVERGENT",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DnsHttpsClass {
    EchCapable,
    NoHttpsRr,
    HttpsRrPresent,
    ResolutionFailed,
}

impl DnsHttpsClass {
    fn as_str(self) -> &'static str {
        match self {
            Self::EchCapable => "ECH_CAPABLE",
            Self::NoHttpsRr => "NO_HTTPS_RR",
            Self::HttpsRrPresent => "HTTPS_RR_PRESENT",
            Self::ResolutionFailed => "RESOLUTION_FAILED",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DnsClassifierDetails {
    classification: Option<&'static str>,
    answer_class: Option<&'static str>,
    https_class: &'static str,
    selected_resolver_role: &'static str,
    https_record_count: usize,
    ech_record_count: usize,
}

fn build_dns_probe_details(inputs: DnsProbeDetailInputs<'_>) -> Vec<ProbeDetail> {
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

fn append_dns_classifier_details(
    result: &mut ProbeResult,
    domain: &str,
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    selected_endpoint: &EncryptedDnsEndpoint,
    transport: &TransportConfig,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) {
    let details = classify_dns_probe_details(
        domain,
        udp_result,
        encrypted_result,
        selected_endpoint,
        transport,
        oracle_assessment,
    );
    push_detail(&mut result.details, "dnsClassifierVersion", "1".to_string());
    push_detail(&mut result.details, "dnsClassification", details.classification.unwrap_or_default().to_string());
    push_detail(&mut result.details, "dnsAnswerClass", details.answer_class.unwrap_or_default().to_string());
    push_detail(&mut result.details, "dnsHttpsClass", details.https_class.to_string());
    push_detail(&mut result.details, "dnsSelectedResolverRole", details.selected_resolver_role.to_string());
    push_detail(&mut result.details, "dnsHttpsRecordCount", details.https_record_count.to_string());
    push_detail(&mut result.details, "dnsEchRecordCount", details.ech_record_count.to_string());
}

fn classify_dns_probe_details(
    domain: &str,
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    selected_endpoint: &EncryptedDnsEndpoint,
    transport: &TransportConfig,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> DnsClassifierDetails {
    let answer_class = classify_dns_answer_class(udp_result, encrypted_result, oracle_assessment);
    let (https_class, https_record_count, ech_record_count) =
        classify_dns_https_support(domain, selected_endpoint, transport);
    let classification = resolve_dns_classification(answer_class, https_class);
    DnsClassifierDetails {
        classification,
        answer_class: answer_class.map(DnsAnswerClass::as_str),
        https_class: https_class.as_str(),
        selected_resolver_role: selected_resolver_role(oracle_assessment),
        https_record_count,
        ech_record_count,
    }
}

fn resolve_dns_classification(
    answer_class: Option<DnsAnswerClass>,
    https_class: DnsHttpsClass,
) -> Option<&'static str> {
    match (https_class, answer_class) {
        (DnsHttpsClass::EchCapable, _) => Some("ECH_CAPABLE"),
        (DnsHttpsClass::NoHttpsRr, Some(DnsAnswerClass::Poisoned)) => Some("POISONED"),
        (DnsHttpsClass::NoHttpsRr, Some(DnsAnswerClass::Divergent)) => Some("DIVERGENT"),
        (DnsHttpsClass::NoHttpsRr, Some(DnsAnswerClass::Clean)) => Some("NO_HTTPS_RR"),
        (_, Some(answer_class)) => Some(answer_class.as_str()),
        (DnsHttpsClass::NoHttpsRr, None) => Some("NO_HTTPS_RR"),
        _ => None,
    }
}

fn classify_dns_answer_class(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> Option<DnsAnswerClass> {
    if !oracle_assessment.trust.allows_tampering_classification() {
        return None;
    }
    match (udp_result, encrypted_result) {
        (Ok(udp_ips), Ok(encrypted_ips)) => match classify_dns_answer_overlap(udp_ips, encrypted_ips) {
            DnsAnswerOverlap::Match => Some(DnsAnswerClass::Clean),
            DnsAnswerOverlap::CompatibleDivergence => Some(DnsAnswerClass::Divergent),
            DnsAnswerOverlap::SinkholeSubstitution => Some(DnsAnswerClass::Poisoned),
        },
        (Err(error), Ok(encrypted_ips))
            if !encrypted_ips.is_empty() && matches!(error.as_str(), "dns_nxdomain" | "dns_no_answer") =>
        {
            Some(DnsAnswerClass::Poisoned)
        }
        _ => None,
    }
}

fn classify_dns_https_support(
    domain: &str,
    selected_endpoint: &EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> (DnsHttpsClass, usize, usize) {
    match resolve_https_service_bindings_via_encrypted_dns_with_endpoint(domain, selected_endpoint.clone(), transport) {
        Ok(bindings) => {
            let ech_record_count = bindings.iter().filter(|record| record.ech_capable).count();
            if ech_record_count > 0 {
                (DnsHttpsClass::EchCapable, bindings.len(), ech_record_count)
            } else if bindings.is_empty() {
                (DnsHttpsClass::NoHttpsRr, 0, 0)
            } else {
                (DnsHttpsClass::HttpsRrPresent, bindings.len(), 0)
            }
        }
        Err(_) => (DnsHttpsClass::ResolutionFailed, 0, 0),
    }
}

fn selected_resolver_role(oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>) -> &'static str {
    match oracle_assessment.selected.as_ref().map(|candidate| candidate.is_primary) {
        Some(true) => "primary",
        Some(false) => "secondary",
        None => "",
    }
}

#[inline(never)]
fn append_injection_profile_details(
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

#[inline(never)]
fn append_udp_response_analysis(result: &mut ProbeResult, raw: &[u8]) {
    let analysis = analyze_dns_response(raw);
    push_detail(&mut result.details, "udpResponseSize", analysis.response_size.to_string());
    push_detail(&mut result.details, "udpAaFlag", analysis.aa_flag.to_string());
    push_detail(&mut result.details, "udpRcode", analysis.rcode.to_string());
    push_detail(&mut result.details, "udpAnswerCount", analysis.answer_count.to_string());
    push_detail(&mut result.details, "udpAuthorityCount", analysis.authority_count.to_string());
    push_detail(&mut result.details, "udpAdditionalCount", analysis.additional_count.to_string());
    push_detail(&mut result.details, "udpMinTtl", analysis.min_ttl.map_or_else(String::new, |value| value.to_string()));
    push_detail(&mut result.details, "udpMaxTtl", analysis.max_ttl.map_or_else(String::new, |value| value.to_string()));
    push_detail(&mut result.details, "udpHasEdns0", analysis.has_edns0.to_string());
    push_joined_string_detail(&mut result.details, "udpCnameTargets", &analysis.cname_targets);
    push_detail(&mut result.details, "udpTamperingScore", analysis.tampering_score.to_string());
    push_joined_str_detail(&mut result.details, "udpAnomalySignals", &analysis.signals);
    push_detail(&mut result.details, "malformedPointers", analysis.malformed_pointers.to_string());
}

#[inline(never)]
fn append_record_comparison_details(result: &mut ProbeResult, udp_raw: &[u8], enc_raw: &[u8]) {
    let udp_records = parse_record_set(udp_raw);
    let enc_records = parse_record_set(enc_raw);
    let comparison = compare_dns_responses(&udp_records, &enc_records);

    let udp_types: Vec<&str> = udp_records.answers.iter().map(|r| r.rtype_name).collect();
    let enc_types: Vec<&str> = enc_records.answers.iter().map(|r| r.rtype_name).collect();

    push_detail(&mut result.details, "udpRecordTypes", udp_types.join("|"));
    push_detail(&mut result.details, "encryptedRecordTypes", enc_types.join("|"));
    push_detail(&mut result.details, "recordTypeMismatch", comparison.record_type_mismatch.to_string());
    push_detail(&mut result.details, "answerCountDivergence", comparison.answer_count_divergence.to_string());
    push_detail(
        &mut result.details,
        "ttlDivergence",
        comparison.ttl_divergence.map_or_else(String::new, |value| value.to_string()),
    );
    push_detail(&mut result.details, "authorityMismatch", comparison.authority_mismatch.to_string());
    push_joined_string_detail(&mut result.details, "extraCnames", &comparison.extra_cnames);
    push_detail(&mut result.details, "comparisonScore", comparison.comparison_score.to_string());
    push_joined_str_detail(&mut result.details, "comparisonSignals", &comparison.comparison_signals);
}

fn classify_dns_probe_outcome(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    path_mode: &ScanPathMode,
    udp_latency_ms: &str,
    expected: &BTreeSet<String>,
) -> String {
    match (udp_result, encrypted_result) {
        (Ok(udp_ips), Ok(encrypted_ips)) => match classify_dns_answer_overlap(udp_ips, encrypted_ips) {
            DnsAnswerOverlap::Match => {
                if !expected.is_empty() && ip_set(udp_ips) != expected.clone() {
                    "dns_expected_mismatch".to_string()
                } else {
                    "dns_match".to_string()
                }
            }
            DnsAnswerOverlap::CompatibleDivergence => {
                if udp_latency_ms.parse::<u64>().unwrap_or(u64::MAX) <= 5 {
                    "dns_suspicious_divergence".to_string()
                } else {
                    "dns_compatible_divergence".to_string()
                }
            }
            DnsAnswerOverlap::SinkholeSubstitution => "dns_sinkhole_substitution".to_string(),
        },
        (Ok(_), Err(_)) => "dns_oracle_unavailable".to_string(),
        (Err(err), Ok(_)) if err == "dns_nxdomain" => "dns_nxdomain_mismatch".to_string(),
        (Err(_), Ok(_)) => {
            if matches!(path_mode, ScanPathMode::InPath) {
                "udp_skipped_or_blocked".to_string()
            } else {
                "udp_blocked".to_string()
            }
        }
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
    }
}
#[cfg(test)]
mod tests {
    use std::collections::{BTreeMap, BTreeSet};

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    use crate::dns_oracle::evaluate_dns_oracles;
    use crate::types::ScanPathMode;

    use super::{
        classify_dns_answer_class, classify_dns_latency_quality, classify_dns_probe_outcome, oracle_result_for_probe,
        resolve_dns_classification, DnsAnswerClass, DnsHttpsClass,
    };
    use crate::dns_oracle::DnsOracleResponse;

    fn endpoint(id: &str) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some(id.to_string()),
            host: format!("{id}.example"),
            port: 443,
            tls_server_name: None,
            bootstrap_ips: Vec::new(),
            doh_url: Some(format!("https://{id}.example/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    #[test]
    fn dns_latency_quality_throttled_for_slow_udp() {
        assert_eq!(classify_dns_latency_quality("6000", "100"), "throttled");
        assert_eq!(classify_dns_latency_quality("3001", "50"), "throttled");
    }

    #[test]
    fn dns_latency_quality_fast_for_quick_encrypted() {
        assert_eq!(classify_dns_latency_quality("20", "50"), "fast");
        assert_eq!(classify_dns_latency_quality("20", "99"), "fast");
    }

    #[test]
    fn dns_latency_quality_normal_for_moderate() {
        assert_eq!(classify_dns_latency_quality("20", "250"), "normal");
    }

    #[test]
    fn dns_latency_quality_slow_for_high_encrypted() {
        // UDP 50ms, encrypted 600ms => ratio 12 (below injected threshold)
        assert_eq!(classify_dns_latency_quality("50", "600"), "slow");
    }

    #[test]
    fn dns_latency_quality_unknown_for_zero() {
        assert_eq!(classify_dns_latency_quality("0", "0"), "unknown");
    }

    #[test]
    fn dns_latency_quality_injected_for_high_ratio() {
        // UDP 3ms, encrypted 200ms => ratio ~66.7 => "injected"
        assert_eq!(classify_dns_latency_quality("3", "200"), "injected");
        // UDP 5ms, encrypted 100ms => ratio 20.0 => "injected"
        assert_eq!(classify_dns_latency_quality("5", "100"), "injected");
    }

    #[test]
    fn dns_latency_quality_not_injected_below_threshold() {
        // UDP 10ms, encrypted 99ms => ratio 9.9 => "fast" (below 20x)
        assert_eq!(classify_dns_latency_quality("10", "99"), "fast");
    }

    #[test]
    fn dns_probe_gates_single_fallback_success_as_oracle_unavailable() {
        let answers = BTreeMap::from([
            ("primary".to_string(), Err("connection reset".to_string())),
            (
                "fallback".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
        ]);
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback")],
            1,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );
        let encrypted_result = oracle_result_for_probe(&assessment);
        let outcome = classify_dns_probe_outcome(
            &Ok(vec!["203.0.113.10".to_string()]),
            &encrypted_result,
            &ScanPathMode::RawPath,
            "25",
            &BTreeSet::new(),
        );

        assert_eq!(outcome, "dns_oracle_unavailable");
    }

    #[test]
    fn dns_answer_class_marks_nxdomain_plus_encrypted_success_as_poisoned() {
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[],
            0,
            |_| Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            |answer| answer.addresses.clone(),
        );

        let answer_class = classify_dns_answer_class(
            &Err("dns_nxdomain".to_string()),
            &Ok(vec!["198.51.100.77".to_string()]),
            &assessment,
        );

        assert_eq!(answer_class, Some(DnsAnswerClass::Poisoned));
    }

    #[test]
    fn dns_answer_class_skips_poisoning_when_oracle_trust_is_single_fallback() {
        let answers = BTreeMap::from([
            ("primary".to_string(), Err("connection reset".to_string())),
            (
                "fallback".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
        ]);
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback")],
            1,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );

        let answer_class = classify_dns_answer_class(
            &Err("dns_nxdomain".to_string()),
            &Ok(vec!["198.51.100.77".to_string()]),
            &assessment,
        );

        assert_eq!(answer_class, None);
    }

    #[test]
    fn dns_classifier_prefers_ech_capable_over_clean_answer_overlap() {
        let classification = resolve_dns_classification(Some(DnsAnswerClass::Clean), DnsHttpsClass::EchCapable);

        assert_eq!(classification, Some("ECH_CAPABLE"));
    }

    #[test]
    fn dns_classifier_keeps_poisoned_when_https_records_are_missing() {
        let classification = resolve_dns_classification(Some(DnsAnswerClass::Poisoned), DnsHttpsClass::NoHttpsRr);

        assert_eq!(classification, Some("POISONED"));
    }
}
