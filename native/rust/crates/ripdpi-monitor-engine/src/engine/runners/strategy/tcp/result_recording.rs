use crate::candidates::StrategyCandidateSpec;
use crate::execution::{CandidateExecution, skipped_candidate_summary};
use crate::types::StrategyProbeProgressLane;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};
use super::super::support::{
    annotate_emitter_execution, record_not_applicable_tcp_candidate, strategy_probe_live_progress_with_targets,
};
use super::capability_gating::{NotApplicableReason, TcpCapabilities};

pub(super) struct CandidateRecord {
    pub(super) failed: bool,
    pub(super) hostfake_family_succeeded: bool,
}

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

pub(super) fn record_executed_candidate(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    tcp_candidate_total: usize,
    execution: CandidateExecution,
    capabilities: TcpCapabilities,
) -> CandidateRecord {
    let requires_applied_execution = !spec.config.chains.tcp_steps.is_empty();
    let execution_verified = execution.execution_evidence_complete
        && (!requires_applied_execution
            || execution.summary.succeeded_targets == 0
            || execution.has_applied_success_evidence());
    let mut summary = execution.summary;
    if !execution_verified {
        summary.outcome = "unverified_execution".to_string();
        summary.notes.push("executionEvidence=unverified".to_string());
    }
    annotate_emitter_execution(&mut summary, spec, capabilities.fake_ttl_available, capabilities.ipfrag_caps);
    let hostfake_family_succeeded = summary.family == "hostfake" && summary.succeeded_targets == summary.total_targets;
    let failed = summary.outcome == "failed";
    runtime.record_step(
        plan,
        phase,
        format!("Tested {}", spec.label),
        Some(spec.label.to_string()),
        Some(summary.outcome.clone()),
        Some(strategy_probe_live_progress_with_targets(
            StrategyProbeProgressLane::Tcp,
            candidate_index,
            tcp_candidate_total,
            spec.id,
            spec.label,
            summary.succeeded_targets,
            summary.total_targets,
        )),
        RunnerArtifacts::from_results(
            execution.results,
            "strategy_probe",
            if failed { "warn" } else { "info" },
            format!("Testing TCP candidate {}", spec.label),
        ),
    );
    runtime.strategy.tcp_candidates.push(summary);
    CandidateRecord { failed, hostfake_family_succeeded }
}
