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
    let mut successes: Vec<DnsOracleCandidate<T>> = Vec::new();
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
                    let agreement_reached = successes.iter().any(|candidate| candidate.answers == answers);
                    successes.push(DnsOracleCandidate { endpoint, value, answers, is_primary, latency_ms });
                    if agreement_reached {
                        break;
                    }
                }
            }
            Err(error) => {
                attempts.push(DnsOracleAttempt {
                    resolver_id: resolver_label(&endpoint),
                    is_primary,
                    latency_ms: started.elapsed().as_millis(),
                    answers: Vec::new(),
                    error: Some(classify_oracle_error(&error).to_string()),
                });
            }
        }
    }

    (attempts, successes)
}

fn classify_oracle_error(error: &str) -> &'static str {
    let normalized = error.to_ascii_lowercase();
    if normalized.contains("timed out") || normalized.contains("timeout") {
        "timeout"
    } else if normalized.contains("connection refused") || normalized.contains("refused") {
        "connection_refused"
    } else if normalized.contains("connection reset") || normalized.contains("reset") {
        "connection_reset"
    } else if normalized.contains("certificate") || normalized.contains("tls") {
        "tls_failure"
    } else if normalized.contains("http") || normalized.contains("status") {
        "http_error"
    } else if normalized.contains("invalid") || normalized.contains("parse") || normalized.contains("malformed") {
        "invalid_response"
    } else {
        "network_error"
    }
}

#[cfg(test)]
mod tests {
    use super::classify_oracle_error;

    #[test]
    fn oracle_error_classifier_never_retains_raw_error_text() {
        assert_eq!(
            classify_oracle_error("connection refused while contacting private.resolver.invalid"),
            "connection_refused"
        );
        assert_eq!(classify_oracle_error("unexpected resolver payload 198.51.100.1"), "network_error");
    }
}
