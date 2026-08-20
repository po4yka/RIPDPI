use crate::candidates::StrategyCandidateSpec;
mod executed;

use crate::execution::skipped_candidate_summary;
use crate::types::StrategyProbeProgressLane;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime};
use super::super::support::record_not_applicable_tcp_candidate;
use super::capability_gating::NotApplicableReason;

pub(super) use executed::record_executed_candidate;

pub(super) fn record_hostfake_short_circuit(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    tcp_candidate_total: usize,
    domain_target_count: usize,
) {
    let summary = skipped_candidate_summary(
        spec,
        domain_target_count * 2,
        6,
        "Earlier hostfake candidate already achieved full success",
    );
    runtime.strategy.tcp_candidates.push(summary.clone());
    runtime.record_skipped_strategy_probe_candidate(
        plan,
        phase,
        StrategyProbeProgressLane::Tcp,
        candidate_index,
        tcp_candidate_total,
        &summary.id,
        &summary.label,
        Some(summary.outcome.clone()),
        format!("Skipped {}", summary.label),
    );
}

pub(super) fn record_not_applicable_candidate(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    tcp_candidate_total: usize,
    not_applicable: NotApplicableReason,
) {
    tracing::debug!(candidate = spec.id, reason = not_applicable.reason, "strategy probe: candidate not_applicable");
    record_not_applicable_tcp_candidate(
        runtime,
        plan,
        phase,
        spec,
        candidate_index,
        tcp_candidate_total,
        not_applicable.reason,
        not_applicable.suffix,
    );
}
