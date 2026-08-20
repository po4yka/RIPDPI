use crate::candidates::StrategyCandidateSpec;
use crate::http::try_http_request_targets;
use crate::transport::{TransportConfig, domain_connect_targets};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};
use crate::util::now_ms;

use super::support::candidate_probe_details;
use crate::execution::scoring::ProbeSample;

mod outcome;

use outcome::classify_http_observation;

pub(super) fn run_http_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let http_port = target.http_port.unwrap_or(80);
    let connect_targets = domain_connect_targets(target);
    let observation =
        try_http_request_targets(&connect_targets, http_port, transport, &target.host, &target.http_path, false);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, fingerprint_name) = classify_http_observation(&observation);
    let h3 = observation.response.as_ref().and_then(|r| r.headers.get("alt-svc")).is_some_and(|v| v.contains("h3"));
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
        attempt_token: None,
        quality: if outcome == "http_ok" {
            3
        } else if outcome == "http_redirect" {
            2
        } else if outcome == "http_blockpage" {
            1
        } else {
            0
        },
        latency_ms,
    }
}
