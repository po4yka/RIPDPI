//! Session-level strategy evolution for DPI evasion parameter combinations.
//!
//! This module implements a UCB1 multi-armed bandit that explores *combinations*
//! across adaptive dimensions, fake-TTL, timing jitter, and OOB-byte placement
//! using epsilon-greedy + UCB1 selection. It operates at the **session** level: a single [`StrategyEvolver`]
//! instance picks one [`StrategyCombo`] at a time and holds it until feedback
//! (success/failure) arrives.
//!
//! # Time-driven semantics (2026-04-25)
//!
//! The bandit is connection-event driven, but it consumes wall-clock time on
//! three read-side checks so it stays responsive to network drift without
//! adding a background timer thread (see
//! `docs/architecture/spike-evolver-timer-ttl-decay.md`):
//!
//! 1. **Active-experiment TTL** -- if a pending experiment has not seen a
//!    success/failure within [`StrategyEvolver::experiment_ttl_ms`], the
//!    next [`StrategyEvolver::suggest_hints`] call drops it silently and
//!    re-rolls. Default 30 s. Closes the silent-stall gap where a stuck
//!    flow could pin one combo for the entire session.
//! 2. **Idle-decay on combo stats** -- [`combo_fitness_at`] applies an
//!    `exp(-Δt / half_life)` weight to the success-rate term so stale
//!    winners fade and the bandit can re-explore. Default half-life 1 h.
//! 3. **Cooldown after consecutive failures** -- after
//!    [`StrategyEvolver::cooldown_after_failures`] non-skip failures in a
//!    row, the combo's stats record a `cooldown_until_ms` and selection
//!    skips it for [`StrategyEvolver::cooldown_ms`] (default 5 min). The
//!    next success clears the cooldown. If every bucket-matching combo is
//!    cooling at once, [`StrategyEvolver::select_next_combo`] falls back
//!    to [`pilot_combo_for_bucket`] so the evolver always returns a hint.
//!
//! The evolver uses a monotonic clock (`Instant` deltas relative to a
//! per-evolver epoch) so TTL, decay, and cooldown survive `SystemTime`
//! jumps and NTP corrections.
//!
//! # Interaction with per-flow adaptive tuning
//!
//! The crate also contains a per-flow adaptive tuning system in
//! [`crate::adaptive_tuning::AdaptivePlannerResolver`]. Both systems produce
//! [`AdaptivePlannerHints`], but they serve different roles:
//!
//! | System | Scope | Granularity |
//! |--------|-------|-------------|
//! | **Strategy Evolver** (this module) | Session-wide | One combo for all flows |
//! | **Adaptive Tuning** (`adaptive_tuning`) | Per-flow | Per (host, group, flow-kind) |
//!
//! **Priority chain for hint resolution:**
//!
//! 1. Evolver hints (when `strategy_evolution` is enabled) -- override everything
//! 2. Per-flow adaptive hints (from `AdaptivePlannerResolver`) -- used when the
//!    evolver is disabled or returns `None`
//! 3. Group defaults (from the `DesyncGroup` configuration)
//!
//! When the evolver is enabled (`--strategy-evolution`), its hints take
//! precedence and per-flow dimension cycling in `adaptive_tuning` is effectively
//! bypassed for the dimensions the evolver sets.
//!
//! # When to enable the evolver
//!
//! - Enable when exploring a new network where the best parameter combination is
//!   unknown. The evolver will converge on a high-fitness combo over time.
//! - Disable (the default) for stable networks where per-flow adaptive tuning
//!   already performs well, or when you want fine-grained per-host adaptation.

mod feedback;
mod lifecycle;
mod prior_store;
mod probe_results;
mod selection;
mod shared_priors;
#[cfg(test)]
mod tests;
mod thompson_sampling;
mod types;

// Re-exported for the Thompson scorer surface; UCB1 remains production default.
#[allow(unused_imports)]
pub use thompson_sampling::{sample_beta, BetaParams, ThompsonSampling};

// Re-exported so callers (including the JNI bridge) can verify and apply
// signed shared-priors bundles without reaching into sub-modules.
pub use probe_results::{
    apply_global_probe_results, clear_global_probe_results_for_tests, latest_global_probe_results,
    probe_combo_for_strategy_id, ProbeResult, ProbeResultsError, PROBE_OBSERVATION_WEIGHT,
};
pub use shared_priors::{
    apply_global_shared_priors, apply_global_shared_priors_with_embedded_key, apply_priors,
    apply_priors_with_embedded_key, canonical_combo_hash, global_shared_priors_len, is_production_key_set,
    latest_shared_priors, AppliedPriors, ApplyError, ManifestError, SharedPriorsError, SharedPriorsManifest,
    SHARED_PRIORS_PUB_KEY,
};

use std::collections::HashMap;
use std::time::Instant;

// Re-export the public API types so external callers continue to find them at
// `crate::strategy_evolver::{StrategyCombo, ComboStats, LearningContext, …}`.
pub use ripdpi_config::EnvironmentKind;
pub use types::{
    CapabilityContext, ComboStats, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass, StrategyCombo,
};

use types::*;

/// Default time-driven evolver knobs. See module-level docs.
pub(crate) const DEFAULT_EXPERIMENT_TTL_MS: u64 = 30_000;
pub(crate) const DEFAULT_DECAY_HALF_LIFE_MS: u64 = 3_600_000;
pub(crate) const DEFAULT_COOLDOWN_AFTER_FAILURES: u32 = 3;
pub(crate) const DEFAULT_COOLDOWN_MS: u64 = 300_000;

// ---------------------------------------------------------------------------
// StrategyEvolver
// ---------------------------------------------------------------------------

pub struct StrategyEvolver {
    combos: HashMap<StrategyCombo, ComboStats>,
    contexts: HashMap<LearningContext, ContextBanditState>,
    current_experiment: Option<StrategyCombo>,
    current_experiment_context: Option<LearningContext>,
    current_experiment_family: Option<StrategyFamily>,
    current_experiment_started_ms: Option<u64>,
    current_learning_context: LearningContext,
    explore_epsilon: f64,
    pub max_combos: usize,
    enabled: bool,
    rng_state: u64,
    /// Monotonic clock anchor. All internal timestamps are millisecond
    /// deltas from this instant.
    epoch: Instant,
    /// Wall-clock budget for a single experiment slot. After elapsing,
    /// the next [`Self::suggest_hints`] drops the experiment without
    /// updating stats and re-rolls.
    pub experiment_ttl_ms: u64,
    /// Half-life for the recency-weighted decay applied to combo
    /// fitness. `0` disables decay.
    pub decay_half_life_ms: u64,
    /// Number of consecutive failures that trips a per-combo cooldown.
    /// `0` disables cooldown.
    pub cooldown_after_failures: u32,
    /// Length of the cooldown window in milliseconds.
    pub cooldown_ms: u64,
    /// Hard cap on the number of attempts a single combo can accumulate
    /// before it is "frozen" — skipped during random exploration. The
    /// niche-winner / family-best paths still keep using a frozen combo so
    /// proven winners can be exploited indefinitely. `u32::MAX` (the
    /// default) disables the cap.
    pub max_arm_attempts: u32,
    /// When true, fitness scoring layers the rarity (`attempts < RARITY_FLOOR`)
    /// and retry-cost (`attempts > RETRY_SATURATION`) penalties on top of
    /// the standard score. Defaults to `false` so existing callers see
    /// unchanged behaviour.
    pub penalties_enabled: bool,
    /// Shared Beta posteriors keyed by canonical combo hash. Populated by
    /// [`Self::apply_shared_priors`] from a verified GitHub-hosted bundle.
    /// The UCB1 selection path does not consume these today; they are exposed
    /// for Thompson-style scoring and diagnostics. The "field data wins" merge rule lives at the
    /// consumption site rather than at apply time so the bundle stays a
    /// pure prior store.
    shared_priors: HashMap<u64, BetaParams>,
    /// Test-only override for the monotonic clock; production code leaves
    /// this `None` and the evolver uses [`Instant`] deltas.
    #[cfg(test)]
    test_clock_override_ms: Option<u64>,
}
