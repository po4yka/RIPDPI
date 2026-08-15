mod attempts;
mod consensus;
mod contracts;
mod details;
mod normalization;
mod resolver_label;

use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use attempts::execute_oracle_attempts;
use consensus::build_assessment;

pub use contracts::{
    DnsOracleAssessment, DnsOracleAttempt, DnsOracleCandidate, DnsOracleConfig, DnsOracleResponse,
    DnsOracleTermination, DnsOracleTrust,
};

pub fn evaluate_dns_oracles<T, F, A>(
    primary_endpoint: EncryptedDnsEndpoint,
    fallback_endpoints: &[EncryptedDnsEndpoint],
    max_fallbacks: usize,
    config: DnsOracleConfig,
    is_cancelled: impl Fn() -> bool,
    resolve: F,
    answer_extractor: A,
) -> DnsOracleAssessment<T>
where
    T: Clone,
    F: FnMut(&EncryptedDnsEndpoint, Duration) -> Result<T, String>,
    A: Fn(&T) -> Vec<String>,
{
    let executed = execute_oracle_attempts(
        primary_endpoint,
        fallback_endpoints,
        max_fallbacks,
        config,
        is_cancelled,
        resolve,
        answer_extractor,
    );
    build_assessment(executed.attempts, executed.successes, executed.termination)
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::time::{Duration, Instant};

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    use super::{DnsOracleConfig, DnsOracleTermination, DnsOracleTrust, evaluate_dns_oracles};

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
            odoh: None,
        }
    }

    #[test]
    fn quorum_prefers_agreed_answer_set() {
        let primary = endpoint("primary");
        let fallback_a = endpoint("fallback-a");
        let fallback_b = endpoint("fallback-b");
        let fallback_c = endpoint("fallback-c");
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-a".to_string(), Err("resolver timeout".to_string())),
            ("fallback-b".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-c".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
        ]);
        let mut attempted = Vec::new();

        let assessment = evaluate_dns_oracles(
            primary,
            &[fallback_a, fallback_b, fallback_c],
            3,
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
                let resolver_id = endpoint.resolver_id.as_deref().unwrap_or_default();
                attempted.push(resolver_id.to_string());
                answers.get(resolver_id).cloned().unwrap_or_else(|| Err("missing".to_string()))
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
        assert_eq!(attempted, vec!["primary", "fallback-a", "fallback-b"]);
        assert_eq!(assessment.termination, DnsOracleTermination::QuorumReached);
        assert_eq!(assessment.attempts[1].error.as_deref(), Some("timeout"));
    }

    #[test]
    fn hung_resolvers_share_one_wall_clock_budget() {
        let started = Instant::now();
        let mut attempted = Vec::new();
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback-a"), endpoint("fallback-b")],
            2,
            DnsOracleConfig::new(Duration::from_millis(40), Duration::from_millis(20), Duration::from_millis(5)),
            || false,
            |endpoint, attempt_budget| {
                attempted.push(endpoint.resolver_id.clone().expect("resolver id"));
                std::thread::sleep(attempt_budget);
                Err("timeout".to_string())
            },
            |answer: &StubAnswer| answer.answers.clone(),
        );

        assert!(started.elapsed() < Duration::from_millis(100));
        assert_eq!(assessment.termination, DnsOracleTermination::CascadeBudgetExhausted);
        assert_eq!(attempted, vec!["primary", "fallback-a"]);
        assert!(assessment.attempts.iter().all(|attempt| attempt.error.as_deref() == Some("timeout")));
    }

    #[test]
    fn resolver_io_inherits_the_per_attempt_budget() {
        let max_attempt_budget = Duration::from_millis(20);
        let mut inherited_budgets = Vec::new();

        let _assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback-a")],
            1,
            DnsOracleConfig::new(Duration::from_millis(200), max_attempt_budget, Duration::from_millis(1)),
            || false,
            |_, _| {
                inherited_budgets.push(
                    ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout(Duration::from_secs(1))
                        .expect("attempt has an active I/O deadline"),
                );
                Err("timeout".to_string())
            },
            |answer: &StubAnswer| answer.answers.clone(),
        );

        assert!(
            inherited_budgets.len() == 2 && inherited_budgets.iter().all(|budget| *budget <= max_attempt_budget),
            "each resolver must inherit its own cap: {inherited_budgets:?}",
        );
    }

    #[test]
    fn disagreement_stays_untrusted_when_oracles_do_not_converge() {
        let primary = endpoint("primary");
        let fallback_a = endpoint("fallback-a");
        let fallback_b = endpoint("fallback-b");
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-a".to_string(), Ok(StubAnswer { answers: vec!["2.2.2.2".to_string()] })),
            ("fallback-b".to_string(), Ok(StubAnswer { answers: vec!["3.3.3.3".to_string()] })),
        ]);
        let mut attempted = Vec::new();

        let assessment = evaluate_dns_oracles(
            primary,
            &[fallback_a, fallback_b],
            2,
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
                let resolver_id = endpoint.resolver_id.as_deref().unwrap_or_default();
                attempted.push(resolver_id.to_string());
                answers.get(resolver_id).cloned().unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.answers.clone(),
        );

        assert_eq!(assessment.trust, DnsOracleTrust::Disagreement);
        assert!(assessment.selected.is_none());
        assert_eq!(
            assessment.disagreement_resolver_ids,
            vec!["primary".to_string(), "fallback-a".to_string(), "fallback-b".to_string()]
        );
        assert_eq!(attempted, vec!["primary", "fallback-a", "fallback-b"]);
        assert_eq!(assessment.termination, DnsOracleTermination::FallbackLimitReached);
    }

    #[test]
    fn expired_budget_emits_one_bounded_terminal_outcome() {
        let mut attempted = 0;
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback-a")],
            1,
            DnsOracleConfig::new(Duration::ZERO, Duration::from_millis(20), Duration::from_millis(5)),
            || false,
            |_, _| {
                attempted += 1;
                Err("timeout".to_string())
            },
            |answer: &StubAnswer| answer.answers.clone(),
        );

        assert_eq!(attempted, 0);
        assert!(assessment.attempts.is_empty());
        assert_eq!(assessment.termination, DnsOracleTermination::CascadeBudgetExhausted);
    }

    #[test]
    fn cancellation_does_not_start_the_next_resolver() {
        let cancelled = std::cell::Cell::new(false);
        let mut attempted = Vec::new();
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback-a")],
            1,
            DnsOracleConfig::default(),
            || cancelled.get(),
            |endpoint, _| {
                attempted.push(endpoint.resolver_id.clone().expect("resolver id"));
                cancelled.set(true);
                Err("timeout".to_string())
            },
            |answer: &StubAnswer| answer.answers.clone(),
        );

        assert_eq!(attempted, vec!["primary"]);
        assert_eq!(assessment.termination, DnsOracleTermination::Cancelled);
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
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
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

    #[test]
    fn unavailable_oracle_details_keep_classified_attempt_diagnostics() {
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[],
            0,
            DnsOracleConfig::default(),
            || false,
            |_, _| Err("connection refused while contacting private.resolver.invalid".to_string()),
            |answer: &StubAnswer| answer.answers.clone(),
        );

        assert_eq!(assessment.trust, DnsOracleTrust::Unavailable);
        let details = assessment.detail_entries();
        let attempts = details
            .iter()
            .find(|detail| detail.key == "oracleAttemptDiagnostics")
            .map(|detail| detail.value.as_str())
            .expect("safe oracle attempt diagnostics");
        assert!(attempts.starts_with("primary:connection_refused:"));
        assert!(attempts.ends_with(":0"));
        assert!(!attempts.contains("private.resolver.invalid"));
    }
}
