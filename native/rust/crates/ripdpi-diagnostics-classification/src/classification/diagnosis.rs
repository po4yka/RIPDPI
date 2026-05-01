use std::collections::{BTreeMap, BTreeSet};

use crate::types::{Diagnosis, ProbeResult, ScanRequest};

mod circumvention;
mod common;
mod dns;
mod domain;
mod quic;
mod service;
mod strategy_probe;
mod tcp;
#[cfg(test)]
mod tests;
mod throughput;
mod transport;

use common::{normalize_host, DiagnosisSink};
pub use strategy_probe::{classify_strategy_probe_baseline_results, strategy_probe_failure_weight};
pub use transport::classify_transport_failure_text;

pub fn failure_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

pub fn classify_connectivity_diagnoses(request: &ScanRequest, results: &[ProbeResult]) -> Vec<Diagnosis> {
    let mut sink = DiagnosisSink::new();
    let mut hard_failure_codes = BTreeSet::<String>::new();

    let domain_outcomes = collect_domain_outcomes(results);
    let tcp_whitelist_bypass = has_tcp_whitelist_bypass(results);

    dns::classify_dns_diagnoses(results, &domain_outcomes, &mut hard_failure_codes, &mut sink);
    domain::classify_domain_diagnoses(results, &mut hard_failure_codes, &mut sink);
    tcp::classify_tcp_diagnoses(request, results, tcp_whitelist_bypass, &mut sink);
    quic::classify_quic_diagnoses(results, &mut sink);
    service::classify_service_diagnoses(results, &mut sink);
    circumvention::classify_circumvention_diagnoses(results, &mut sink);

    if should_run_throughput_diagnosis(&hard_failure_codes) {
        throughput::classify_throughput_diagnosis(results, &mut sink);
    }

    sink.into_vec()
}

fn collect_domain_outcomes(results: &[ProbeResult]) -> BTreeMap<String, Vec<&ProbeResult>> {
    let mut domain_outcomes = BTreeMap::<String, Vec<&ProbeResult>>::new();
    for result in results {
        if result.probe_type == "domain_reachability" {
            domain_outcomes.entry(normalize_host(&result.target)).or_default().push(result);
        }
    }
    domain_outcomes
}

fn has_tcp_whitelist_bypass(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| result.probe_type == "tcp_fat_header" && result.outcome == "whitelist_sni_ok")
}

fn should_run_throughput_diagnosis(hard_failure_codes: &BTreeSet<String>) -> bool {
    !hard_failure_codes.iter().any(|code| {
        matches!(
            code.as_str(),
            "dns_tampering"
                | "dns_blockpage_fingerprint"
                | "tls_clienthello_timeout"
                | "tls_clienthello_rst"
                | "tls_clienthello_close"
                | "tls_cert_mitm"
                | "http_blockpage"
        )
    })
}
