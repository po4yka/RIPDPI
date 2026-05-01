use std::time::{Duration, Instant};

use crate::types::ResolverOracleObservation;

use super::oracle_policy;

const INITIAL_SUCCESS_RATE: f64 = 0.5;
const INITIAL_LATENCY_MS: f64 = 200.0;
const LATENCY_SCORE_CAP_MS: f64 = 2000.0;

#[derive(Debug, Clone)]
pub(super) struct HealthScore {
    pub(super) ewma_success_rate: f64,
    pub(super) ewma_latency_ms: f64,
    pub(super) ewma_oracle_score: f64,
    last_updated: Instant,
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
            last_updated: now,
            observation_count: 0,
            oracle_observation_count: 0,
            oracle_disagreement_streak: 0,
            quarantine_until: None,
        }
    }

    pub(super) fn update(&mut self, success: bool, latency_ms: u64, half_life: Duration, now: Instant) {
        let alpha = ewma_alpha(self.last_updated, now, half_life);
        let success_sample = if success { 1.0 } else { 0.0 };
        self.ewma_success_rate = alpha * success_sample + (1.0 - alpha) * self.ewma_success_rate;
        self.ewma_latency_ms = alpha * (latency_ms as f64) + (1.0 - alpha) * self.ewma_latency_ms;
        self.last_updated = now;
        self.observation_count += 1;
    }

    pub(super) fn update_oracle(&mut self, observation: ResolverOracleObservation, half_life: Duration, now: Instant) {
        let alpha = ewma_alpha(self.last_updated, now, half_life);
        let sample = oracle_policy::oracle_sample(observation);
        self.ewma_oracle_score = alpha * sample + (1.0 - alpha) * self.ewma_oracle_score;
        self.last_updated = now;
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

    /// Composite score in 0.0..1.0 (higher is better).
    pub(super) fn composite_score(&self) -> f64 {
        let latency_score = 1.0 - (self.ewma_latency_ms / LATENCY_SCORE_CAP_MS).clamp(0.0, 1.0);
        self.ewma_success_rate * 0.55 + latency_score * 0.25 + self.ewma_oracle_score * 0.20
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

fn ewma_alpha(last_updated: Instant, now: Instant, half_life: Duration) -> f64 {
    let dt = now.saturating_duration_since(last_updated).as_secs_f64();
    let hl = half_life.as_secs_f64().max(f64::EPSILON);
    (1.0_f64 - (-dt / hl).exp()).clamp(0.0, 1.0)
}
