mod attempts;
mod consensus;
mod contracts;
mod details;
mod normalization;
mod resolver_label;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use attempts::execute_oracle_attempts;
use consensus::build_assessment;

pub use contracts::{DnsOracleAssessment, DnsOracleAttempt, DnsOracleCandidate, DnsOracleResponse, DnsOracleTrust};

pub fn evaluate_dns_oracles<T, F, A>(
    primary_endpoint: EncryptedDnsEndpoint,
    fallback_endpoints: &[EncryptedDnsEndpoint],
    max_fallbacks: usize,
    resolve: F,
    answer_extractor: A,
) -> DnsOracleAssessment<T>
where
    T: Clone,
    F: FnMut(&EncryptedDnsEndpoint) -> Result<T, String>,
    A: Fn(&T) -> Vec<String>,
{
    let (attempts, successes) =
        execute_oracle_attempts(primary_endpoint, fallback_endpoints, max_fallbacks, resolve, answer_extractor);
    build_assessment(attempts, successes)
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    use super::{DnsOracleTrust, evaluate_dns_oracles};

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
    fn trusted_agreement_stops_before_later_timeout_fallbacks() {
        let primary = endpoint("primary");
        let fallback_a = endpoint("fallback-a");
        let fallback_b = endpoint("fallback-b");
        let fallback_c = endpoint("fallback-c");
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-a".to_string(), Err("timeout".to_string())),
            ("fallback-b".to_string(), Ok(StubAnswer { answers: vec!["1.1.1.1".to_string()] })),
            ("fallback-c".to_string(), Err("timeout".to_string())),
        ]);

        let assessment = evaluate_dns_oracles(
            primary,
            &[fallback_a, fallback_b, fallback_c],
            3,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.answers.clone(),
        );

        assert_eq!(
            (
                assessment.trust,
                assessment
                    .attempts
                    .iter()
                    .map(|attempt| (attempt.resolver_id.as_str(), attempt.error.as_deref()))
                    .collect::<Vec<_>>(),
            ),
            (
                DnsOracleTrust::TrustedAgreement,
                vec![("primary", None), ("fallback-a", Some("timeout")), ("fallback-b", None)],
            )
        );
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

    #[test]
    fn unavailable_oracle_details_keep_classified_attempt_diagnostics() {
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[],
            0,
            |_| Err("connection refused while contacting private.resolver.invalid".to_string()),
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
