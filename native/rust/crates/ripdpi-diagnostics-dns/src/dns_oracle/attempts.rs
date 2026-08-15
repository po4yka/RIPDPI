use std::collections::BTreeMap;
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, with_scan_io_deadline};
use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use super::contracts::{DnsOracleAttempt, DnsOracleCandidate, DnsOracleConfig, DnsOracleTermination};
use super::normalization::normalize_answers;
use super::resolver_label::resolver_label;

pub(super) struct OracleAttempts<T> {
    pub attempts: Vec<DnsOracleAttempt>,
    pub successes: Vec<DnsOracleCandidate<T>>,
    pub termination: DnsOracleTermination,
}

pub(super) fn execute_oracle_attempts<T, F, A>(
    primary_endpoint: EncryptedDnsEndpoint,
    fallback_endpoints: &[EncryptedDnsEndpoint],
    max_fallbacks: usize,
    config: DnsOracleConfig,
    is_cancelled: impl Fn() -> bool,
    mut resolve: F,
    answer_extractor: A,
) -> OracleAttempts<T>
where
    F: FnMut(&EncryptedDnsEndpoint, Duration) -> Result<T, String>,
    A: Fn(&T) -> Vec<String>,
{
    let mut attempts = Vec::new();
    let mut successes = Vec::new();
    let endpoints = std::iter::once(primary_endpoint)
        .chain(fallback_endpoints.iter().take(max_fallbacks).cloned())
        .collect::<Vec<_>>();
    let deadline = Instant::now() + config.total_budget;
    let mut termination = DnsOracleTermination::FallbackLimitReached;

    for (index, endpoint) in endpoints.into_iter().enumerate() {
        if is_cancelled() {
            termination = DnsOracleTermination::Cancelled;
            break;
        }
        let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
            termination = DnsOracleTermination::CascadeBudgetExhausted;
            break;
        };
        if remaining < config.min_attempt_budget {
            termination = DnsOracleTermination::CascadeBudgetExhausted;
            break;
        }
        let is_primary = index == 0;
        let started = Instant::now();
        let attempt_budget = remaining.min(config.max_attempt_budget);
        let attempt_deadline = Instant::now() + attempt_budget;
        let attempt_deadline = deadline.min(attempt_deadline);
        let attempt_deadline =
            active_scan_io_deadline().map_or(attempt_deadline, |scan_deadline| attempt_deadline.min(scan_deadline));
        match with_scan_io_deadline(Some(attempt_deadline), || resolve(&endpoint, attempt_budget)) {
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
                    if has_trusted_agreement(&successes) {
                        termination = DnsOracleTermination::QuorumReached;
                        break;
                    }
                }
            }
            Err(error) => {
                let error = classify_oracle_error(&error).to_string();
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

    OracleAttempts { attempts, successes, termination }
}

fn has_trusted_agreement<T>(successes: &[DnsOracleCandidate<T>]) -> bool {
    let mut agreement_counts = BTreeMap::<&[String], usize>::new();
    for candidate in successes {
        let count = agreement_counts.entry(&candidate.answers).or_default();
        *count += 1;
        if *count >= 2 {
            return true;
        }
    }
    false
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
