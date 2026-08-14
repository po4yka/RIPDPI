use crate::candidates::StrategyCandidateSpec;
use crate::types::{ProbeResult, StrategyProbeCandidateSummary};

use super::score_state::CandidateScore;
use super::{candidate_notes, candidate_proxy_config_json};

#[derive(Debug)]
pub struct CandidateExecution {
    pub summary: StrategyProbeCandidateSummary,
    pub results: Vec<ProbeResult>,
    pub cancelled: bool,
}

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
        },
        results: score.results,
        cancelled: false,
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
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            emitter_tier: spec.emitter_tier,
            exact_emitter_requires_root: spec.exact_emitter_requires_root,
            emitter_downgraded: false,
            quic_layout_family: spec.quic_layout_family.map(str::to_string),
            outcome: "failed".to_string(),
            rationale: err,
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: None,
            skipped: false,
            domain_outcomes: vec![],
        },
        results: Vec::new(),
        cancelled: false,
    }
}

pub fn not_applicable_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
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
            outcome: "not_applicable".to_string(),
            rationale: rationale.to_string(),
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[rationale]),
            average_latency_ms: None,
            skipped: false,
            domain_outcomes: vec![],
        },
        results: Vec::new(),
        cancelled: false,
    }
}

pub fn skipped_candidate_summary(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        emitter_tier: spec.emitter_tier,
        exact_emitter_requires_root: spec.exact_emitter_requires_root,
        emitter_downgraded: false,
        quic_layout_family: spec.quic_layout_family.map(str::to_string),
        outcome: "skipped".to_string(),
        rationale: rationale.to_string(),
        succeeded_targets: 0,
        total_targets,
        weighted_success_score: 0,
        total_weight: total_targets * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[rationale]),
        average_latency_ms: None,
        skipped: true,
        domain_outcomes: vec![],
    }
}

pub fn eliminated_candidate_summary(
    spec: &StrategyCandidateSpec,
    qualifier_succeeded: usize,
    qualifier_total: usize,
    total_weight_per_target: usize,
) -> StrategyProbeCandidateSummary {
    let rationale = format!("Eliminated in qualifier: {qualifier_succeeded}/{qualifier_total} succeeded");

    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        emitter_tier: spec.emitter_tier,
        exact_emitter_requires_root: spec.exact_emitter_requires_root,
        emitter_downgraded: false,
        quic_layout_family: spec.quic_layout_family.map(str::to_string),
        outcome: "eliminated".to_string(),
        rationale: rationale.clone(),
        succeeded_targets: qualifier_succeeded,
        total_targets: qualifier_total,
        weighted_success_score: 0,
        total_weight: qualifier_total * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[&rationale]),
        average_latency_ms: None,
        skipped: false,
        domain_outcomes: vec![],
    }
}
