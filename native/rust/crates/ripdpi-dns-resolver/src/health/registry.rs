use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::types::{ResolverNetworkScope, ResolverOracleObservation};

use super::score::HealthScore;
use super::snapshot::HealthScoreSnapshot;

#[derive(Default)]
struct ScopedHealthState {
    endpoint_scores: HashMap<String, HealthScore>,
    bootstrap_scores: HashMap<IpAddr, HealthScore>,
}

struct HealthRegistryInner {
    scopes: HashMap<ResolverNetworkScope, ScopedHealthState>,
    half_life: Duration,
    clock: fn() -> Instant,
}

impl HealthRegistryInner {
    fn scoped_state_mut(&mut self, scope: &ResolverNetworkScope) -> &mut ScopedHealthState {
        self.scopes.entry(scope.clone()).or_default()
    }

    fn endpoint_score_or_insert(&mut self, scope: &ResolverNetworkScope, label: &str) -> &mut HealthScore {
        let clock = self.clock;
        self.scoped_state_mut(scope)
            .endpoint_scores
            .entry(label.to_string())
            .or_insert_with(|| HealthScore::new(clock()))
    }

    fn bootstrap_score_or_insert(&mut self, scope: &ResolverNetworkScope, ip: IpAddr) -> &mut HealthScore {
        let clock = self.clock;
        self.scoped_state_mut(scope).bootstrap_scores.entry(ip).or_insert_with(|| HealthScore::new(clock()))
    }
}

/// Thread-safe EWMA health registry for encrypted DNS endpoints and bootstrap IPs.
///
/// Scores decay toward a neutral prior (0.5 success rate, 200ms latency, 0.5
/// oracle trust) when no observations are recorded, with a configurable
/// half-life. Health memory is partitioned by an opaque network scope token.
#[derive(Clone)]
pub struct HealthRegistry {
    inner: Arc<Mutex<HealthRegistryInner>>,
}

impl HealthRegistry {
    pub fn new(half_life: Duration) -> Self {
        Self::with_clock(half_life, Instant::now)
    }

    pub(crate) fn with_clock(half_life: Duration, clock: fn() -> Instant) -> Self {
        Self { inner: Arc::new(Mutex::new(HealthRegistryInner { scopes: HashMap::new(), half_life, clock })) }
    }

    /// Records an SNI-blocked outcome for a named endpoint.
    /// Uses elevated latency penalty (4000ms) to deprioritize blocked providers faster.
    pub fn record_sni_blocked(&self, label: &str) {
        self.record_sni_blocked_in_scope(&ResolverNetworkScope::global(), label);
    }

    pub fn record_sni_blocked_in_scope(&self, scope: &ResolverNetworkScope, label: &str) {
        self.record_endpoint_outcome_in_scope(scope, label, false, 4000);
    }

    /// Records the outcome of an exchange with a named endpoint.
    pub fn record_endpoint_outcome(&self, label: &str, success: bool, latency_ms: u64) {
        self.record_endpoint_outcome_in_scope(&ResolverNetworkScope::global(), label, success, latency_ms);
    }

    pub fn record_endpoint_outcome_in_scope(
        &self,
        scope: &ResolverNetworkScope,
        label: &str,
        success: bool,
        latency_ms: u64,
    ) {
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner.endpoint_score_or_insert(scope, label).update(success, latency_ms, half_life, now);
        }
    }

    /// Records the outcome of a TCP connect attempt to a bootstrap IP.
    pub fn record_bootstrap_outcome(&self, ip: IpAddr, success: bool, latency_ms: u64) {
        self.record_bootstrap_outcome_in_scope(&ResolverNetworkScope::global(), ip, success, latency_ms);
    }

    pub fn record_bootstrap_outcome_in_scope(
        &self,
        scope: &ResolverNetworkScope,
        ip: IpAddr,
        success: bool,
        latency_ms: u64,
    ) {
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner.bootstrap_score_or_insert(scope, ip).update(success, latency_ms, half_life, now);
        }
    }

    /// Records an oracle trust observation for a named endpoint.
    pub fn record_oracle_observation(&self, label: &str, observation: ResolverOracleObservation) {
        self.record_oracle_observation_in_scope(&ResolverNetworkScope::global(), label, observation);
    }

    pub fn record_oracle_observation_in_scope(
        &self,
        scope: &ResolverNetworkScope,
        label: &str,
        observation: ResolverOracleObservation,
    ) {
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner.endpoint_score_or_insert(scope, label).update_oracle(observation, half_life, now);
        }
    }

    /// Returns the indices of `labels` sorted by composite health score (best first).
    pub fn rank_indices(&self, labels: &[&str]) -> Vec<usize> {
        self.rank_indices_in_scope(&ResolverNetworkScope::global(), labels)
    }

    pub fn rank_indices_in_scope(&self, scope: &ResolverNetworkScope, labels: &[&str]) -> Vec<usize> {
        match self.inner.lock() {
            Ok(mut inner) => {
                let now = (inner.clock)();
                let mut scored: Vec<(usize, bool, f64)> = labels
                    .iter()
                    .enumerate()
                    .map(|(i, &label)| {
                        let score = inner.endpoint_score_or_insert(scope, label);
                        (i, score.is_quarantined(now), score.composite_score())
                    })
                    .collect();
                scored.sort_by(|a, b| {
                    a.1.cmp(&b.1).then_with(|| b.2.partial_cmp(&a.2).unwrap_or(std::cmp::Ordering::Equal))
                });
                scored.into_iter().map(|(i, _, _)| i).collect()
            }
            _ => (0..labels.len()).collect(),
        }
    }

    /// Returns `ips` reordered by bootstrap health score (healthiest first).
    pub fn rank_bootstrap_ips(&self, ips: &[IpAddr]) -> Vec<IpAddr> {
        self.rank_bootstrap_ips_in_scope(&ResolverNetworkScope::global(), ips)
    }

    pub fn rank_bootstrap_ips_in_scope(&self, scope: &ResolverNetworkScope, ips: &[IpAddr]) -> Vec<IpAddr> {
        match self.inner.lock() {
            Ok(mut inner) => {
                let mut scored: Vec<(IpAddr, f64)> = ips
                    .iter()
                    .map(|&ip| {
                        let score = inner.bootstrap_score_or_insert(scope, ip).composite_score();
                        (ip, score)
                    })
                    .collect();
                scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
                scored.into_iter().map(|(ip, _)| ip).collect()
            }
            _ => ips.to_vec(),
        }
    }

    /// Returns the number of transport observations recorded for a named endpoint.
    pub fn observation_count(&self, label: &str) -> u64 {
        self.observation_count_in_scope(&ResolverNetworkScope::global(), label)
    }

    pub fn observation_count_in_scope(&self, scope: &ResolverNetworkScope, label: &str) -> u64 {
        self.inner
            .lock()
            .ok()
            .and_then(|inner| {
                inner.scopes.get(scope).and_then(|state| state.endpoint_scores.get(label).map(|s| s.observation_count))
            })
            .unwrap_or(0)
    }

    /// Returns a point-in-time snapshot of the health score for a named endpoint.
    pub fn snapshot(&self, label: &str) -> Option<HealthScoreSnapshot> {
        self.snapshot_in_scope(&ResolverNetworkScope::global(), label)
    }

    pub fn snapshot_in_scope(&self, scope: &ResolverNetworkScope, label: &str) -> Option<HealthScoreSnapshot> {
        self.inner.lock().ok().and_then(|inner| {
            let now = (inner.clock)();
            inner.scopes.get(scope).and_then(|state| {
                state.endpoint_scores.get(label).map(|score| HealthScoreSnapshot {
                    ewma_success_rate: score.ewma_success_rate,
                    ewma_latency_ms: score.ewma_latency_ms,
                    ewma_oracle_score: score.ewma_oracle_score,
                    observation_count: score.observation_count,
                    oracle_observation_count: score.oracle_observation_count,
                    oracle_disagreement_streak: score.oracle_disagreement_streak,
                    quarantined: score.is_quarantined(now),
                })
            })
        })
    }
}

impl std::fmt::Debug for HealthRegistry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HealthRegistry").finish_non_exhaustive()
    }
}
