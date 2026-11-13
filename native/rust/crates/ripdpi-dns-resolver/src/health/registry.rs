use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::types::{ResolverNetworkScope, ResolverOracleObservation};

use super::calibration::Calibration;
use super::score::HealthScore;
use super::snapshot::HealthScoreSnapshot;

/// Maximum number of network scopes kept; the least recently written scope is evicted.
const MAX_SCOPES: usize = 32;

struct ScopedHealthState {
    endpoint_scores: HashMap<String, HealthScore>,
    /// Keyed by (is UDP, address): DoT (TCP) and DoQ (UDP) on one IP and port are separate paths.
    bootstrap_scores: HashMap<(bool, SocketAddr), HealthScore>,
    last_write: Instant,
    calibration: Calibration,
}

struct HealthRegistryInner {
    scopes: HashMap<ResolverNetworkScope, ScopedHealthState>,
    half_life: Duration,
    clock: fn() -> Instant,
    next_incarnation: u64,
}

impl HealthRegistryInner {
    fn scoped_state_mut(&mut self, scope: &ResolverNetworkScope, now: Instant) -> &mut ScopedHealthState {
        if !self.scopes.contains_key(scope) && self.scopes.len() >= MAX_SCOPES {
            let oldest = self.scopes.iter().min_by_key(|(_, state)| state.last_write).map(|(key, _)| key.clone());
            if let Some(oldest) = oldest {
                self.scopes.remove(&oldest);
            }
        }
        if !self.scopes.contains_key(scope) {
            self.next_incarnation = self.next_incarnation.wrapping_add(1);
        }
        let incarnation = self.next_incarnation;
        let state = self.scopes.entry(scope.clone()).or_insert_with(|| ScopedHealthState {
            endpoint_scores: HashMap::new(),
            bootstrap_scores: HashMap::new(),
            last_write: now,
            calibration: Calibration::new(now, incarnation),
        });
        state.last_write = now;
        state
    }

    fn endpoint_score_or_insert(
        &mut self,
        scope: &ResolverNetworkScope,
        label: &str,
        now: Instant,
    ) -> &mut HealthScore {
        self.scoped_state_mut(scope, now)
            .endpoint_scores
            .entry(label.to_string())
            .or_insert_with(|| HealthScore::new(now))
    }

    fn bootstrap_score_or_insert(
        &mut self,
        scope: &ResolverNetworkScope,
        key: (bool, SocketAddr),
        now: Instant,
    ) -> &mut HealthScore {
        self.scoped_state_mut(scope, now).bootstrap_scores.entry(key).or_insert_with(|| HealthScore::new(now))
    }

    fn endpoint_score(&self, scope: &ResolverNetworkScope, label: &str) -> Option<&HealthScore> {
        self.scopes.get(scope).and_then(|state| state.endpoint_scores.get(label))
    }
}

/// Thread-safe EWMA health registry for encrypted DNS endpoints and bootstrap addresses.
///
/// Each sample moves a score by a fixed weight. Reads see stored values decayed
/// toward a neutral prior (0.5 success rate, 200ms latency, 0.5 oracle trust)
/// by the time since the last observation, with a configurable half-life.
/// Health memory is partitioned by an opaque network scope token; at most
/// `MAX_SCOPES` scopes are kept.
#[derive(Clone)]
pub struct HealthRegistry {
    inner: Arc<Mutex<HealthRegistryInner>>,
}

impl HealthRegistry {
    pub fn new(half_life: Duration) -> Self {
        Self::with_clock(half_life, Instant::now)
    }

    pub(crate) fn with_clock(half_life: Duration, clock: fn() -> Instant) -> Self {
        Self {
            inner: Arc::new(Mutex::new(HealthRegistryInner {
                scopes: HashMap::new(),
                half_life,
                clock,
                next_incarnation: 0,
            })),
        }
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
        let batch = if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            let state = inner.scoped_state_mut(scope, now);
            let score = state.endpoint_scores.entry(label.to_string()).or_insert_with(|| HealthScore::new(now));
            let features = score.features(half_life, now);
            score.update(success, latency_ms, half_life, now);
            state.calibration.record(features, success, latency_ms, now)
        } else {
            None
        };
        // Fit stored observations without holding the health-registry mutex.
        if let Some(batch) = batch
            && let Some(candidate) = batch.fit()
            && let Ok(mut inner) = self.inner.lock()
            && let Some(state) = inner.scopes.get_mut(scope)
        {
            state.calibration.apply(&batch, candidate);
        }
    }

    /// Records the outcome of a connect attempt to a bootstrap IP and port (`udp` for QUIC, else TCP).
    pub fn record_bootstrap_outcome(&self, ip: IpAddr, port: u16, udp: bool, success: bool, latency_ms: u64) {
        self.record_bootstrap_outcome_in_scope(&ResolverNetworkScope::global(), ip, port, udp, success, latency_ms);
    }

    pub fn record_bootstrap_outcome_in_scope(
        &self,
        scope: &ResolverNetworkScope,
        ip: IpAddr,
        port: u16,
        udp: bool,
        success: bool,
        latency_ms: u64,
    ) {
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner
                .bootstrap_score_or_insert(scope, (udp, SocketAddr::new(ip, port)), now)
                .update(success, latency_ms, half_life, now);
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
            inner.endpoint_score_or_insert(scope, label, now).update_oracle(observation, half_life, now);
        }
    }

    /// Returns the indices of `labels` sorted by composite health score (best first).
    pub fn rank_indices(&self, labels: &[&str]) -> Vec<usize> {
        self.rank_indices_in_scope(&ResolverNetworkScope::global(), labels)
    }

    /// Ranks without inserting entries: unknown labels score as a fresh neutral endpoint.
    pub fn rank_indices_in_scope(&self, scope: &ResolverNetworkScope, labels: &[&str]) -> Vec<usize> {
        match self.inner.lock() {
            Ok(inner) => {
                let now = (inner.clock)();
                let half_life = inner.half_life;
                let weights = inner.scopes.get(scope).map_or_default(|state| state.calibration.weights(now));
                let mut scored: Vec<(usize, bool, f64)> = labels
                    .iter()
                    .enumerate()
                    .map(|(i, &label)| match inner.endpoint_score(scope, label) {
                        Some(score) => (i, score.is_quarantined(now), score.weighted_score(weights, half_life, now)),
                        None => (i, false, HealthScore::new(now).weighted_score(weights, half_life, now)),
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

    /// Returns `true` while the named endpoint is quarantined by oracle observations.
    pub fn is_quarantined_in_scope(&self, scope: &ResolverNetworkScope, label: &str) -> bool {
        self.inner.lock().ok().is_some_and(|inner| {
            let now = (inner.clock)();
            inner.endpoint_score(scope, label).is_some_and(|score| score.is_quarantined(now))
        })
    }

    /// Returns `ips` (all on `port`, `udp` for QUIC) reordered by bootstrap health score (healthiest first).
    pub fn rank_bootstrap_ips(&self, ips: &[IpAddr], port: u16, udp: bool) -> Vec<IpAddr> {
        self.rank_bootstrap_ips_in_scope(&ResolverNetworkScope::global(), ips, port, udp)
    }

    /// Ranks without inserting entries: unknown addresses score as a fresh neutral one.
    pub fn rank_bootstrap_ips_in_scope(
        &self,
        scope: &ResolverNetworkScope,
        ips: &[IpAddr],
        port: u16,
        udp: bool,
    ) -> Vec<IpAddr> {
        match self.inner.lock() {
            Ok(inner) => {
                let now = (inner.clock)();
                let half_life = inner.half_life;
                let state = inner.scopes.get(scope);
                let mut scored: Vec<(IpAddr, f64)> = ips
                    .iter()
                    .map(|&ip| {
                        let score = match state.and_then(|s| s.bootstrap_scores.get(&(udp, SocketAddr::new(ip, port))))
                        {
                            Some(score) => score.composite_score(half_life, now),
                            None => HealthScore::new(now).composite_score(half_life, now),
                        };
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
            .and_then(|inner| inner.endpoint_score(scope, label).map(|s| s.observation_count))
            .unwrap_or(0)
    }

    /// Returns a point-in-time snapshot of the health score for a named endpoint,
    /// decayed toward the prior as of now.
    pub fn snapshot(&self, label: &str) -> Option<HealthScoreSnapshot> {
        self.snapshot_in_scope(&ResolverNetworkScope::global(), label)
    }

    pub fn snapshot_in_scope(&self, scope: &ResolverNetworkScope, label: &str) -> Option<HealthScoreSnapshot> {
        self.inner.lock().ok().and_then(|inner| {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner.endpoint_score(scope, label).map(|score| {
                let (success_rate, latency_ms) = score.decayed_transport(half_life, now);
                HealthScoreSnapshot {
                    ewma_success_rate: success_rate,
                    ewma_latency_ms: latency_ms,
                    ewma_oracle_score: score.decayed_oracle(half_life, now),
                    observation_count: score.observation_count,
                    oracle_observation_count: score.oracle_observation_count,
                    oracle_disagreement_streak: score.oracle_disagreement_streak,
                    quarantined: score.is_quarantined(now),
                }
            })
        })
    }

    #[cfg(test)]
    pub(crate) fn scope_count(&self) -> usize {
        self.inner.lock().map_or(0, |inner| inner.scopes.len())
    }
}

impl std::fmt::Debug for HealthRegistry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HealthRegistry").finish_non_exhaustive()
    }
}

#[cfg(test)]
mod calibration_tests;
