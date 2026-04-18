use std::collections::{BTreeMap, BTreeSet};
use std::time::Instant;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::types::ProbeDetail;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DnsOracleTrust {
    TrustedAgreement,
    PrimaryOnly,
    SingleFallback,
    Disagreement,
    Unavailable,
}

#[derive(Debug, Clone)]
pub(crate) struct DnsOracleResponse {
    pub(crate) addresses: Vec<String>,
    pub(crate) raw_response: Option<Vec<u8>>,
}

impl DnsOracleTrust {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::TrustedAgreement => "trusted_agreement",
            Self::PrimaryOnly => "primary_only",
            Self::SingleFallback => "single_fallback",
            Self::Disagreement => "disagreement",
            Self::Unavailable => "unavailable",
        }
    }

    pub(crate) fn allows_tampering_classification(self) -> bool {
        matches!(self, Self::TrustedAgreement | Self::PrimaryOnly)
    }
}

#[derive(Debug, Clone)]
pub(crate) struct DnsOracleCandidate<T> {
    pub(crate) endpoint: EncryptedDnsEndpoint,
    pub(crate) value: T,
    pub(crate) answers: Vec<String>,
    pub(crate) is_primary: bool,
    pub(crate) latency_ms: u128,
}

#[derive(Debug, Clone)]
pub(crate) struct DnsOracleAttempt {
    pub(crate) resolver_id: String,
    pub(crate) is_primary: bool,
    pub(crate) latency_ms: u128,
    pub(crate) answers: Vec<String>,
    pub(crate) error: Option<String>,
}

#[derive(Debug, Clone)]
pub(crate) struct DnsOracleAssessment<T> {
    pub(crate) trust: DnsOracleTrust,
    pub(crate) confidence_score: u8,
    pub(crate) selected: Option<DnsOracleCandidate<T>>,
    pub(crate) agreement_resolver_ids: Vec<String>,
    pub(crate) disagreement_resolver_ids: Vec<String>,
    pub(crate) attempts: Vec<DnsOracleAttempt>,
}

impl<T> DnsOracleAssessment<T> {
    pub(crate) fn fallback_resolver_used(&self) -> Option<String> {
        self.selected
            .as_ref()
            .and_then(|candidate| (!candidate.is_primary).then(|| resolver_label(&candidate.endpoint)))
    }

    pub(crate) fn selected_latency_ms(&self) -> Option<u128> {
        self.selected.as_ref().map(|candidate| candidate.latency_ms)
    }

    pub(crate) fn primary_latency_ms(&self) -> Option<u128> {
        self.attempts.iter().find_map(|attempt| attempt.is_primary.then_some(attempt.latency_ms))
    }

    pub(crate) fn preferred_latency_ms(&self) -> u128 {
        self.selected_latency_ms().or_else(|| self.primary_latency_ms()).unwrap_or(0)
    }

    pub(crate) fn detail_entries(&self) -> Vec<ProbeDetail> {
        let attempted = self.attempts.iter().map(|attempt| attempt.resolver_id.clone()).collect::<Vec<_>>();
        let succeeded = self
            .attempts
            .iter()
            .filter(|attempt| attempt.error.is_none() && !attempt.answers.is_empty())
            .map(|attempt| attempt.resolver_id.clone())
            .collect::<Vec<_>>();
        let failed = self
            .attempts
            .iter()
            .filter(|attempt| attempt.error.is_some() || attempt.answers.is_empty())
            .map(|attempt| attempt.resolver_id.clone())
            .collect::<Vec<_>>();

        let mut details = Vec::with_capacity(8);
        push_detail(&mut details, "oracleTrust", self.trust.as_str().to_string());
        push_detail(&mut details, "oracleConfidenceScore", self.confidence_score.to_string());
        push_detail(
            &mut details,
            "oracleSelectedResolverId",
            self.selected.as_ref().map(|candidate| resolver_label(&candidate.endpoint)).unwrap_or_default(),
        );
        push_detail(&mut details, "oracleAgreementResolvers", self.agreement_resolver_ids.join("|"));
        push_detail(&mut details, "oracleDisagreementResolvers", self.disagreement_resolver_ids.join("|"));
        push_detail(&mut details, "oracleTriedResolvers", attempted.join("|"));
        push_detail(&mut details, "oracleSuccessfulResolvers", succeeded.join("|"));
        push_detail(&mut details, "oracleFailedResolvers", failed.join("|"));
        details
    }
}

#[inline(never)]
fn push_detail(details: &mut Vec<ProbeDetail>, key: &str, value: String) {
    details.push(ProbeDetail { key: key.to_string(), value });
}

pub(crate) fn evaluate_dns_oracles<T, F, A>(
    primary_endpoint: EncryptedDnsEndpoint,
    fallback_endpoints: &[EncryptedDnsEndpoint],
    max_fallbacks: usize,
    mut resolve: F,
    answer_extractor: A,
) -> DnsOracleAssessment<T>
where
    T: Clone,
    F: FnMut(&EncryptedDnsEndpoint) -> Result<T, String>,
    A: Fn(&T) -> Vec<String>,
{
    let mut attempts = Vec::new();
    let mut successes = Vec::new();
    let endpoints = std::iter::once(primary_endpoint.clone())
        .chain(fallback_endpoints.iter().take(max_fallbacks).cloned())
        .collect::<Vec<_>>();

    for (index, endpoint) in endpoints.into_iter().enumerate() {
        let started = Instant::now();
        match resolve(&endpoint) {
            Ok(value) => {
                let answers = normalize_answers(answer_extractor(&value));
                let latency_ms = started.elapsed().as_millis();
                attempts.push(DnsOracleAttempt {
                    resolver_id: resolver_label(&endpoint),
                    is_primary: index == 0,
                    latency_ms,
                    answers: answers.clone(),
                    error: None,
                });
                if !answers.is_empty() {
                    successes.push(DnsOracleCandidate { endpoint, value, answers, is_primary: index == 0, latency_ms });
                }
            }
            Err(error) => {
                attempts.push(DnsOracleAttempt {
                    resolver_id: resolver_label(&endpoint),
                    is_primary: index == 0,
                    latency_ms: started.elapsed().as_millis(),
                    answers: Vec::new(),
                    error: Some(error),
                });
            }
        }
    }

    build_assessment(attempts, successes)
}

fn build_assessment<T>(attempts: Vec<DnsOracleAttempt>, successes: Vec<DnsOracleCandidate<T>>) -> DnsOracleAssessment<T>
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
        };
    }

    if successes.len() == 1 {
        let fallback = successes[0].clone();
        let fallback_id = resolver_label(&fallback.endpoint);
        return DnsOracleAssessment {
            trust: DnsOracleTrust::SingleFallback,
            confidence_score: 40,
            selected: Some(fallback.clone()),
            agreement_resolver_ids: vec![fallback_id],
            disagreement_resolver_ids: Vec::new(),
            attempts,
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
    }
}

fn normalize_answers(answers: Vec<String>) -> Vec<String> {
    answers
        .into_iter()
        .map(|answer| answer.trim().to_string())
        .filter(|answer| !answer.is_empty())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

fn resolver_label(endpoint: &EncryptedDnsEndpoint) -> String {
    endpoint
        .resolver_id
        .clone()
        .or_else(|| (!endpoint.host.is_empty()).then(|| endpoint.host.clone()))
        .unwrap_or_else(|| endpoint.protocol.as_str().to_string())
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    use super::{evaluate_dns_oracles, DnsOracleTrust};

    #[derive(Clone, Debug)]
    struct StubAnswer {
        answers: Vec<String>,
    }

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
    fn quorum_prefers_agreed_answer_set() {
        let primary = endpoint("primary");
        let fallback_a = endpoint("fallback-a");
        let fallback_b = endpoint("fallback-b");
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-a".to_string(), Ok(StubAnswer { answers: vec!["2.2.2.2".to_string()] })),
            ("fallback-b".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
        ]);

        let assessment = evaluate_dns_oracles(
            primary,
            &[fallback_a, fallback_b],
            2,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.answers.clone(),
        );

        assert_eq!(assessment.trust, DnsOracleTrust::TrustedAgreement);
        assert_eq!(
            assessment.selected.as_ref().map(|selected| selected.answers.clone()),
            Some(vec!["1.1.1.1".to_string()])
        );
        assert_eq!(assessment.agreement_resolver_ids, vec!["primary".to_string(), "fallback-b".to_string()]);
        assert_eq!(assessment.fallback_resolver_used(), None);
    }

    #[test]
    fn disagreement_stays_untrusted_when_oracles_do_not_converge() {
        let primary = endpoint("primary");
        let fallback_a = endpoint("fallback-a");
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-a".to_string(), Ok(StubAnswer { answers: vec!["2.2.2.2".to_string()] })),
        ]);

        let assessment = evaluate_dns_oracles(
            primary,
            &[fallback_a],
            1,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.answers.clone(),
        );

        assert_eq!(assessment.trust, DnsOracleTrust::Disagreement);
        assert!(assessment.selected.is_none());
        assert_eq!(assessment.disagreement_resolver_ids, vec!["primary".to_string(), "fallback-a".to_string()]);
    }

    #[test]
    fn single_fallback_success_does_not_gain_trusted_oracle_status() {
        let primary = endpoint("primary");
        let fallback_a = endpoint("fallback-a");
        let answers = BTreeMap::from([
            ("primary".to_string(), Err("connection reset".to_string())),
            ("fallback-a".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
        ]);

        let assessment = evaluate_dns_oracles(
            primary,
            &[fallback_a],
            1,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.answers.clone(),
        );

        assert_eq!(assessment.trust, DnsOracleTrust::SingleFallback);
        assert_eq!(assessment.fallback_resolver_used(), Some("fallback-a".to_string()));
        assert!(!assessment.trust.allows_tampering_classification());
    }
}
