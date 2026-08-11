use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::{ProbeAttemptMetadata, ProbeSample};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult, ScanPathMode};
use crate::util::{classify_probe_outcome, now_ms};

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
    let success = classify_probe_outcome("strategy_https", &ScanPathMode::RawPath, &outcome).healthy_enough_for_summary;
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tls_version_split_sample_is_not_successful() {
        let candidate = crate::candidates::candidate_spec(
            "test",
            "Test",
            "test",
            ripdpi_monitor_adapter::proxy_config::ProxyUiConfig::default(),
        );
        let target = DomainTarget {
            host: "example.test".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        };

        let sample = build_https_sample(&candidate, &target, "tls_version_split".to_string(), vec![], 0, 0);

        assert!(!sample.success);
    }
}
