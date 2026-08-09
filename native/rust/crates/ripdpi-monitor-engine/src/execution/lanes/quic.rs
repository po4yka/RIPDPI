use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use ripdpi_packets::{QUIC_V1_VERSION, build_realistic_quic_initial, parse_quic_initial};

use crate::candidates::{StrategyCandidateSpec, target_probe_pause_ms};
use crate::transport::{TransportConfig, quic_connect_targets, relay_udp_payload_observed};
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};
use crate::util::{now_ms, stable_probe_hash};

use super::support::candidate_probe_details;
use crate::execution::runtime::{CandidateRuntimeLauncher, probe_runtime_transport};
use crate::execution::scoring::{
    CandidateExecution, CandidateScore, ProbeSample, build_candidate_execution, cancelled_candidate_execution,
    failed_candidate_execution, not_applicable_candidate_execution,
};

pub fn execute_quic_candidate(
    runtime_launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    targets: &[QuicTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 2, "No QUIC targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(runtime_launcher, spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));
            for (index, target) in ordered_targets.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 2);
                }
                if index > 0 {
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &target.host)));
                }
                score.add(run_quic_strategy_probe(&transport, target, spec));
            }
            drop(runtime);
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "quic",
            )
            .record(probe_started.elapsed().as_secs_f64());
            build_candidate_execution(spec, score, 2)
        }
        Err(err) => failed_candidate_execution(spec, targets.len(), 2, err.to_string()),
    }
}
pub(super) fn run_quic_strategy_probe(
    transport: &TransportConfig,
    target: &QuicTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str())).unwrap_or_default();
    let response = relay_udp_payload_observed(&quic_connect_targets(target), target.port, transport, &payload);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error, connected_addr) = match response {
        Ok(result) if parse_quic_initial(&result.payload).is_some() => (
            "quic_initial_response".to_string(),
            "quic_initial_response".to_string(),
            "none".to_string(),
            result.connected_addr,
        ),
        Ok(result) if !result.payload.is_empty() => {
            ("quic_response".to_string(), "quic_response".to_string(), "none".to_string(), result.connected_addr)
        }
        Ok(result) => ("quic_empty".to_string(), "quic_empty".to_string(), "none".to_string(), result.connected_addr),
        Err(err) => ("quic_error".to_string(), "quic_error".to_string(), err, None),
    };
    let attempt_reason = (error != "none").then(|| error.clone());
    let mut details = candidate_probe_details(candidate, "QUIC", latency_ms);
    details.extend([
        ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ProbeDetail { key: "status".to_string(), value: status },
        ProbeDetail { key: "error".to_string(), value: error },
    ]);
    if let Some(addr) = connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
        if let Some(provider) = crate::cdn_ech::opportunistic_ech_provider_for_ip(addr.ip()) {
            details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider.to_string() });
        }
    }
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_quic".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: matches!(outcome.as_str(), "quic_initial_response" | "quic_response"),
        weight: 2,
        domain: Some(target.host.clone()),
        is_control: false,
        quality: match outcome.as_str() {
            "quic_initial_response" => 4,
            "quic_response" => 3,
            _ => 0,
        },
        latency_ms,
        started_at_ms: started,
        retry_count: 0,
        protocol: "QUIC".to_string(),
        reason: attempt_reason,
    }
}
