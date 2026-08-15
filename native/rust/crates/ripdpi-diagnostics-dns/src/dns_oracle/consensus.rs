use std::collections::BTreeMap;

use super::contracts::{
    DnsOracleAssessment, DnsOracleAttempt, DnsOracleCandidate, DnsOracleTermination, DnsOracleTrust,
};
use super::resolver_label::resolver_label;

pub(super) fn build_assessment<T>(
    attempts: Vec<DnsOracleAttempt>,
    successes: Vec<DnsOracleCandidate<T>>,
    termination: DnsOracleTermination,
) -> DnsOracleAssessment<T>
where
    T: Clone,
{
    let mut groups = BTreeMap::<Vec<String>, Vec<usize>>::new();
    for (index, candidate) in successes.iter().enumerate() {
        groups.entry(candidate.answers.clone()).or_default().push(index);
    }

    if let Some(indices) = groups.values().find(|indices| indices.len() >= 2) {
        let agreement_resolver_ids =
            indices.iter().map(|index| resolver_label(&successes[*index].endpoint)).collect::<Vec<_>>();
        let selected_index = indices.iter().find(|index| successes[**index].is_primary).copied().unwrap_or(indices[0]);
        let disagreement_resolver_ids = successes
            .iter()
            .enumerate()
            .filter(|(index, _)| !indices.contains(index))
            .map(|(_, candidate)| resolver_label(&candidate.endpoint))
            .collect::<Vec<_>>();

        return DnsOracleAssessment {
            trust: DnsOracleTrust::TrustedAgreement,
            confidence_score: 100,
            selected: Some(successes[selected_index].clone()),
            agreement_resolver_ids,
            disagreement_resolver_ids,
            attempts,
            termination,
        };
    }

    if let Some(primary) = successes.iter().find(|candidate| candidate.is_primary).cloned() {
        if successes.len() == 1 {
            let primary_id = resolver_label(&primary.endpoint);
            return DnsOracleAssessment {
                trust: DnsOracleTrust::PrimaryOnly,
                confidence_score: 70,
                selected: Some(primary),
                agreement_resolver_ids: vec![primary_id],
                disagreement_resolver_ids: Vec::new(),
                attempts,
                termination,
            };
        }

        return DnsOracleAssessment {
            trust: DnsOracleTrust::Disagreement,
            confidence_score: 25,
            selected: None,
            agreement_resolver_ids: Vec::new(),
            disagreement_resolver_ids: successes
                .iter()
                .map(|candidate| resolver_label(&candidate.endpoint))
                .collect::<Vec<_>>(),
            attempts,
            termination,
        };
    }

    if successes.len() == 1 {
        let fallback = successes[0].clone();
        let fallback_id = resolver_label(&fallback.endpoint);
        return DnsOracleAssessment {
            trust: DnsOracleTrust::SingleFallback,
            confidence_score: 40,
            selected: Some(fallback),
            agreement_resolver_ids: vec![fallback_id],
            disagreement_resolver_ids: Vec::new(),
            attempts,
            termination,
        };
    }

    let disagreement_resolver_ids = if successes.len() > 1 {
        successes.iter().map(|candidate| resolver_label(&candidate.endpoint)).collect::<Vec<_>>()
    } else {
        Vec::new()
    };
    let trust =
        if disagreement_resolver_ids.is_empty() { DnsOracleTrust::Unavailable } else { DnsOracleTrust::Disagreement };
    let confidence_score = if trust == DnsOracleTrust::Unavailable { 0 } else { 25 };

    DnsOracleAssessment {
        trust,
        confidence_score,
        selected: None,
        agreement_resolver_ids: Vec::new(),
        disagreement_resolver_ids,
        attempts,
        termination,
    }
}
