use crate::candidates::StrategyCandidateSpec;
use crate::types::{StrategyProbeCandidateSummary, StrategyProbeObservationRole, StrategyProbeRuntimeTerminalStatus};

use super::super::score_state::CandidateScore;
use super::super::{candidate_notes, candidate_proxy_config_json};
use super::execution::{CandidateAttemptExecution, CandidateExecution};
use super::features::{candidate_requires_desync_execution_evidence, candidate_route_features};

pub fn build_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let outcome = if score.is_full_success() {
        "success"
    } else if score.succeeded_targets > 0 && score.quality_score >= quality_floor {
        "partial"
    } else {
        "failed"
    };
    let rationale = format!("{} of {} targets succeeded", score.succeeded_targets, score.total_targets);
    let domain_outcomes = score.domain_outcomes();
    let attempts = score
        .attempts
        .iter()
        .map(|(token, success)| CandidateAttemptExecution {
            token: token.clone(),
            success: *success,
            receipts: Vec::new(),
        })
        .collect();
    let desync_execution_required = candidate_requires_desync_execution_evidence(spec);
    let execution_evidence_complete = !desync_execution_required;
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            emitter_tier: spec.emitter_tier,
            exact_emitter_requires_root: spec.exact_emitter_requires_root,
            emitter_downgraded: false,
            quic_layout_family: spec.quic_layout_family.map(str::to_string),
            outcome: outcome.to_string(),
            rationale,
            succeeded_targets: score.succeeded_targets,
            total_targets: score.total_targets,
            weighted_success_score: score.weighted_success_score,
            total_weight: score.total_weight,
            quality_score: score.quality_score,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: score.average_latency_ms(),
            skipped: false,
            domain_outcomes,
            observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
            active_snapshot_faithful: spec.active_snapshot_faithful,
            desync_execution_required,
            runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
            execution_evidence_complete,
            execution_attempts: Vec::new(),
            route_features: candidate_route_features(spec),
        },
        results: score.results,
        cancelled: false,
        attempts,
        execution_evidence_complete,
    }
}

pub(in crate::execution) fn cancelled_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let mut execution = build_candidate_execution(spec, score, quality_floor);
    execution.cancelled = true;
    execution
}

pub fn failed_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    err: String,
) -> CandidateExecution {
    empty_candidate_execution(spec, total_targets, total_weight_per_target, "failed", err, &[])
}

pub fn not_applicable_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> CandidateExecution {
    empty_candidate_execution(
        spec,
        total_targets,
        total_weight_per_target,
        "not_applicable",
        rationale.to_string(),
        &[rationale],
    )
}

fn empty_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    outcome: &str,
    rationale: String,
    extra_notes: &[&str],
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            emitter_tier: spec.emitter_tier,
            exact_emitter_requires_root: spec.exact_emitter_requires_root,
            emitter_downgraded: false,
            quic_layout_family: spec.quic_layout_family.map(str::to_string),
            outcome: outcome.to_string(),
            rationale,
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, extra_notes),
            average_latency_ms: None,
            skipped: false,
            domain_outcomes: vec![],
            observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
            active_snapshot_faithful: spec.active_snapshot_faithful,
            desync_execution_required: candidate_requires_desync_execution_evidence(spec),
            runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
            execution_evidence_complete: false,
            execution_attempts: Vec::new(),
            route_features: candidate_route_features(spec),
        },
        results: Vec::new(),
        cancelled: false,
        attempts: Vec::new(),
        execution_evidence_complete: false,
    }
}
