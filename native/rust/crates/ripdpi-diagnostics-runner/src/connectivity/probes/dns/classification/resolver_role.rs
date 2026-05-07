use crate::connectivity::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};

pub(super) fn selected_resolver_role(oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>) -> &'static str {
    match oracle_assessment.selected.as_ref().map(|candidate| candidate.is_primary) {
        Some(true) => "primary",
        Some(false) => "secondary",
        None => "",
    }
}
