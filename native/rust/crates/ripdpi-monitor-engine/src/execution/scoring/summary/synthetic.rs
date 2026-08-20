use crate::candidates::StrategyCandidateSpec;
use crate::types::{StrategyProbeCandidateSummary, StrategyProbeObservationRole, StrategyProbeRuntimeTerminalStatus};

use super::super::{candidate_notes, candidate_proxy_config_json};
use super::features::{candidate_requires_desync_execution_evidence, candidate_route_features};

pub fn skipped_candidate_summary(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> StrategyProbeCandidateSummary {
    synthetic_summary(spec, total_targets, total_weight_per_target, "skipped", rationale.to_string(), true, 0)
}

pub fn eliminated_candidate_summary(
    spec: &StrategyCandidateSpec,
    qualifier_succeeded: usize,
    qualifier_total: usize,
    total_weight_per_target: usize,
) -> StrategyProbeCandidateSummary {
    let rationale = format!("Eliminated in qualifier: {qualifier_succeeded}/{qualifier_total} succeeded");
    synthetic_summary(
        spec,
        qualifier_total,
        total_weight_per_target,
        "eliminated",
        rationale,
        false,
        qualifier_succeeded,
    )
}

fn synthetic_summary(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    outcome: &str,
    rationale: String,
    skipped: bool,
    succeeded_targets: usize,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        emitter_tier: spec.emitter_tier,
        exact_emitter_requires_root: spec.exact_emitter_requires_root,
        emitter_downgraded: false,
        quic_layout_family: spec.quic_layout_family.map(str::to_string),
        outcome: outcome.to_string(),
        rationale: rationale.clone(),
        succeeded_targets,
        total_targets,
        weighted_success_score: 0,
        total_weight: total_targets * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[&rationale]),
        average_latency_ms: None,
        skipped,
        domain_outcomes: vec![],
        observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: spec.active_snapshot_faithful,
        desync_execution_required: candidate_requires_desync_execution_evidence(spec),
        runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: false,
        execution_attempts: Vec::new(),
        route_features: candidate_route_features(spec),
    }
}
