use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const INITIAL_SUCCESS_RATE: f64 = 0.5;
const INITIAL_LATENCY_MS: f64 = 200.0;
const LATENCY_SCORE_CAP_MS: f64 = 2000.0;

#[derive(Debug, Clone)]
pub struct HealthScoreSnapshot {
    pub ewma_success_rate: f64,
    pub ewma_latency_ms: f64,
    pub observation_count: u64,
}

#[derive(Debug, Clone)]
struct HealthScore {
    ewma_success_rate: f64,
    ewma_latency_ms: f64,
    last_updated: Instant,
    observation_count: u64,
}

impl HealthScore {
    fn new(now: Instant) -> Self {
        Self {
            ewma_success_rate: INITIAL_SUCCESS_RATE,
            ewma_latency_ms: INITIAL_LATENCY_MS,
            last_updated: now,
            observation_count: 0,
        }
    }

    fn update(&mut self, success: bool, latency_ms: u64, half_life: Duration, now: Instant) {
        let dt = now.saturating_duration_since(self.last_updated).as_secs_f64();
        let hl = half_life.as_secs_f64().max(f64::EPSILON);
        let alpha = (1.0_f64 - (-dt / hl).exp()).clamp(0.0, 1.0);
        let success_sample = if success { 1.0 } else { 0.0 };
        self.ewma_success_rate = alpha * success_sample + (1.0 - alpha) * self.ewma_success_rate;
        self.ewma_latency_ms = alpha * (latency_ms as f64) + (1.0 - alpha) * self.ewma_latency_ms;
        self.last_updated = now;
        self.observation_count += 1;
    }

    /// Composite score in 0.0..1.0 (higher is better).
    fn composite_score(&self) -> f64 {
        let latency_score = 1.0 - (self.ewma_latency_ms / LATENCY_SCORE_CAP_MS).clamp(0.0, 1.0);
        self.ewma_success_rate * 0.7 + latency_score * 0.3
    }
}

struct HealthRegistryInner {
    endpoint_scores: HashMap<String, HealthScore>,
    bootstrap_scores: HashMap<IpAddr, HealthScore>,
    half_life: Duration,
    clock: fn() -> Instant,
}

impl HealthRegistryInner {
    fn endpoint_score_or_insert(&mut self, label: &str) -> &mut HealthScore {
        let clock = self.clock;
        self.endpoint_scores.entry(label.to_string()).or_insert_with(|| HealthScore::new(clock()))
    }

    fn bootstrap_score_or_insert(&mut self, ip: IpAddr) -> &mut HealthScore {
        let clock = self.clock;
        self.bootstrap_scores.entry(ip).or_insert_with(|| HealthScore::new(clock()))
    }
}

/// Thread-safe EWMA health registry for encrypted DNS endpoints and bootstrap IPs.
///
/// Scores decay toward a neutral prior (0.5 success rate, 200ms latency) when
/// no observations are recorded, with a configurable half-life.
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
                endpoint_scores: HashMap::new(),
                bootstrap_scores: HashMap::new(),
                half_life,
                clock,
            })),
        }
    }

    /// Records an SNI-blocked outcome for a named endpoint.
    /// Uses elevated latency penalty (4000ms) to deprioritize blocked providers faster.
    pub fn record_sni_blocked(&self, label: &str) {
        self.record_endpoint_outcome(label, false, 4000);
    }

    /// Records the outcome of an exchange with a named endpoint.
    pub fn record_endpoint_outcome(&self, label: &str, success: bool, latency_ms: u64) {
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner.endpoint_score_or_insert(label).update(success, latency_ms, half_life, now);
        }
    }

    /// Records the outcome of a TCP connect attempt to a bootstrap IP.
    pub fn record_bootstrap_outcome(&self, ip: IpAddr, success: bool, latency_ms: u64) {
        if let Ok(mut inner) = self.inner.lock() {
            let now = (inner.clock)();
            let half_life = inner.half_life;
            inner.bootstrap_score_or_insert(ip).update(success, latency_ms, half_life, now);
        }
    }

    /// Returns the indices of `labels` sorted by composite health score (best first).
    pub fn rank_indices(&self, labels: &[&str]) -> Vec<usize> {
        if let Ok(mut inner) = self.inner.lock() {
            let mut scored: Vec<(usize, f64)> = labels
                .iter()
                .enumerate()
                .map(|(i, &label)| {
                    let score = inner.endpoint_score_or_insert(label).composite_score();
                    (i, score)
                })
                .collect();
            scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
            scored.into_iter().map(|(i, _)| i).collect()
        } else {
            (0..labels.len()).collect()
        }
    }

    /// Returns `ips` reordered by bootstrap health score (healthiest first).
    pub fn rank_bootstrap_ips(&self, ips: &[IpAddr]) -> Vec<IpAddr> {
        if let Ok(mut inner) = self.inner.lock() {
            let mut scored: Vec<(IpAddr, f64)> = ips
                .iter()
                .map(|&ip| {
                    let score = inner.bootstrap_score_or_insert(ip).composite_score();
                    (ip, score)
                })
                .collect();
            scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
            scored.into_iter().map(|(ip, _)| ip).collect()
        } else {
            ips.to_vec()
        }
    }

    /// Returns the number of observations recorded for a named endpoint.
    pub fn observation_count(&self, label: &str) -> u64 {
        self.inner
            .lock()
            .ok()
            .and_then(|inner| inner.endpoint_scores.get(label).map(|s| s.observation_count))
            .unwrap_or(0)
    }

    /// Returns a point-in-time snapshot of the health score for a named endpoint.
    pub fn snapshot(&self, label: &str) -> Option<HealthScoreSnapshot> {
        self.inner.lock().ok().and_then(|inner| {
            inner.endpoint_scores.get(label).map(|score| HealthScoreSnapshot {
                ewma_success_rate: score.ewma_success_rate,
                ewma_latency_ms: score.ewma_latency_ms,
                observation_count: score.observation_count,
            })
        })
    }
}

impl std::fmt::Debug for HealthRegistry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HealthRegistry").finish_non_exhaustive()
    }
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
        // Reset the fake clock for this test (relevant when tests share a thread).
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
