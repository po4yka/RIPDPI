use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::candidates::{StrategyCandidateSpec, target_probe_pause_ms};
use crate::types::QuicTarget;
use crate::util::stable_probe_hash;

use crate::execution::runtime::{CandidateRuntimeLauncher, CandidateRuntimeSupervisor, probe_runtime_transport};
use crate::execution::scoring::{
    CandidateExecution, CandidateScore, build_candidate_execution, cancelled_candidate_execution,
    failed_candidate_execution, not_applicable_candidate_execution,
};

use super::run_quic_strategy_probe;

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
