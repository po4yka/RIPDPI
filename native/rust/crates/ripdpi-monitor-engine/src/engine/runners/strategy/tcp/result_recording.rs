use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};
use super::super::attempt_recording::record_full_matrix_candidate_attempts;
use super::super::support::{annotate_emitter_execution, strategy_probe_live_progress_with_targets};
use super::capability_gating::{NotApplicableReason, TcpCapabilities};
use crate::candidates::StrategyCandidateSpec;

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
    let summary = crate::execution::skipped_candidate_summary(
        spec,
        domain_target_count * 2,
        6,
        "Earlier hostfake candidate already achieved full success",
    );
    runtime.strategy.tcp_candidates.push(summary.clone());
    runtime.record_skipped_strategy_probe_candidate(
        plan,
        phase,
        crate::types::StrategyProbeProgressLane::Tcp,
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
    super::super::support::record_not_applicable_tcp_candidate(
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
    mut execution: crate::execution::CandidateExecution,
    capabilities: TcpCapabilities,
) -> CandidateRecord {
    record_full_matrix_candidate_attempts(runtime, &mut execution);
    let mut summary = execution.summary;
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
            crate::types::StrategyProbeProgressLane::Tcp,
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
