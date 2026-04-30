use std::collections::BTreeSet;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::dns::resolve_https_service_bindings_via_encrypted_dns_with_endpoint;
use crate::dns_oracle::{DnsOracleAssessment, DnsOracleResponse, DnsOracleTrust};
use crate::transport::TransportConfig;
use crate::types::{ProbeResult, ScanPathMode};
use crate::util::{classify_dns_answer_overlap, ip_set, DnsAnswerOverlap};

use super::super::support::push_detail;

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

pub(super) fn oracle_result_for_probe(
    assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> Result<Vec<String>, String> {
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

pub(super) fn append_dns_classifier_details(
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

pub(super) fn classify_dns_probe_outcome(
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

    use crate::dns_oracle::{evaluate_dns_oracles, DnsOracleResponse};
    use crate::types::ScanPathMode;

    use super::{
        classify_dns_answer_class, classify_dns_probe_outcome, oracle_result_for_probe, resolve_dns_classification,
        DnsAnswerClass, DnsHttpsClass,
    };

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
