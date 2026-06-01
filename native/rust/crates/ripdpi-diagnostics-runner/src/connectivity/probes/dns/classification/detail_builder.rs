use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::connectivity::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::ProbeResult;

use super::super::super::support::push_detail;
use super::answer_classification::{DnsAnswerClass, classify_dns_answer_class};
use super::classification_policy::resolve_dns_classification;
use super::https_ech_classification::classify_dns_https_support;
use super::resolver_role::selected_resolver_role;

#[derive(Debug, Clone, PartialEq, Eq)]
struct DnsClassifierDetails {
    classification: Option<&'static str>,
    answer_class: Option<&'static str>,
    https_class: &'static str,
    selected_resolver_role: &'static str,
    https_record_count: usize,
    ech_record_count: usize,
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
