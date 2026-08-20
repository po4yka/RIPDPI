use crate::candidates::StrategyCandidateSpec;
use crate::execution::CandidateExecution;
use crate::types::StrategyProbeProgressLane;

use super::super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};
use super::super::super::support::{annotate_emitter_execution, strategy_probe_live_progress_with_targets};
use super::super::capability_gating::TcpCapabilities;

pub(in crate::engine::runners::strategy::tcp) struct CandidateRecord {
    pub(in crate::engine::runners::strategy::tcp) failed: bool,
    pub(in crate::engine::runners::strategy::tcp) hostfake_family_succeeded: bool,
}

pub(in crate::engine::runners::strategy::tcp) fn record_executed_candidate(
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
