use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

pub(super) fn build_https_sample(
    candidate: &StrategyCandidateSpec,
    target: &DomainTarget,
    outcome: String,
    details: Vec<ProbeDetail>,
    started_at_ms: u64,
    latency_ms: u64,
    retry_count: usize,
    reason: Option<String>,
) -> ProbeSample {
    debug_assert!(matches!(
        outcome.as_str(),
        "tls_cert_invalid" | "tls_ok" | "tls_version_split" | "tls_ech_only" | "tls_handshake_failed"
    ));
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
        latency_ms,
        started_at_ms,
        retry_count,
        protocol: "HTTPS".to_string(),
        reason,
    }
}
