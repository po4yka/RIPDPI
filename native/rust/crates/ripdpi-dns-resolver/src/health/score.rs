use std::time::{Duration, Instant};

use crate::types::ResolverOracleObservation;

use super::{calibration::Weights, oracle_policy};

const INITIAL_SUCCESS_RATE: f64 = 0.5;
const INITIAL_LATENCY_MS: f64 = 200.0;
const LATENCY_SCORE_CAP_MS: f64 = 2000.0;
/// Weight of one new sample. Time only decays old values toward the prior.
const SAMPLE_ALPHA: f64 = 0.3;

#[derive(Debug, Clone)]
pub(super) struct HealthScore {
    pub(super) ewma_success_rate: f64,
    pub(super) ewma_latency_ms: f64,
    pub(super) ewma_oracle_score: f64,
    transport_updated: Instant,
    oracle_updated: Instant,
    pub(super) observation_count: u64,
    pub(super) oracle_observation_count: u64,
    pub(super) oracle_disagreement_streak: u32,
    quarantine_until: Option<Instant>,
}

impl HealthScore {
    pub(super) fn new(now: Instant) -> Self {
        Self {
            ewma_success_rate: INITIAL_SUCCESS_RATE,
            ewma_latency_ms: INITIAL_LATENCY_MS,
            ewma_oracle_score: oracle_policy::INITIAL_ORACLE_SCORE,
            transport_updated: now,
            oracle_updated: now,
            observation_count: 0,
            oracle_observation_count: 0,
            oracle_disagreement_streak: 0,
            quarantine_until: None,
        }
    }

    pub(super) fn update(&mut self, success: bool, latency_ms: u64, half_life: Duration, now: Instant) {
        let (success_rate, latency_ms_decayed) = self.decayed_transport(half_life, now);
        let success_sample = if success { 1.0 } else { 0.0 };
        self.ewma_success_rate = SAMPLE_ALPHA * success_sample + (1.0 - SAMPLE_ALPHA) * success_rate;
        self.ewma_latency_ms = SAMPLE_ALPHA * (latency_ms as f64) + (1.0 - SAMPLE_ALPHA) * latency_ms_decayed;
        self.transport_updated = now;
        self.observation_count += 1;
    }

    pub(super) fn update_oracle(&mut self, observation: ResolverOracleObservation, half_life: Duration, now: Instant) {
        let sample = oracle_policy::oracle_sample(observation);
        self.ewma_oracle_score = SAMPLE_ALPHA * sample + (1.0 - SAMPLE_ALPHA) * self.decayed_oracle(half_life, now);
        self.oracle_updated = now;
        self.oracle_observation_count += 1;

        match observation {
            ResolverOracleObservation::Agreement => {
                self.oracle_disagreement_streak = 0;
                self.quarantine_until = None;
            }
            ResolverOracleObservation::PartialOverlap {
                shared_answers,
                resolver_only_answers,
                oracle_only_answers,
            } => {
                let overlap = oracle_policy::partial_overlap_similarity(
                    shared_answers,
                    resolver_only_answers,
                    oracle_only_answers,
                );
                if oracle_policy::is_quarantinable_partial_overlap(overlap) {
                    self.bump_disagreement_streak(now, half_life);
                } else if oracle_policy::is_recovering_partial_overlap(overlap) {
                    self.oracle_disagreement_streak = self.oracle_disagreement_streak.saturating_sub(1);
                }
            }
            ResolverOracleObservation::Disagreement => {
                self.bump_disagreement_streak(now, half_life);
            }
            ResolverOracleObservation::Poisoned => {
                self.oracle_disagreement_streak =
                    self.oracle_disagreement_streak.max(oracle_policy::poisoned_disagreement_streak_floor());
                self.extend_quarantine(now, oracle_policy::poisoned_quarantine_duration(half_life));
            }
        }
    }

    pub(super) fn is_quarantined(&self, now: Instant) -> bool {
        self.quarantine_until.is_some_and(|until| until > now)
    }

    /// Transport (success rate, latency ms) decayed toward the prior as of `now`; does not mutate.
    pub(super) fn decayed_transport(&self, half_life: Duration, now: Instant) -> (f64, f64) {
        let factor = decay_factor(self.transport_updated, now, half_life);
        (
            decay_toward(self.ewma_success_rate, INITIAL_SUCCESS_RATE, factor),
            decay_toward(self.ewma_latency_ms, INITIAL_LATENCY_MS, factor),
        )
    }

    /// Oracle score decayed toward the prior as of `now`; does not mutate.
    pub(super) fn decayed_oracle(&self, half_life: Duration, now: Instant) -> f64 {
        let factor = decay_factor(self.oracle_updated, now, half_life);
        decay_toward(self.ewma_oracle_score, oracle_policy::INITIAL_ORACLE_SCORE, factor)
    }

    /// Composite score in 0.0..1.0 (higher is better), using values decayed to `now`.
    pub(super) fn composite_score(&self, half_life: Duration, now: Instant) -> f64 {
        self.weighted_score(Weights::default(), half_life, now)
    }

    pub(super) fn features(&self, half_life: Duration, now: Instant) -> [f64; 3] {
        let (success_rate, latency_ms) = self.decayed_transport(half_life, now);
        let oracle_score = self.decayed_oracle(half_life, now);
        let latency_score = 1.0 - (latency_ms / LATENCY_SCORE_CAP_MS).clamp(0.0, 1.0);
        [success_rate, latency_score, oracle_score]
    }

    pub(super) fn weighted_score(&self, weights: Weights, half_life: Duration, now: Instant) -> f64 {
        weights.score(self.features(half_life, now))
    }

    fn bump_disagreement_streak(&mut self, now: Instant, half_life: Duration) {
        self.oracle_disagreement_streak = self.oracle_disagreement_streak.saturating_add(1);
        if oracle_policy::should_quarantine(self.oracle_disagreement_streak) {
            self.extend_quarantine(now, oracle_policy::disagreement_quarantine_duration(half_life));
        }
    }

    fn extend_quarantine(&mut self, now: Instant, duration: Duration) {
        let next_until = now + duration;
        self.quarantine_until = Some(match self.quarantine_until {
            Some(existing) if existing > next_until => existing,
            _ => next_until,
        });
    }
}

fn decay_factor(last_updated: Instant, now: Instant, half_life: Duration) -> f64 {
    let dt = now.saturating_duration_since(last_updated).as_secs_f64();
    let hl = half_life.as_secs_f64().max(f64::EPSILON);
    (-dt / hl).exp().clamp(0.0, 1.0)
}

fn decay_toward(value: f64, prior: f64, factor: f64) -> f64 {
    prior + (value - prior) * factor
}
