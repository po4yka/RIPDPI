use ripdpi_config::QuicFakeProfile;
use ripdpi_desync::AdaptiveUdpBurstProfile;
use ripdpi_failure_classifier::FailureClass;

use super::context::StrategyFamily;
use super::identity::StrategyCombo;

/// Multiplier for the success-rate term in fitness scoring.
/// With a success rate range of [0.0, 1.0], this yields a fitness component of [0, 1000].
pub(crate) const FITNESS_SUCCESS_WEIGHT: f64 = 1000.0;

/// Maximum average latency (ms) considered in fitness scoring.
/// Latencies above this cap contribute the same penalty, preventing a single
/// slow outlier from dominating the score.
pub(crate) const FITNESS_LATENCY_CAP_MS: f64 = 5000.0;

/// Per-millisecond penalty weight for average latency in fitness scoring.
/// At the cap (5000 ms), the maximum penalty is -100, roughly 10% of the
/// success-rate range.
pub(crate) const FITNESS_LATENCY_PENALTY_PER_MS: f64 = 0.02;
pub(crate) const FITNESS_FAILURE_VARIANCE_WEIGHT: f64 = 80.0;
pub(crate) const FITNESS_DETECTABILITY_WEIGHT: f64 = 35.0;
pub(crate) const FITNESS_STABILITY_WEIGHT: f64 = 45.0;
pub(crate) const FITNESS_LATENCY_VARIANCE_WEIGHT: f64 = 20.0;
pub(crate) const FITNESS_ENERGY_WEIGHT: f64 = 18.0;

/// Below this many attempts an arm is treated as "rare" and pays a flat
/// fitness penalty. Pure UCB1 already up-weights rare arms during exploration;
/// the rarity penalty applied here is a *fitness-side* counterweight so
/// eviction and winner selection do not promote arms that are still
/// statistically untrusted.
pub(crate) const RARITY_FLOOR: u32 = 3;

/// Flat fitness penalty per attempt below [`RARITY_FLOOR`]. Scales linearly:
/// an arm with 0 attempts pays `RARITY_PENALTY * RARITY_FLOOR`, an arm with
/// `RARITY_FLOOR` attempts pays nothing.
pub(crate) const RARITY_PENALTY: f64 = 5.0;

/// Above this many attempts an arm starts paying a log-damped retry cost
/// that nudges the evolver toward exploring fresh combos.
pub(crate) const RETRY_SATURATION: u32 = 20;

/// Multiplier on the log-damped retry term `RETRY_COST_FACTOR * ln(attempts -
/// RETRY_SATURATION + 1)`. Bounded by ln(remaining-pool-size) in practice.
pub(crate) const RETRY_COST_FACTOR: f64 = 4.0;

/// Half-life for the win (success) component of asymmetric decay.
///
/// Wins decay slower than losses so a successful family retains its learned
/// advantage longer than a failed exact variant. The ratio `WIN_HALF_LIFE_MS /
/// LOSS_HALF_LIFE_MS == 2` means the win count halves in twice the time it
/// takes the loss count to halve, preserving accumulated learning after a
/// transient failure streak.
///
/// Rationale: a single failure should not wipe out accumulated wins; a 2×
/// half-life ratio gives losses room to clear while keeping the win signal
/// meaningful for roughly two loss cycles.
pub(crate) const WIN_HALF_LIFE_MS: u64 = 7_200_000; // 2 h

/// Half-life for the loss (failure) component of asymmetric decay.
///
/// Losses decay faster than wins so that old failure streaks do not
/// permanently suppress a family that has since recovered.
pub(crate) const LOSS_HALF_LIFE_MS: u64 = 3_600_000; // 1 h (= WIN_HALF_LIFE_MS / 2)

/// Per-combo performance statistics.
///
/// `last_attempt_ms`, `cooldown_until_ms`, and `consecutive_failure_count`
/// drive the time-aware selection paths described in the module-level docs.
/// `last_attempt_ms` is the evolver-monotonic millisecond clock (delta from
/// the evolver epoch), not `SystemTime`.
#[derive(Debug, Clone)]
pub struct ComboStats {
    pub attempts: u32,
    pub successes: u32,
    pub total_latency_ms: u64,
    pub total_latency_square_ms: u128,
    pub last_attempt_ms: u64,
    pub last_failure_class: Option<FailureClass>,
    pub last_outcome_success: Option<bool>,
    pub outcome_flips: u32,
    pub detectability_events: u32,
    /// Number of non-skip failures since the most recent success. Resets to
    /// zero on the next success.
    pub consecutive_failure_count: u32,
    /// Monotonic millisecond timestamp at which the cooldown lifts. `None`
    /// means the combo is selectable now.
    pub cooldown_until_ms: Option<u64>,
}

impl ComboStats {
    pub(crate) fn new() -> Self {
        Self {
            attempts: 0,
            successes: 0,
            total_latency_ms: 0,
            total_latency_square_ms: 0,
            last_attempt_ms: 0,
            last_failure_class: None,
            last_outcome_success: None,
            outcome_flips: 0,
            detectability_events: 0,
            consecutive_failure_count: 0,
            cooldown_until_ms: None,
        }
    }

    pub(crate) fn record_attempt(
        &mut self,
        success: bool,
        latency_ms: u64,
        failure_class: Option<FailureClass>,
        last_attempt_ms: u64,
        cooldown_after_failures: u32,
        cooldown_ms: u64,
    ) -> CooldownTransition {
        if self.last_outcome_success.is_some_and(|last| last != success) {
            self.outcome_flips += 1;
        }
        self.last_outcome_success = Some(success);
        self.attempts += 1;
        let mut transition = CooldownTransition::Unchanged;
        if success {
            self.successes += 1;
            self.total_latency_ms += latency_ms;
            self.total_latency_square_ms += u128::from(latency_ms) * u128::from(latency_ms);
            self.last_failure_class = None;
            self.consecutive_failure_count = 0;
            if self.cooldown_until_ms.take().is_some() {
                transition = CooldownTransition::Cleared;
            }
        } else {
            self.last_failure_class = failure_class;
            if failure_class.is_some_and(is_detectability_failure) {
                self.detectability_events += 1;
            }
            self.consecutive_failure_count = self.consecutive_failure_count.saturating_add(1);
            if cooldown_after_failures > 0 && self.consecutive_failure_count >= cooldown_after_failures {
                let until = last_attempt_ms.saturating_add(cooldown_ms);
                self.cooldown_until_ms = Some(until);
                transition = CooldownTransition::Tripped { until_ms: until };
            }
        }
        self.last_attempt_ms = last_attempt_ms;
        transition
    }

    /// Returns `true` if the combo is currently cooling at `now_ms`.
    pub fn is_cooled(&self, now_ms: u64) -> bool {
        self.cooldown_until_ms.is_some_and(|until| until > now_ms)
    }

    /// Returns the recency-decay weight `exp(-Delta t / half_life)` applied to
    /// fitness scoring. Returns `1.0` for combos that have never been
    /// touched (no time signal), `1.0` if `half_life_ms == 0` (decay
    /// disabled), and decays toward zero as elapsed time grows.
    pub(crate) fn decay_weight(&self, now_ms: u64, half_life_ms: u64) -> f64 {
        if self.attempts == 0 || half_life_ms == 0 {
            return 1.0;
        }
        let elapsed = now_ms.saturating_sub(self.last_attempt_ms) as f64;
        let half_life = half_life_ms as f64;
        (-std::f64::consts::LN_2 * elapsed / half_life).exp()
    }

    /// Apply asymmetric exponential decay to the win and loss accumulators.
    ///
    /// Wins use [`WIN_HALF_LIFE_MS`] and losses use [`LOSS_HALF_LIFE_MS`]
    /// (wins decay 2× slower). This is intentionally cheaper than decaying on
    /// every update: callers should invoke this only at periodic checkpoints
    /// (e.g. once per selection cycle) rather than after each `record_attempt`.
    ///
    /// The method is a no-op when `elapsed_ms == 0` (idempotent at zero) or
    /// when `attempts == 0` (nothing to decay). `total_latency_ms` and
    /// `total_latency_square_ms` are scaled by the win multiplier so that
    /// the average-latency computation remains consistent with the decayed
    /// success count. `outcome_flips` and `detectability_events` are scaled
    /// by the overall history multiplier so their per-attempt ratios stay
    /// bounded.
    pub fn apply_decay(&mut self, elapsed_ms: u64) {
        if elapsed_ms == 0 || self.attempts == 0 {
            return;
        }

        let elapsed = elapsed_ms as f64;

        // Compute per-component decay multipliers: exp(-ln2 * t / half_life)
        let win_mult = (-std::f64::consts::LN_2 * elapsed / WIN_HALF_LIFE_MS as f64).exp();
        let loss_mult = (-std::f64::consts::LN_2 * elapsed / LOSS_HALF_LIFE_MS as f64).exp();

        let old_wins = self.successes as f64;
        let old_losses = (self.attempts - self.successes) as f64;
        let old_history = old_wins + old_losses;

        let new_wins = (old_wins * win_mult).max(0.0);
        let new_losses = (old_losses * loss_mult).max(0.0);

        // Round to nearest integer; guarantee wins <= attempts.
        self.successes = new_wins.round() as u32;
        self.attempts = (new_wins + new_losses).round() as u32;
        // Ensure consistency: successes can never exceed attempts after rounding.
        if self.successes > self.attempts {
            self.successes = self.attempts;
        }

        // Scale the flip and detectability counters by the same overall
        // history multiplier so the per-attempt penalty ratios in
        // `fitness_at` stay in [0, 1] instead of growing without bound as
        // the decayed `attempts` denominator shrinks.
        if old_history > 0.0 {
            let history_mult = (new_wins + new_losses) / old_history;
            self.outcome_flips = (self.outcome_flips as f64 * history_mult).round() as u32;
            self.detectability_events = (self.detectability_events as f64 * history_mult).round() as u32;
            if self.outcome_flips > self.attempts {
                self.outcome_flips = self.attempts;
            }
            if self.detectability_events > self.attempts {
                self.detectability_events = self.attempts;
            }
        }

        // Scale latency accumulators proportionally with the win multiplier so
        // avg_latency_ms stays consistent with the decayed success count.
        self.total_latency_ms = (self.total_latency_ms as f64 * win_mult).round() as u64;
        self.total_latency_square_ms = (self.total_latency_square_ms as f64 * win_mult * win_mult).round() as u128;
    }

    pub(crate) fn avg_latency_ms(&self) -> f64 {
        if self.successes > 0 { self.total_latency_ms as f64 / self.successes as f64 } else { FITNESS_LATENCY_CAP_MS }
    }

    pub(crate) fn latency_variance_ms(&self) -> f64 {
        if self.successes <= 1 {
            return 0.0;
        }
        let successes = self.successes as f64;
        let mean = self.avg_latency_ms();
        let mean_square = self.total_latency_square_ms as f64 / successes;
        (mean_square - mean * mean).max(0.0)
    }

    /// Fitness score: higher is better. No idle-decay applied.
    ///
    /// `success_rate * FITNESS_SUCCESS_WEIGHT - avg_latency.min(FITNESS_LATENCY_CAP_MS) * FITNESS_LATENCY_PENALTY_PER_MS`
    ///
    /// The success-rate term dominates (range 0..1000) so reliability always
    /// wins over speed. The latency penalty caps at -100 (~10% of the success
    /// range), which is large enough to differentiate fast vs slow strategies
    /// when success rates are comparable.
    pub fn fitness(&self) -> f64 {
        self.fitness_at(self.last_attempt_ms, 0)
    }

    /// Fitness score with idle-decay applied to the success-rate term.
    ///
    /// `now_ms` and `half_life_ms` use the evolver's monotonic clock. Pass
    /// `half_life_ms == 0` to disable decay.
    pub fn fitness_at(&self, now_ms: u64, half_life_ms: u64) -> f64 {
        if self.attempts == 0 {
            return 0.0;
        }
        let raw_success_rate = self.successes as f64 / self.attempts as f64;
        let decay = self.decay_weight(now_ms, half_life_ms);
        let success_rate = raw_success_rate * decay;
        let avg_latency = self.avg_latency_ms();
        let failure_rate = 1.0 - raw_success_rate;
        let stability_penalty = self.outcome_flips as f64 / self.attempts.max(1) as f64;
        let variance_penalty = (self.latency_variance_ms().sqrt() / FITNESS_LATENCY_CAP_MS).min(1.0);
        let detectability_penalty = self.detectability_events as f64 / self.attempts.max(1) as f64;

        success_rate * FITNESS_SUCCESS_WEIGHT
            - avg_latency.min(FITNESS_LATENCY_CAP_MS) * FITNESS_LATENCY_PENALTY_PER_MS
            - failure_rate * FITNESS_FAILURE_VARIANCE_WEIGHT
            - stability_penalty * FITNESS_STABILITY_WEIGHT
            - variance_penalty * FITNESS_LATENCY_VARIANCE_WEIGHT
            - detectability_penalty * FITNESS_DETECTABILITY_WEIGHT
    }
}

/// Outcome of [`ComboStats::record_attempt`] for cooldown tracking.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CooldownTransition {
    /// Cooldown state did not change.
    Unchanged,
    /// The combo just entered a cooldown window.
    Tripped { until_ms: u64 },
    /// The combo successfully cleared a previously active cooldown.
    Cleared,
}

pub(crate) fn combo_fitness_at(combo: &StrategyCombo, stats: &ComboStats, now_ms: u64, half_life_ms: u64) -> f64 {
    combo_fitness_at_with_penalties(combo, stats, now_ms, half_life_ms, false)
}

pub(crate) fn combo_fitness_at_with_penalties(
    combo: &StrategyCombo,
    stats: &ComboStats,
    now_ms: u64,
    half_life_ms: u64,
    penalties_enabled: bool,
) -> f64 {
    let mut score = stats.fitness_at(now_ms, half_life_ms) - combo_energy_cost(combo) * FITNESS_ENERGY_WEIGHT;
    if penalties_enabled {
        score -= rarity_penalty(stats.attempts);
        score -= retry_cost(stats.attempts);
    }
    score
}

pub(crate) fn combo_energy_cost(combo: &StrategyCombo) -> f64 {
    let mut cost = 0.0;
    if combo.fake_ttl.is_some() {
        cost += 1.2;
    }
    if combo.entropy_mode.is_some() {
        cost += 0.8;
    }
    if combo.udp_burst_profile == Some(AdaptiveUdpBurstProfile::Aggressive) {
        cost += 1.0;
    }
    if combo.quic_fake_profile == Some(QuicFakeProfile::RealisticInitial) {
        cost += 0.7;
    }
    if combo.family() == StrategyFamily::Mixed {
        cost += 1.3;
    }
    cost
}

fn is_detectability_failure(class: FailureClass) -> bool {
    matches!(
        class,
        FailureClass::TlsAlert | FailureClass::HttpBlockpage | FailureClass::Redirect | FailureClass::ConnectionFreeze
    )
}

pub(crate) fn rarity_penalty(attempts: u32) -> f64 {
    if attempts >= RARITY_FLOOR {
        return 0.0;
    }
    f64::from(RARITY_FLOOR - attempts) * RARITY_PENALTY
}

pub(crate) fn retry_cost(attempts: u32) -> f64 {
    if attempts <= RETRY_SATURATION {
        return 0.0;
    }
    let over = f64::from(attempts - RETRY_SATURATION);
    RETRY_COST_FACTOR * (over + 1.0).ln()
}
