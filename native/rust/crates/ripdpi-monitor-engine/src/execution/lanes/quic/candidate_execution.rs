use std::sync::atomic::AtomicBool;

use ripdpi_proxy_config::ProxyRuntimeContext;

use crate::candidates::StrategyCandidateSpec;
use crate::types::QuicTarget;

use crate::execution::runtime::{
    CandidateRuntimeLauncher, CandidateRuntimeSupervisor, candidate_abort_reason, probe_runtime_transport,
};
use crate::execution::scoring::{
    CandidateExecution, CandidateScore, build_candidate_execution, cancelled_candidate_execution,
    failed_candidate_execution, not_applicable_candidate_execution,
};

use super::collect_quic_samples;

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
    if candidate_abort_reason(cancel).is_some() {
        return cancelled_candidate_execution(spec, CandidateScore::default(), 2);
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(runtime_launcher, spec, runtime_context) {
        Ok(runtime) => {
            let runtime = supervisor.supervise(runtime);
            let generation = runtime.runtime().generation();
            let mut score = CandidateScore::default();
            let complete = collect_quic_samples(runtime.runtime(), spec, targets, probe_seed, cancel, &mut score);
            let terminal_receipt = runtime.shutdown_interruptibly(&|| candidate_abort_reason(cancel).is_some());
            if !complete || candidate_abort_reason(cancel).is_some() {
                return cancelled_candidate_execution(spec, score, 2);
            }
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
        Err(_) if candidate_abort_reason(cancel).is_some() => {
            cancelled_candidate_execution(spec, CandidateScore::default(), 2)
        }
        Err(err) => failed_candidate_execution(spec, targets.len(), 2, err.to_string()),
    }
}
