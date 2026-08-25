//! Per-batch pre-filtering and result merge for round-2 TCP candidate batches.
//!
//! Keeps `tcp.rs` (a hotspot-budgeted file) focused on batch-loop control flow;
//! the per-candidate bookkeeping for one selected batch lives here.

use crate::candidates::StrategyCandidateSpec;
use crate::execution::CandidateExecution;
use crate::types::StrategyProbeProgressLane;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime};
use super::super::support::FamilyFailureTracker;
use super::capability_gating::{TcpCapabilities, candidate_not_applicable};
use super::result_recording::{
    record_executed_candidate, record_hostfake_short_circuit, record_not_applicable_candidate,
};

/// Per-suite context shared by every round-2 batch filter decision.
pub(super) struct BatchFilterContext<'a> {
    pub(super) plan: &'a ExecutionPlan,
    pub(super) phase: &'a str,
    pub(super) short_circuit_hostfake: bool,
    pub(super) hostfake_family_succeeded: bool,
    pub(super) capabilities: TcpCapabilities,
    pub(super) tcp_candidate_total: usize,
    pub(super) domain_target_count: usize,
}

/// Publish "started" progress for every selected candidate and split the batch
/// into skip/not-applicable recordings versus candidates that need execution.
pub(super) fn select_executable_candidates(
    runtime: &mut ExecutionRuntime,
    batch: Vec<(usize, StrategyCandidateSpec)>,
    context: BatchFilterContext<'_>,
) -> Vec<(usize, StrategyCandidateSpec)> {
    let mut to_execute = Vec::new();
    for (candidate_index, spec) in batch {
        tracing::debug!(candidate = spec.id, label = spec.label, "strategy probe: testing TCP candidate");
        runtime.publish_strategy_probe_candidate_started(
            context.plan,
            context.phase,
            StrategyProbeProgressLane::Tcp,
            candidate_index,
            context.tcp_candidate_total,
            spec.id,
            spec.label,
            format!("Testing TCP candidate {}", spec.label),
        );
        if context.short_circuit_hostfake && spec.family == "hostfake" && context.hostfake_family_succeeded {
            record_hostfake_short_circuit(
                runtime,
                context.plan,
                context.phase,
                &spec,
                candidate_index,
                context.tcp_candidate_total,
                context.domain_target_count,
            );
            continue;
        }
        if let Some(not_applicable) = candidate_not_applicable(&spec, context.capabilities) {
            record_not_applicable_candidate(
                runtime,
                context.plan,
                context.phase,
                &spec,
                candidate_index,
                context.tcp_candidate_total,
                not_applicable,
            );
            continue;
        }
        to_execute.push((candidate_index, spec));
    }
    to_execute
}

/// Bookkeeping flags produced while merging one executed batch back into the
/// runtime sequentially.
pub(super) struct BatchMergeOutcome {
    pub(super) any_cancelled: bool,
    pub(super) hostfake_family_succeeded: bool,
}

/// Merge parallel-execution results back into the runtime in candidate order,
/// updating the family failure tracker and the executed tally.
pub(super) fn merge_batch_results(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    exec_results: Vec<(usize, StrategyCandidateSpec, CandidateExecution)>,
    failure_tracker: &mut FamilyFailureTracker<'_>,
    executed_count: &mut usize,
    capabilities: TcpCapabilities,
    tcp_candidate_total: usize,
) -> BatchMergeOutcome {
    let mut outcome = BatchMergeOutcome { any_cancelled: false, hostfake_family_succeeded: false };
    for (candidate_index, spec, execution) in exec_results {
        if execution.cancelled {
            outcome.any_cancelled = true;
            continue;
        }
        let record = record_executed_candidate(
            runtime,
            plan,
            phase,
            &spec,
            candidate_index,
            tcp_candidate_total,
            execution,
            capabilities,
        );
        if record.hostfake_family_succeeded {
            outcome.hostfake_family_succeeded = true;
        }
        *executed_count += 1;
        failure_tracker.record(spec.family, record.failed);
        if failure_tracker.blocked_family().is_some() {
            tracing::debug!(
                candidate = spec.id,
                family = spec.family,
                "strategy probe: candidate skipped, family blocked"
            );
        }
    }
    outcome
}
