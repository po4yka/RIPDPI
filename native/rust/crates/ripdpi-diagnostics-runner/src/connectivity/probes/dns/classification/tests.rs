use std::collections::{BTreeMap, BTreeSet};

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

use crate::connectivity::adapters::dns_oracle::{evaluate_dns_oracles, DnsOracleResponse};
use crate::types::ScanPathMode;

use super::answer_classification::{classify_dns_answer_class, DnsAnswerClass};
use super::classification_policy::resolve_dns_classification;
use super::https_ech_classification::DnsHttpsClass;
use super::{classify_dns_probe_outcome, oracle_result_for_probe};

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
