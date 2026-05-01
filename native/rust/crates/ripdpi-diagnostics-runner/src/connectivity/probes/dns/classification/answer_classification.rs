use crate::dns_oracle::{DnsOracleAssessment, DnsOracleResponse, DnsOracleTrust};
use crate::util::{classify_dns_answer_overlap, DnsAnswerOverlap};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum DnsAnswerClass {
    Clean,
    Poisoned,
    Divergent,
}

impl DnsAnswerClass {
    pub(super) fn as_str(self) -> &'static str {
        match self {
            Self::Clean => "CLEAN",
            Self::Poisoned => "POISONED",
            Self::Divergent => "DIVERGENT",
        }
    }
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

pub(super) fn classify_dns_answer_class(
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
