use std::collections::BTreeMap;

use crate::types::{Diagnosis, ProbeResult};

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

use common::{DiagnosisSink, normalize_host};
pub use strategy_probe::{classify_strategy_probe_baseline_results, strategy_probe_failure_weight};
pub use transport::classify_transport_failure_text;

pub fn failure_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

pub fn classify_connectivity_diagnoses(results: &[ProbeResult]) -> Vec<Diagnosis> {
    let mut sink = DiagnosisSink::new();

    let domain_outcomes = collect_domain_outcomes(results);

    dns::classify_dns_diagnoses(results, &domain_outcomes, &mut sink);
    domain::classify_domain_diagnoses(results, &mut sink);
    tcp::classify_tcp_diagnoses(results, &mut sink);
    quic::classify_quic_diagnoses(results, &mut sink);
    service::classify_service_diagnoses(results, &mut sink);
    circumvention::classify_circumvention_diagnoses(results, &mut sink);

    throughput::classify_throughput_diagnosis(results, &mut sink);

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
