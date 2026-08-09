use std::sync::LazyLock;

use crate::blockpage_fingerprints::{BlockpageFingerprint, load_fingerprints};
use crate::candidates::StrategyCandidateSpec;
use crate::http::{classify_http_response_with_fingerprints, is_blockpage, try_http_request_targets};
use crate::transport::{TransportConfig, domain_connect_targets};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

use super::support::candidate_probe_details;
use crate::execution::scoring::{ProbeAttemptMetadata, ProbeSample};

static BLOCKPAGE_FINGERPRINTS: LazyLock<Vec<BlockpageFingerprint>> = LazyLock::new(load_fingerprints);

pub(super) fn run_http_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = crate::util::now_ms();
    let http_port = target.http_port.unwrap_or(80);
    let connect_targets = domain_connect_targets(target);
    let observation =
        try_http_request_targets(&connect_targets, http_port, transport, &target.host, &target.http_path, false);
    let latency_ms = crate::util::now_ms().saturating_sub(started);
    // Try fingerprint-based classification first, then fall back to heuristics.
    let (outcome, fingerprint_name) = if let Some(response) = &observation.response {
        let (fp_outcome, fp_name) = classify_http_response_with_fingerprints(response, &BLOCKPAGE_FINGERPRINTS);
        let outcome = if fp_name.is_some() {
            fp_outcome
        } else if is_blockpage(&observation) {
            "http_blockpage".to_string()
        } else if observation.status == "http_ok" {
            "http_ok".to_string()
        } else if observation.status.starts_with("http_status_3") {
            "http_redirect".to_string()
        } else if observation.error.is_some() {
            "http_unreachable".to_string()
        } else {
            observation.status.clone()
        };
        (outcome, fp_name)
    } else if observation.error.is_some() {
        ("http_unreachable".to_string(), None)
    } else {
        (observation.status.clone(), None)
    };
    let h3 = observation.response.as_ref().and_then(|r| r.headers.get("alt-svc")).is_some_and(|v| v.contains("h3"));
    let attempt = ProbeAttemptMetadata::new(started, latency_ms, 0, "HTTP", observation.error.clone());
    let mut details = candidate_probe_details(candidate, "HTTP", latency_ms);
    details.extend([
        ProbeDetail { key: "status".to_string(), value: observation.status },
        ProbeDetail { key: "error".to_string(), value: observation.error.unwrap_or_else(|| "none".to_string()) },
        ProbeDetail {
            key: "redirectLocation".to_string(),
            value: if outcome == "http_redirect" {
                observation
                    .response
                    .as_ref()
                    .and_then(|r| r.headers.get("location"))
                    .cloned()
                    .unwrap_or_else(|| "none".to_string())
            } else {
                "none".to_string()
            },
        },
    ]);
    if let Some(fp) = &fingerprint_name {
        details.push(ProbeDetail { key: "blockpageFingerprint".to_string(), value: fp.clone() });
    }
    details.push(ProbeDetail { key: "h3Advertised".to_string(), value: h3.to_string() });
    push_http_timing_details(&mut details, observation.ttfb_ms);
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_http".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: outcome == "http_ok" || outcome == "http_redirect",
        weight: 1,
        domain: Some(target.host.clone()),
        is_control: target.is_control,
        quality: if outcome == "http_ok" {
            3
        } else if outcome == "http_redirect" {
            2
        } else if outcome == "http_blockpage" {
            1
        } else {
            0
        },
        attempt,
    }
}

fn push_http_timing_details(details: &mut Vec<ProbeDetail>, ttfb_ms: Option<u64>) {
    if let Some(ttfb_ms) = ttfb_ms {
        details.push(ProbeDetail { key: "ttfbMs".to_string(), value: ttfb_ms.to_string() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn http_probe_exports_measured_ttfb_detail() {
        let mut details = Vec::new();

        push_http_timing_details(&mut details, Some(23));

        assert_eq!(details.len(), 1);
        assert_eq!(details[0].key, "ttfbMs");
        assert_eq!(details[0].value, "23");
    }
}
