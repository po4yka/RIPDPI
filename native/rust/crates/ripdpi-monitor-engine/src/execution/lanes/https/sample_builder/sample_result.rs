use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::{ProbeAttemptMetadata, ProbeSample};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};
use crate::util::now_ms;

pub(super) fn build_https_sample(
    candidate: &StrategyCandidateSpec,
    target: &DomainTarget,
    outcome: String,
    mut details: Vec<ProbeDetail>,
    started_at_ms: u64,
    retry_count: usize,
) -> ProbeSample {
    debug_assert!(matches!(
        outcome.as_str(),
        "tls_cert_invalid" | "tls_ok" | "tls_version_split" | "tls_ech_only" | "tls_handshake_failed"
    ));
    details.push(ProbeDetail { key: "probeRetryCount".to_string(), value: retry_count.to_string() });
    let reason = details
        .iter()
        .find(|detail| detail.key == "tlsError" && detail.value != "none")
        .map(|detail| detail.value.clone());
    let duration_ms = now_ms().saturating_sub(started_at_ms);
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: matches!(outcome.as_str(), "tls_ok" | "tls_version_split"),
        weight: 2,
        domain: Some(target.host.clone()),
        is_control: target.is_control,
        quality: match outcome.as_str() {
            "tls_ok" => 4,
            "tls_version_split" => 3,
            _ => 0,
        },
        attempt: ProbeAttemptMetadata::new(started_at_ms, duration_ms, retry_count, "HTTPS", reason),
    }
}
