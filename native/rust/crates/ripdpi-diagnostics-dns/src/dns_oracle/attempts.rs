use std::time::Instant;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use super::contracts::{DnsOracleAttempt, DnsOracleCandidate};
use super::normalization::normalize_answers;
use super::resolver_label::resolver_label;

pub(super) fn execute_oracle_attempts<T, F, A>(
    primary_endpoint: EncryptedDnsEndpoint,
    fallback_endpoints: &[EncryptedDnsEndpoint],
    max_fallbacks: usize,
    mut resolve: F,
    answer_extractor: A,
) -> (Vec<DnsOracleAttempt>, Vec<DnsOracleCandidate<T>>)
where
    F: FnMut(&EncryptedDnsEndpoint) -> Result<T, String>,
    A: Fn(&T) -> Vec<String>,
{
    let mut attempts = Vec::new();
    let mut successes = Vec::new();
    let endpoints = std::iter::once(primary_endpoint)
        .chain(fallback_endpoints.iter().take(max_fallbacks).cloned())
        .collect::<Vec<_>>();

    for (index, endpoint) in endpoints.into_iter().enumerate() {
        let is_primary = index == 0;
        let started = Instant::now();
        match resolve(&endpoint) {
            Ok(value) => {
                let answers = normalize_answers(answer_extractor(&value));
                let latency_ms = started.elapsed().as_millis();
                attempts.push(DnsOracleAttempt {
                    resolver_id: resolver_label(&endpoint),
                    is_primary,
                    latency_ms,
                    answers: answers.clone(),
                    error: None,
                });
                if !answers.is_empty() {
                    successes.push(DnsOracleCandidate { endpoint, value, answers, is_primary, latency_ms });
                }
            }
            Err(error) => {
                attempts.push(DnsOracleAttempt {
                    resolver_id: resolver_label(&endpoint),
                    is_primary,
                    latency_ms: started.elapsed().as_millis(),
                    answers: Vec::new(),
                    error: Some(error),
                });
            }
        }
    }

    (attempts, successes)
}
