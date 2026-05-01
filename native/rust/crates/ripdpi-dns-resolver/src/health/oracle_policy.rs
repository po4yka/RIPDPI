use std::time::Duration;

use crate::types::ResolverOracleObservation;

pub(super) const INITIAL_ORACLE_SCORE: f64 = 0.5;

const ORACLE_DISAGREEMENT_QUARANTINE_STREAK: u32 = 2;
const ORACLE_QUARANTINE_MULTIPLIER: f64 = 3.0;
const ORACLE_POISONED_QUARANTINE_MULTIPLIER: f64 = 5.0;
const PARTIAL_OVERLAP_DISAGREEMENT_THRESHOLD: f64 = 0.25;

pub(super) fn oracle_sample(observation: ResolverOracleObservation) -> f64 {
    match observation {
        ResolverOracleObservation::Agreement => 1.0,
        ResolverOracleObservation::PartialOverlap { shared_answers, resolver_only_answers, oracle_only_answers } => {
            partial_overlap_sample(shared_answers, resolver_only_answers, oracle_only_answers)
        }
        ResolverOracleObservation::Disagreement | ResolverOracleObservation::Poisoned => 0.0,
    }
}

pub(super) fn partial_overlap_similarity(
    shared_answers: usize,
    resolver_only_answers: usize,
    oracle_only_answers: usize,
) -> f64 {
    let union = shared_answers + resolver_only_answers + oracle_only_answers;
    if union == 0 {
        return 0.5;
    }
    shared_answers as f64 / union as f64
}

pub(super) fn is_quarantinable_partial_overlap(overlap: f64) -> bool {
    overlap < PARTIAL_OVERLAP_DISAGREEMENT_THRESHOLD
}

pub(super) fn is_recovering_partial_overlap(overlap: f64) -> bool {
    overlap >= 0.5
}

pub(super) fn should_quarantine(disagreement_streak: u32) -> bool {
    disagreement_streak >= ORACLE_DISAGREEMENT_QUARANTINE_STREAK
}

pub(super) fn poisoned_disagreement_streak_floor() -> u32 {
    ORACLE_DISAGREEMENT_QUARANTINE_STREAK
}

pub(super) fn disagreement_quarantine_duration(half_life: Duration) -> Duration {
    scale_duration(half_life, ORACLE_QUARANTINE_MULTIPLIER)
}

pub(super) fn poisoned_quarantine_duration(half_life: Duration) -> Duration {
    scale_duration(half_life, ORACLE_POISONED_QUARANTINE_MULTIPLIER)
}

fn partial_overlap_sample(shared_answers: usize, resolver_only_answers: usize, oracle_only_answers: usize) -> f64 {
    let overlap = partial_overlap_similarity(shared_answers, resolver_only_answers, oracle_only_answers);
    (0.35 + overlap * 0.65).clamp(0.0, 1.0)
}

fn scale_duration(base: Duration, multiplier: f64) -> Duration {
    Duration::from_secs_f64((base.as_secs_f64() * multiplier).max(f64::EPSILON))
}
