mod outcome;

use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use ripdpi_packets::{QUIC_V1_VERSION, build_realistic_quic_initial};

use crate::candidates::{StrategyCandidateSpec, target_probe_pause_ms};
use crate::transport::{TransportConfig, quic_connect_targets, relay_udp_payload_observed};
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};
use crate::util::{now_ms, stable_probe_hash};

use super::support::candidate_probe_details;
use crate::execution::runtime::{CandidateRuntimeLauncher, CandidateRuntimeSupervisor, probe_runtime_transport};
use crate::execution::scoring::{
    CandidateExecution, CandidateScore, ProbeSample, build_candidate_execution, cancelled_candidate_execution,
    failed_candidate_execution, not_applicable_candidate_execution,
};
use outcome::classify_quic_response;

pub fn execute_quic_candidate(
    runtime_launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    targets: &[QuicTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    cancel: &AtomicBool,
    supervisor: &CandidateRuntimeSupervisor,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 2, "No QUIC targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(runtime_launcher, spec, runtime_context) {
        Ok(runtime) => {
            let runtime = supervisor.supervise(runtime);
            let generation = runtime.runtime().generation();
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));
            for (index, target) in ordered_targets.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    runtime.shutdown();
                    return cancelled_candidate_execution(spec, score, 2);
                }
                if index > 0 {
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &target.host)));
                }
                let attempt_token = crate::CandidateAttemptCorrelationId::evaluated(generation, index as u64 + 1);
                let transport = attempt_token.as_ref().map_or_else(
                    || runtime.runtime().transport(),
                    |token| runtime.runtime().transport_for_attempt(token),
                );
                let mut sample = run_quic_strategy_probe(&transport, target, spec);
                sample.attempt_token = attempt_token;
                score.add(sample);
            }
            let terminal_receipt = runtime.shutdown();
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "quic",
            )
            .record(probe_started.elapsed().as_secs_f64());
            let mut execution = build_candidate_execution(spec, score, 2);
            execution.attach_terminal_evidence(generation, &terminal_receipt);
            execution
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
    let outcome = classify_quic_response(response);
    let mut details = candidate_probe_details(candidate, "QUIC", latency_ms);
    details.extend([
        ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ProbeDetail { key: "status".to_string(), value: outcome.status.clone() },
        ProbeDetail { key: "error".to_string(), value: outcome.error },
    ]);
    if let Some(addr) = outcome.connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
        if let Some(provider) = crate::cdn_ech::opportunistic_ech_provider_for_ip(addr.ip()) {
            details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider.to_string() });
        }
    }
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_quic".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.kind.clone(),
            details,
        },
        success: matches!(outcome.kind.as_str(), "quic_initial_response" | "quic_response"),
        weight: 2,
        domain: Some(target.host.clone()),
        is_control: false,
        attempt_token: None,
        quality: match outcome.kind.as_str() {
            "quic_initial_response" => 4,
            "quic_response" => 3,
            _ => 0,
        },
        latency_ms,
    }
}
