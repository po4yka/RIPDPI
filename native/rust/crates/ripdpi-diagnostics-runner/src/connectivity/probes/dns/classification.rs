mod answer_classification;
mod classification_policy;
mod detail_builder;
mod https_ech_classification;
mod outcome;
mod resolver_role;

#[cfg(test)]
mod tests;

use std::collections::BTreeSet;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::transport::TransportConfig;
use crate::types::{ProbeResult, ScanPathMode};

pub(super) fn oracle_result_for_probe(
    assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> Result<Vec<String>, String> {
    answer_classification::oracle_result_for_probe(assessment)
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
    detail_builder::append_dns_classifier_details(
        result,
        domain,
        udp_result,
        encrypted_result,
        selected_endpoint,
        transport,
        oracle_assessment,
    );
}

pub(super) fn classify_dns_probe_outcome(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    path_mode: &ScanPathMode,
    udp_latency_ms: &str,
    expected: &BTreeSet<String>,
) -> String {
    outcome::classify_dns_probe_outcome(udp_result, encrypted_result, path_mode, udp_latency_ms, expected)
}
