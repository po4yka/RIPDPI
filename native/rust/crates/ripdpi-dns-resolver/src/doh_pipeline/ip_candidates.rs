use crate::transport::{IpAnswerFamily, extract_ip_answer_records};

use super::{DohBatchLookup, DohBatchRecordType, DohIpAnswerCandidate, DohIpFamily};

pub fn doh_ip_answer_candidates(lookup: &DohBatchLookup) -> Vec<DohIpAnswerCandidate> {
    lookup
        .records
        .iter()
        .filter(|record| matches!(record.record_type, DohBatchRecordType::A | DohBatchRecordType::Aaaa))
        .flat_map(|record| {
            extract_ip_answer_records(&record.response_bytes).unwrap_or_default().into_iter().map(move |answer| {
                DohIpAnswerCandidate {
                    ip: answer.ip.to_string(),
                    ip_family: answer.family.into(),
                    resolver_role: lookup.resolver_role,
                    ttl_secs: record.min_ttl_secs.or(lookup.cache_ttl_secs),
                }
            })
        })
        .collect()
}

impl From<IpAnswerFamily> for DohIpFamily {
    fn from(value: IpAnswerFamily) -> Self {
        match value {
            IpAnswerFamily::Ipv4 => Self::Ipv4,
            IpAnswerFamily::Ipv6 => Self::Ipv6,
        }
    }
}
