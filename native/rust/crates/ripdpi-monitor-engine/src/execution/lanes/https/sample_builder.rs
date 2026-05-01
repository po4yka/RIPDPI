use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::transport::TransportConfig;
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

use super::detail_builder::build_https_probe_details;
use super::observation_collection::collect_https_observations;
use super::outcome_classification::classify_https_outcome;
use super::retry_policy::apply_https_retry_policy;

pub(super) fn build_https_probe_sample(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeSample {
    let observations = collect_https_observations(transport, target, tls_verifier);
    let outcome = classify_https_outcome(&observations);
    let mut details = build_https_probe_details(candidate, &observations, &outcome);

    let retry =
        apply_https_retry_policy(transport, target, tls_verifier, observations.https_port, &outcome, &mut details);
    details.push(ProbeDetail { key: "probeRetryCount".to_string(), value: retry.retry_count.to_string() });

    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: retry.final_outcome.clone(),
            details,
        },
        success: matches!(retry.final_outcome.as_str(), "tls_ok" | "tls_version_split"),
        weight: 2,
        domain: Some(target.host.clone()),
        quality: match retry.final_outcome.as_str() {
            "tls_ok" => 4,
            "tls_version_split" => 3,
            _ => 0,
        },
        latency_ms: observations.latency_ms,
    }
}
