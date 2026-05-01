mod dns_fuzz;
mod http_fuzz;
mod summary;
mod tls_fuzz;

use crate::transport::TransportConfig;
use crate::types::{DnsTarget, DomainTarget, ProbeDetail};

pub(crate) fn append_http_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DomainTarget,
    transport: &TransportConfig,
    baseline_status: &str,
) {
    http_fuzz::append_trigger_fuzzing_details(details, target, transport, baseline_status);
}

pub(crate) fn append_tls_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DomainTarget,
    transport: &TransportConfig,
    baseline_status: &str,
) {
    tls_fuzz::append_trigger_fuzzing_details(details, target, transport, baseline_status);
}

pub(crate) fn append_dns_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DnsTarget,
    transport: &TransportConfig,
    baseline_outcome: &str,
    encrypted_result: &Result<Vec<String>, String>,
) {
    dns_fuzz::append_trigger_fuzzing_details(details, target, transport, baseline_outcome, encrypted_result);
}

#[cfg(test)]
mod tests;
