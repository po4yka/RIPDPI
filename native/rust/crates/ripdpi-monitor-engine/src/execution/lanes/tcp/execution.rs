use std::sync::atomic::Ordering;
use std::thread;
use std::time::{Duration, Instant};

use crate::candidates::{StrategyCandidateSpec, target_probe_pause_ms};
use crate::execution::runtime::{CandidateRuntimeLease, run_candidate_warmup};
use crate::execution::scoring::{
    CandidateExecution, CandidateScore, build_candidate_execution, cancelled_candidate_execution,
    failed_candidate_execution,
};
use crate::tls::tls_key_log_callback_for_path;
use crate::types::DomainTarget;
use crate::util::stable_probe_hash;

use super::TcpCandidateExecutionContext;
use super::domain_probe::probe_domain_chunk;

const PARALLEL_DOMAIN_BATCH_SIZE: usize = 3;

pub(super) fn execute_started_tcp_candidate(
    runtime: CandidateRuntimeLease<'_>,
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    probe_seed: u64,
    context: TcpCandidateExecutionContext<'_>,
    probe_started: Instant,
) -> CandidateExecution {
    let transport = runtime.runtime().transport();
    let generation = runtime.runtime().generation();
    let key_log = context.keylog_path.map(tls_key_log_callback_for_path);
    run_candidate_warmup(spec, &transport, targets, context.tls_verifier, key_log.as_ref());
    if context.cancel.load(Ordering::Acquire) {
        runtime.shutdown();
        return cancelled_candidate_execution(spec, CandidateScore::default(), 3);
    }
    let mut score = CandidateScore::default();
    let mut ordered_targets = targets.to_vec();
    ordered_targets.sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));

    for (chunk_index, chunk) in ordered_targets.chunks(PARALLEL_DOMAIN_BATCH_SIZE).enumerate() {
        if context.cancel.load(Ordering::Acquire) {
            runtime.shutdown();
            return cancelled_candidate_execution(spec, score, 3);
        }
        if chunk_index > 0 {
            thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &chunk[0].host)));
        }
        let ordinal_base = chunk_index as u64 * PARALLEL_DOMAIN_BATCH_SIZE as u64 * 2;
        let Ok(chunk_results) = probe_domain_chunk(
            chunk,
            runtime.runtime(),
            generation,
            ordinal_base,
            spec,
            context.tls_verifier,
            key_log.as_ref(),
        ) else {
            let terminal_receipt = runtime.shutdown();
            let mut execution =
                failed_candidate_execution(spec, targets.len() * 2, 3, "candidate probe worker panicked".to_string());
            execution.attach_terminal_evidence(generation, &terminal_receipt);
            return execution;
        };
        for samples in chunk_results {
            for sample in samples {
                score.add(sample);
            }
        }
        if context.cancel.load(Ordering::Acquire) {
            runtime.shutdown();
            return cancelled_candidate_execution(spec, score, 3);
        }
    }
    let terminal_receipt = runtime.shutdown();
    metrics::histogram!(
        "ripdpi_strategy_probe_duration_seconds",
        "candidate_id" => spec.id.to_string(),
        "family" => "tcp",
    )
    .record(probe_started.elapsed().as_secs_f64());
    let mut execution = build_candidate_execution(spec, score, 3);
    execution.attach_terminal_evidence(generation, &terminal_receipt);
    execution
}
