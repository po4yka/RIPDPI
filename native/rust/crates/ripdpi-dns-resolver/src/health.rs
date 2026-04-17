use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::types::{ResolverNetworkScope, ResolverOracleObservation};

const INITIAL_SUCCESS_RATE: f64 = 0.5;
const INITIAL_LATENCY_MS: f64 = 200.0;
const INITIAL_ORACLE_SCORE: f64 = 0.5;
const LATENCY_SCORE_CAP_MS: f64 = 2000.0;
const ORACLE_DISAGREEMENT_QUARANTINE_STREAK: u32 = 2;
const ORACLE_QUARANTINE_MULTIPLIER: f64 = 3.0;
const ORACLE_POISONED_QUARANTINE_MULTIPLIER: f64 = 5.0;
const PARTIAL_OVERLAP_DISAGREEMENT_THRESHOLD: f64 = 0.25;

#[derive(Debug, Clone)]
pub struct HealthScoreSnapshot {
    pub ewma_success_rate: f64,
    pub ewma_latency_ms: f64,
    pub ewma_oracle_score: f64,
    pub observation_count: u64,
    pub oracle_observation_count: u64,
    pub oracle_disagreement_streak: u32,
    pub quarantined: bool,
}

#[derive(Debug, Clone)]
struct HealthScore {
    ewma_success_rate: f64,
    ewma_latency_ms: f64,
    ewma_oracle_score: f64,
    last_updated: Instant,
    observation_count: u64,
    oracle_observation_count: u64,
    oracle_disagreement_streak: u32,
    quarantine_until: Option<Instant>,
}

impl HealthScore {
    fn new(now: Instant) -> Self {
        Self {
            ewma_success_rate: INITIAL_SUCCESS_RATE,
            ewma_latency_ms: INITIAL_LATENCY_MS,
            ewma_oracle_score: INITIAL_ORACLE_SCORE,
            last_updated: now,
            observation_count: 0,
            oracle_observation_count: 0,
            oracle_disagreement_streak: 0,
            quarantine_until: None,
        }
    }

    fn update(&mut self, success: bool, latency_ms: u64, half_life: Duration, now: Instant) {
        let alpha = ewma_alpha(self.last_updated, now, half_life);
        let success_sample = if success { 1.0 } else { 0.0 };
        self.ewma_success_rate = alpha * success_sample + (1.0 - alpha) * self.ewma_success_rate;
        self.ewma_latency_ms = alpha * (latency_ms as f64) + (1.0 - alpha) * self.ewma_latency_ms;
        self.last_updated = now;
        self.observation_count += 1;
    }

    fn update_oracle(&mut self, observation: ResolverOracleObservation, half_life: Duration, now: Instant) {
        let alpha = ewma_alpha(self.last_updated, now, half_life);
        let sample = match observation {
            ResolverOracleObservation::Agreement => 1.0,
            ResolverOracleObservation::PartialOverlap {
                shared_answers,
                resolver_only_answers,
                oracle_only_answers,
            } => partial_overlap_sample(shared_answers, resolver_only_answers, oracle_only_answers),
            ResolverOracleObservation::Disagreement | ResolverOracleObservation::Poisoned => 0.0,
        };
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
                let overlap = partial_overlap_similarity(shared_answers, resolver_only_answers, oracle_only_answers);
                if overlap < PARTIAL_OVERLAP_DISAGREEMENT_THRESHOLD {
                    self.bump_disagreement_streak(now, half_life, ORACLE_QUARANTINE_MULTIPLIER);
                } else if overlap >= 0.5 {
                    self.oracle_disagreement_streak = self.oracle_disagreement_streak.saturating_sub(1);
                }
            }
            ResolverOracleObservation::Disagreement => {
                self.bump_disagreement_streak(now, half_life, ORACLE_QUARANTINE_MULTIPLIER);
            }
            ResolverOracleObservation::Poisoned => {
                self.oracle_disagreement_streak =
                    self.oracle_disagreement_streak.max(ORACLE_DISAGREEMENT_QUARANTINE_STREAK);
                self.extend_quarantine(now, scale_duration(half_life, ORACLE_POISONED_QUARANTINE_MULTIPLIER));
            }
        }
    }

    fn bump_disagreement_streak(&mut self, now: Instant, half_life: Duration, multiplier: f64) {
        self.oracle_disagreement_streak = self.oracle_disagreement_streak.saturating_add(1);
        if self.oracle_disagreement_streak >= ORACLE_DISAGREEMENT_QUARANTINE_STREAK {
            self.extend_quarantine(now, scale_duration(half_life, multiplier));
        }
    }

    fn extend_quarantine(&mut self, now: Instant, duration: Duration) {
        let next_until = now + duration;
        self.quarantine_until = Some(match self.quarantine_until {
            Some(existing) if existing > next_until => existing,
            _ => next_until,
        });
    }

    fn is_quarantined(&self, now: Instant) -> bool {
        self.quarantine_until.is_some_and(|until| until > now)
    }

    /// Composite score in 0.0..1.0 (higher is better).
    fn composite_score(&self) -> f64 {
        let latency_score = 1.0 - (self.ewma_latency_ms / LATENCY_SCORE_CAP_MS).clamp(0.0, 1.0);
        self.ewma_success_rate * 0.55 + latency_score * 0.25 + self.ewma_oracle_score * 0.20
    }
}

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
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let mut scored: Vec<(usize, bool, f64)> = labels
                .iter()
                .enumerate()
                .map(|(i, &label)| {
                    let score = inner.endpoint_score_or_insert(scope, label);
                    (i, score.is_quarantined(now), score.composite_score())
                })
                .collect();
            scored
                .sort_by(|a, b| a.1.cmp(&b.1).then_with(|| b.2.partial_cmp(&a.2).unwrap_or(std::cmp::Ordering::Equal)));
            scored.into_iter().map(|(i, _, _)| i).collect()
        } else {
            (0..labels.len()).collect()
        }
    }

    /// Returns `ips` reordered by bootstrap health score (healthiest first).
    pub fn rank_bootstrap_ips(&self, ips: &[IpAddr]) -> Vec<IpAddr> {
        self.rank_bootstrap_ips_in_scope(&ResolverNetworkScope::global(), ips)
    }

    pub fn rank_bootstrap_ips_in_scope(&self, scope: &ResolverNetworkScope, ips: &[IpAddr]) -> Vec<IpAddr> {
        if let Ok(mut inner) = self.inner.lock() {
            let mut scored: Vec<(IpAddr, f64)> = ips
                .iter()
                .map(|&ip| {
                    let score = inner.bootstrap_score_or_insert(scope, ip).composite_score();
                    (ip, score)
                })
                .collect();
            scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
            scored.into_iter().map(|(ip, _)| ip).collect()
        } else {
            ips.to_vec()
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

fn ewma_alpha(last_updated: Instant, now: Instant, half_life: Duration) -> f64 {
    let dt = now.saturating_duration_since(last_updated).as_secs_f64();
    let hl = half_life.as_secs_f64().max(f64::EPSILON);
    (1.0_f64 - (-dt / hl).exp()).clamp(0.0, 1.0)
}

fn scale_duration(base: Duration, multiplier: f64) -> Duration {
    Duration::from_secs_f64((base.as_secs_f64() * multiplier).max(f64::EPSILON))
}

fn partial_overlap_sample(shared_answers: usize, resolver_only_answers: usize, oracle_only_answers: usize) -> f64 {
    let overlap = partial_overlap_similarity(shared_answers, resolver_only_answers, oracle_only_answers);
    (0.35 + overlap * 0.65).clamp(0.0, 1.0)
}

fn partial_overlap_similarity(shared_answers: usize, resolver_only_answers: usize, oracle_only_answers: usize) -> f64 {
    let union = shared_answers + resolver_only_answers + oracle_only_answers;
    if union == 0 {
        return 0.5;
    }
    shared_answers as f64 / union as f64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::net::{IpAddr, Ipv4Addr};
    use std::sync::Arc;

    // A fake clock that advances by one half-life (1 second) per call, keeping each
    // EWMA update in a fresh time window (alpha ≈ 0.632 per observation).
    thread_local! {
        static FAKE_CLOCK_MILLIS: Cell<u64> = const { Cell::new(0) };
    }

    fn advancing_fake_clock() -> Instant {
        FAKE_CLOCK_MILLIS.with(|c| {
            let ms = c.get();
            c.set(ms + 1_000);
            Instant::now() + Duration::from_millis(ms)
        })
    }

    #[test]
    fn ewma_converges_toward_full_success_after_repeated_successes() {
        FAKE_CLOCK_MILLIS.with(|c| c.set(0));
        let reg = HealthRegistry::with_clock(Duration::from_secs(1), advancing_fake_clock);
        for _ in 0..20 {
            reg.record_endpoint_outcome("ep", true, 10);
        }
        let snap = reg.snapshot("ep").unwrap();
        assert!(snap.ewma_success_rate > 0.95, "expected success_rate > 0.95, got {}", snap.ewma_success_rate);
    }

    #[test]
    fn ewma_converges_toward_zero_after_repeated_failures() {
        FAKE_CLOCK_MILLIS.with(|c| c.set(0));
        let reg = HealthRegistry::with_clock(Duration::from_secs(1), advancing_fake_clock);
        for _ in 0..20 {
            reg.record_endpoint_outcome("ep", false, 500);
        }
        let snap = reg.snapshot("ep").unwrap();
        assert!(snap.ewma_success_rate < 0.05, "expected success_rate < 0.05, got {}", snap.ewma_success_rate);
    }

    #[test]
    fn ranking_places_healthy_endpoint_before_unhealthy() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        for _ in 0..30 {
            reg.record_endpoint_outcome("good", true, 30);
        }
        for _ in 0..30 {
            reg.record_endpoint_outcome("bad", false, 3000);
        }
        let ranked = reg.rank_indices(&["bad", "good"]);
        assert_eq!(ranked[0], 1, "good (index 1) should be ranked first");
    }

    #[test]
    fn bootstrap_ranking_places_healthy_ip_first() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        let good = IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8));
        let bad = IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1));
        for _ in 0..30 {
            reg.record_bootstrap_outcome(good, true, 20);
        }
        for _ in 0..30 {
            reg.record_bootstrap_outcome(bad, false, 4000);
        }
        let ranked = reg.rank_bootstrap_ips(&[bad, good]);
        assert_eq!(ranked[0], good, "good IP should be ranked first");
    }

    #[test]
    fn snapshot_returns_none_for_unknown_label() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        assert!(reg.snapshot("unknown").is_none());
    }

    #[test]
    fn observation_count_increments_per_record() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        assert_eq!(reg.observation_count("ep"), 0);
        reg.record_endpoint_outcome("ep", true, 100);
        reg.record_endpoint_outcome("ep", false, 200);
        assert_eq!(reg.observation_count("ep"), 2);
    }

    #[test]
    fn rank_indices_returns_all_indices() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        let ranked = reg.rank_indices(&["a", "b", "c"]);
        let mut sorted = ranked.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, vec![0, 1, 2]);
    }

    #[test]
    fn repeated_oracle_disagreement_quarantines_endpoint() {
        FAKE_CLOCK_MILLIS.with(|c| c.set(0));
        let reg = HealthRegistry::with_clock(Duration::from_secs(1), advancing_fake_clock);
        for _ in 0..12 {
            reg.record_endpoint_outcome("ep", true, 15);
        }

        reg.record_oracle_observation("ep", ResolverOracleObservation::Disagreement);
        let first = reg.snapshot("ep").unwrap();
        assert!(!first.quarantined);

        reg.record_oracle_observation("ep", ResolverOracleObservation::Disagreement);
        let second = reg.snapshot("ep").unwrap();
        assert!(second.quarantined);
        assert!(second.ewma_oracle_score < 0.2);
    }

    #[test]
    fn partial_overlap_is_less_harsh_than_full_disagreement() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        for _ in 0..20 {
            reg.record_endpoint_outcome("partial", true, 20);
            reg.record_endpoint_outcome("bad", true, 20);
        }

        reg.record_oracle_observation(
            "partial",
            ResolverOracleObservation::PartialOverlap {
                shared_answers: 2,
                resolver_only_answers: 1,
                oracle_only_answers: 1,
            },
        );
        reg.record_oracle_observation("bad", ResolverOracleObservation::Disagreement);
        reg.record_oracle_observation("bad", ResolverOracleObservation::Disagreement);

        let partial = reg.snapshot("partial").unwrap();
        let bad = reg.snapshot("bad").unwrap();
        assert!(!partial.quarantined, "partial overlap should tolerate CDN-style variance");
        assert!(bad.quarantined, "repeated disagreement should quarantine");

        let ranked = reg.rank_indices(&["bad", "partial"]);
        assert_eq!(ranked[0], 1);
    }

    #[test]
    fn scoped_health_is_isolated_by_network_scope() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        let wifi = ResolverNetworkScope::new("wifi:alpha");
        let cellular = ResolverNetworkScope::new("cell:carrier");

        for _ in 0..20 {
            reg.record_endpoint_outcome_in_scope(&wifi, "resolver-a", false, 3000);
            reg.record_endpoint_outcome_in_scope(&wifi, "resolver-b", true, 25);
        }

        let wifi_ranked = reg.rank_indices_in_scope(&wifi, &["resolver-a", "resolver-b"]);
        assert_eq!(wifi_ranked[0], 1);

        let cellular_ranked = reg.rank_indices_in_scope(&cellular, &["resolver-a", "resolver-b"]);
        assert_eq!(cellular_ranked[0], 0, "fresh scope should preserve configured order");
    }

    #[test]
    fn network_scope_can_separate_ipv4_and_ipv6_rankings() {
        let reg = HealthRegistry::new(Duration::from_secs(60));
        let wifi_ipv4 = ResolverNetworkScope::new("wifi:alpha|carrier:none|ip:v4|transport:doh");
        let wifi_ipv6 = ResolverNetworkScope::new("wifi:alpha|carrier:none|ip:v6|transport:doh");

        for _ in 0..20 {
            reg.record_endpoint_outcome_in_scope(&wifi_ipv4, "resolver-a", false, 4000);
            reg.record_endpoint_outcome_in_scope(&wifi_ipv4, "resolver-b", true, 20);
            reg.record_endpoint_outcome_in_scope(&wifi_ipv6, "resolver-a", true, 25);
            reg.record_endpoint_outcome_in_scope(&wifi_ipv6, "resolver-b", false, 4000);
        }

        assert_eq!(
            reg.rank_indices_in_scope(&wifi_ipv4, &["resolver-a", "resolver-b"])[0],
            1,
            "ipv4 scope should prefer resolver-b"
        );
        assert_eq!(
            reg.rank_indices_in_scope(&wifi_ipv6, &["resolver-a", "resolver-b"])[0],
            0,
            "ipv6 scope should keep its own preference history"
        );
    }

    #[test]
    fn thread_safety_concurrent_record_and_rank() {
        let reg = Arc::new(HealthRegistry::new(Duration::from_secs(60)));
        let mut handles = vec![];
        for i in 0..8 {
            let reg = Arc::clone(&reg);
            let label = format!("ep-{}", i % 3);
            handles.push(std::thread::spawn(move || {
                for _ in 0..100 {
                    reg.record_endpoint_outcome(&label, i % 2 == 0, 50 + i as u64 * 10);
                    let _ = reg.rank_indices(&["ep-0", "ep-1", "ep-2"]);
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
    }
}
