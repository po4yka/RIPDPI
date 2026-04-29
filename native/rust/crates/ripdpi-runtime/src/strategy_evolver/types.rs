//! Type definitions extracted from the strategy evolver module.
//!
//! This file contains all enums, structs, helper discriminant functions,
//! and the combo pool that the `StrategyEvolver` implementation depends on.
//! Items are `pub(super)` unless they are part of the public crate API.

use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::{EntropyMode, EnvironmentKind, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_failure_classifier::FailureClass;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

pub(super) fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

/// Assign a stable discriminant for hashing. The exact values are arbitrary
/// but must be consistent for the lifetime of the process.
pub(super) fn offset_base_disc(o: OffsetBase) -> u8 {
    match o {
        OffsetBase::Abs => 0,
        OffsetBase::PayloadEnd => 1,
        OffsetBase::PayloadMid => 2,
        OffsetBase::PayloadRand => 3,
        OffsetBase::Host => 4,
        OffsetBase::EndHost => 5,
        OffsetBase::HostMid => 6,
        OffsetBase::HostRand => 7,
        OffsetBase::Sld => 8,
        OffsetBase::MidSld => 9,
        OffsetBase::EndSld => 10,
        OffsetBase::Method => 11,
        OffsetBase::ExtLen => 12,
        OffsetBase::SniExt => 13,
        OffsetBase::EchExt => 21,
        OffsetBase::AutoBalanced => 14,
        OffsetBase::AutoHost => 15,
        OffsetBase::AutoMidSld => 16,
        OffsetBase::AutoEndHost => 17,
        OffsetBase::AutoMethod => 18,
        OffsetBase::AutoSniExt => 19,
        OffsetBase::AutoExtLen => 20,
    }
}

pub(super) fn quic_fake_disc(q: QuicFakeProfile) -> u8 {
    match q {
        QuicFakeProfile::Disabled => 0,
        QuicFakeProfile::CompatDefault => 1,
        QuicFakeProfile::RealisticInitial => 2,
        _ => 0,
    }
}

pub(super) fn tls_randrec_disc(t: AdaptiveTlsRandRecProfile) -> u8 {
    match t {
        AdaptiveTlsRandRecProfile::Balanced => 0,
        AdaptiveTlsRandRecProfile::Tight => 1,
        AdaptiveTlsRandRecProfile::Wide => 2,
    }
}

pub(super) fn udp_burst_disc(u: AdaptiveUdpBurstProfile) -> u8 {
    match u {
        AdaptiveUdpBurstProfile::Balanced => 0,
        AdaptiveUdpBurstProfile::Conservative => 1,
        AdaptiveUdpBurstProfile::Aggressive => 2,
    }
}

pub(super) fn entropy_mode_disc(e: EntropyMode) -> u8 {
    match e {
        EntropyMode::Disabled => 0,
        EntropyMode::Popcount => 1,
        EntropyMode::Shannon => 2,
        EntropyMode::Combined => 3,
        _ => 0,
    }
}

pub(super) fn hash_option_disc<H: Hasher>(h: &mut H, tag: u8, disc: Option<u8>) {
    h.write_u8(tag);
    match disc {
        None => h.write_u8(0xFF),
        Some(d) => {
            h.write_u8(0);
            h.write_u8(d);
        }
    }
}

// ---------------------------------------------------------------------------
// Learning context enums
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningTargetBucket {
    #[default]
    Generic,
    Tls,
    Ech,
    Quic,
    Control,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningTransportKind {
    #[default]
    Unknown,
    Tcp,
    UdpQuic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningAlpnClass {
    #[default]
    Unknown,
    Http1,
    H2Http11,
    H3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningHostingFamily {
    #[default]
    Unknown,
    Direct,
    Cloudflare,
    Google,
    DomesticCdn,
    ForeignCdn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningReachabilitySet {
    #[default]
    Unknown,
    Control,
    Domestic,
    Foreign,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum ResolverHealthClass {
    #[default]
    Unknown,
    Healthy,
    Degraded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum CapabilityContext {
    #[default]
    Unknown,
    Full,
    Degraded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(super) enum StrategyFamily {
    Baseline,
    SplitOffset,
    TlsRecordOffset,
    TlsRandRec,
    UdpBurst,
    QuicFake,
    FakeTtl,
    Entropy,
    Mixed,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub struct LearningContext {
    pub network_identity: Option<String>,
    pub target_bucket: LearningTargetBucket,
    pub transport: LearningTransportKind,
    pub alpn_class: LearningAlpnClass,
    pub hosting_family: LearningHostingFamily,
    pub reachability_set: LearningReachabilitySet,
    pub ech_capable: bool,
    pub resolver_health: ResolverHealthClass,
    pub rooted: bool,
    pub capability_context: CapabilityContext,
    /// Coarse classification of the host device — `Field` for real user
    /// devices, `Emulator` for AVD / CI test devices, `Unknown` when the
    /// platform-side detector has not been wired yet (P4.4.5, offline-learner architecture note).
    /// Including this here automatically segregates field-derived bandit
    /// statistics from emulator-derived ones via the `HashMap`'s
    /// per-context state.
    pub environment: EnvironmentKind,
}

// ---------------------------------------------------------------------------
// Internal bandit state
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default)]
pub(super) struct FamilyStats {
    pub(super) attempts: u32,
    pub(super) total_reward: f64,
}

#[derive(Debug, Clone, Default)]
pub(super) struct ContextBanditState {
    pub(super) combos: HashMap<StrategyCombo, ComboStats>,
    pub(super) families: HashMap<StrategyFamily, FamilyStats>,
    pub(super) piloted_buckets: HashSet<LearningTargetBucket>,
    pub(super) niche_winners: HashMap<LearningTargetBucket, StrategyCombo>,
}

// ---------------------------------------------------------------------------
// StrategyCombo
// ---------------------------------------------------------------------------

/// Snapshot of all adaptive dimensions that together form a single evasion
/// strategy. `None` means "defer to the default planner for that dimension".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StrategyCombo {
    pub split_offset_base: Option<OffsetBase>,
    pub tls_record_offset_base: Option<OffsetBase>,
    pub tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    pub udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    pub quic_fake_profile: Option<QuicFakeProfile>,
    pub fake_ttl: Option<u8>,
    pub entropy_mode: Option<EntropyMode>,
}

impl Hash for StrategyCombo {
    fn hash<H: Hasher>(&self, state: &mut H) {
        hash_option_disc(state, 0, self.split_offset_base.map(offset_base_disc));
        hash_option_disc(state, 1, self.tls_record_offset_base.map(offset_base_disc));
        hash_option_disc(state, 2, self.tlsrandrec_profile.map(tls_randrec_disc));
        hash_option_disc(state, 3, self.udp_burst_profile.map(udp_burst_disc));
        hash_option_disc(state, 4, self.quic_fake_profile.map(quic_fake_disc));
        hash_option_disc(state, 5, self.fake_ttl);
        hash_option_disc(state, 6, self.entropy_mode.map(entropy_mode_disc));
    }
}

impl StrategyCombo {
    pub(super) fn default_combo() -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
            entropy_mode: None,
        }
    }

    pub(super) fn to_hints(&self) -> AdaptivePlannerHints {
        AdaptivePlannerHints {
            split_offset_base: self.split_offset_base,
            tls_record_offset_base: self.tls_record_offset_base,
            tlsrandrec_profile: self.tlsrandrec_profile,
            udp_burst_profile: self.udp_burst_profile,
            quic_fake_profile: self.quic_fake_profile,
            entropy_mode: self.entropy_mode,
        }
    }

    pub(super) fn family(&self) -> StrategyFamily {
        let dimensions = [
            self.split_offset_base.is_some(),
            self.tls_record_offset_base.is_some(),
            self.tlsrandrec_profile.is_some(),
            self.udp_burst_profile.is_some(),
            self.quic_fake_profile.is_some(),
            self.fake_ttl.is_some(),
            self.entropy_mode.is_some(),
        ]
        .into_iter()
        .filter(|value| *value)
        .count();
        if dimensions > 1 {
            return StrategyFamily::Mixed;
        }
        if self.entropy_mode.is_some() {
            StrategyFamily::Entropy
        } else if self.fake_ttl.is_some() {
            StrategyFamily::FakeTtl
        } else if self.quic_fake_profile.is_some() {
            StrategyFamily::QuicFake
        } else if self.udp_burst_profile.is_some() {
            StrategyFamily::UdpBurst
        } else if self.tlsrandrec_profile.is_some() {
            StrategyFamily::TlsRandRec
        } else if self.tls_record_offset_base.is_some() {
            StrategyFamily::TlsRecordOffset
        } else if self.split_offset_base.is_some() {
            StrategyFamily::SplitOffset
        } else {
            StrategyFamily::Baseline
        }
    }
}

// ---------------------------------------------------------------------------
// ComboStats
// ---------------------------------------------------------------------------

/// Multiplier for the success-rate term in fitness scoring.
/// With a success rate range of [0.0, 1.0], this yields a fitness component of [0, 1000].
pub(super) const FITNESS_SUCCESS_WEIGHT: f64 = 1000.0;

/// Maximum average latency (ms) considered in fitness scoring.
/// Latencies above this cap contribute the same penalty, preventing a single
/// slow outlier from dominating the score.
pub(super) const FITNESS_LATENCY_CAP_MS: f64 = 5000.0;

/// Per-millisecond penalty weight for average latency in fitness scoring.
/// At the cap (5000 ms), the maximum penalty is -100, roughly 10% of the
/// success-rate range.
pub(super) const FITNESS_LATENCY_PENALTY_PER_MS: f64 = 0.02;
pub(super) const FITNESS_FAILURE_VARIANCE_WEIGHT: f64 = 80.0;
pub(super) const FITNESS_DETECTABILITY_WEIGHT: f64 = 35.0;
pub(super) const FITNESS_STABILITY_WEIGHT: f64 = 45.0;
pub(super) const FITNESS_LATENCY_VARIANCE_WEIGHT: f64 = 20.0;
pub(super) const FITNESS_ENERGY_WEIGHT: f64 = 18.0;

// ---------------------------------------------------------------------------
// Rarity / retry penalty knobs (P4.4.2, offline-learner architecture note)
// ---------------------------------------------------------------------------

/// Below this many attempts an arm is treated as "rare" and pays a flat
/// fitness penalty. Pure UCB1 already up-weights rare arms during exploration;
/// the rarity penalty applied here is a *fitness-side* counterweight so
/// eviction and winner selection do not promote arms that are still
/// statistically untrusted.
pub(super) const RARITY_FLOOR: u32 = 3;

/// Flat fitness penalty per attempt below [`RARITY_FLOOR`]. Scales linearly:
/// an arm with 0 attempts pays `RARITY_PENALTY * RARITY_FLOOR`, an arm with
/// `RARITY_FLOOR` attempts pays nothing.
pub(super) const RARITY_PENALTY: f64 = 5.0;

/// Above this many attempts an arm starts paying a log-damped retry cost
/// that nudges the evolver toward exploring fresh combos.
pub(super) const RETRY_SATURATION: u32 = 20;

/// Multiplier on the log-damped retry term `RETRY_COST_FACTOR * ln(attempts -
/// RETRY_SATURATION + 1)`. Bounded by ln(remaining-pool-size) in practice.
pub(super) const RETRY_COST_FACTOR: f64 = 4.0;

/// Linear penalty levied on combos with `attempts < RARITY_FLOOR`. Returns
/// zero once the floor is reached.
pub(super) fn rarity_penalty(attempts: u32) -> f64 {
    if attempts >= RARITY_FLOOR {
        return 0.0;
    }
    f64::from(RARITY_FLOOR - attempts) * RARITY_PENALTY
}

/// Log-damped cost levied on combos with `attempts > RETRY_SATURATION` so
/// the evolver starts to lean away from saturated arms. Returns zero below
/// the saturation threshold.
pub(super) fn retry_cost(attempts: u32) -> f64 {
    if attempts <= RETRY_SATURATION {
        return 0.0;
    }
    let over = f64::from(attempts - RETRY_SATURATION);
    RETRY_COST_FACTOR * (over + 1.0).ln()
}

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
    pub(super) fn new() -> Self {
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

    pub(super) fn record_attempt(
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

    /// Returns the recency-decay weight `exp(-Δt / half_life)` applied to
    /// fitness scoring. Returns `1.0` for combos that have never been
    /// touched (no time signal), `1.0` if `half_life_ms == 0` (decay
    /// disabled), and decays toward zero as elapsed time grows.
    pub(super) fn decay_weight(&self, now_ms: u64, half_life_ms: u64) -> f64 {
        if self.attempts == 0 || half_life_ms == 0 {
            return 1.0;
        }
        let elapsed = now_ms.saturating_sub(self.last_attempt_ms) as f64;
        let half_life = half_life_ms as f64;
        // exp(-ln(2) * Δt / half_life)
        (-std::f64::consts::LN_2 * elapsed / half_life).exp()
    }

    pub(super) fn avg_latency_ms(&self) -> f64 {
        if self.successes > 0 {
            self.total_latency_ms as f64 / self.successes as f64
        } else {
            FITNESS_LATENCY_CAP_MS
        }
    }

    pub(super) fn latency_variance_ms(&self) -> f64 {
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
        // Equivalent to fitness_at(self.last_attempt_ms, 0).
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
pub(super) enum CooldownTransition {
    /// Cooldown state did not change.
    Unchanged,
    /// The combo just entered a cooldown window.
    Tripped { until_ms: u64 },
    /// The combo successfully cleared a previously active cooldown.
    Cleared,
}

pub(super) fn is_detectability_failure(class: FailureClass) -> bool {
    matches!(
        class,
        FailureClass::TlsAlert | FailureClass::HttpBlockpage | FailureClass::Redirect | FailureClass::ConnectionFreeze
    )
}

pub(super) fn combo_energy_cost(combo: &StrategyCombo) -> f64 {
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

/// Idle-decayed combo fitness used by all selection / eviction paths.
///
/// Pass `half_life_ms == 0` to disable decay (legacy behaviour). The
/// production paths in [`super::StrategyEvolver`] always pass the configured
/// `decay_half_life_ms`. Rarity / retry penalties (P4.4.2) are layered on
/// top via [`combo_fitness_at_with_penalties`] when the evolver has them
/// enabled; the legacy entry-point preserves the old behaviour for callers
/// that have not opted in.
pub(super) fn combo_fitness_at(combo: &StrategyCombo, stats: &ComboStats, now_ms: u64, half_life_ms: u64) -> f64 {
    combo_fitness_at_with_penalties(combo, stats, now_ms, half_life_ms, false)
}

/// Idle-decayed combo fitness with the rarity / retry penalties layered on
/// top when `penalties_enabled` is true. See [`rarity_penalty`] and
/// [`retry_cost`] for the individual terms.
pub(super) fn combo_fitness_at_with_penalties(
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

// ---------------------------------------------------------------------------
// Combo pool (fixed set of common combinations)
// ---------------------------------------------------------------------------

/// Pre-defined pool entry covering all 7 adaptive dimensions.
pub(super) struct PoolEntry {
    pub(super) split_offset_base: Option<OffsetBase>,
    pub(super) tls_record_offset_base: Option<OffsetBase>,
    pub(super) tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    pub(super) udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    pub(super) quic_fake_profile: Option<QuicFakeProfile>,
    pub(super) fake_ttl: Option<u8>,
    pub(super) entropy_mode: Option<EntropyMode>,
}

impl PoolEntry {
    pub(super) const fn new() -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
            entropy_mode: None,
        }
    }
}

/// Pre-defined pool of combos to explore across all 7 dimensions.
pub(super) const COMBO_POOL: &[PoolEntry] = &[
    // 0: Default (all None)
    PoolEntry::new(),
    // --- Split offset + fake TTL variants (original entries) ---
    // 1-4: AutoHost offset variants
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), fake_ttl: Some(6), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), fake_ttl: Some(10), ..PoolEntry::new() },
    // 5-8: MidSld offset variants
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), fake_ttl: Some(6), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), fake_ttl: Some(10), ..PoolEntry::new() },
    // 9-12: EndHost offset variants
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), fake_ttl: Some(6), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), fake_ttl: Some(10), ..PoolEntry::new() },
    // 13-15: Shannon/Combined entropy padding variants
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        entropy_mode: Some(EntropyMode::Shannon),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        fake_ttl: Some(8),
        entropy_mode: Some(EntropyMode::Shannon),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        entropy_mode: Some(EntropyMode::Combined),
        ..PoolEntry::new()
    },
    // --- TLS RandRec profile variants ---
    // 16: AutoHost + Tight TLS RandRec
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
        ..PoolEntry::new()
    },
    // 17: AutoHost + Wide TLS RandRec
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide),
        ..PoolEntry::new()
    },
    // 18: MidSld + Balanced TLS RandRec
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Balanced),
        ..PoolEntry::new()
    },
    // --- UDP burst profile variants ---
    // 19: AutoHost + TTL 8 + Conservative UDP burst
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..PoolEntry::new()
    },
    // 20: AutoHost + TTL 8 + Aggressive UDP burst
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Aggressive),
        ..PoolEntry::new()
    },
    // 21: MidSld + Conservative UDP burst
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..PoolEntry::new()
    },
    // --- QUIC fake profile variants ---
    // 22: AutoHost + TTL 8 + CompatDefault QUIC fake
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        quic_fake_profile: Some(QuicFakeProfile::CompatDefault),
        ..PoolEntry::new()
    },
    // 23: AutoHost + TTL 8 + RealisticInitial QUIC fake
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        quic_fake_profile: Some(QuicFakeProfile::RealisticInitial),
        ..PoolEntry::new()
    },
    // 24: EndHost + CompatDefault QUIC fake
    PoolEntry {
        split_offset_base: Some(OffsetBase::EndHost),
        quic_fake_profile: Some(QuicFakeProfile::CompatDefault),
        ..PoolEntry::new()
    },
    // --- TLS record offset variants ---
    // 25: AutoHost split + EndHost TLS record offset
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        tls_record_offset_base: Some(OffsetBase::EndHost),
        ..PoolEntry::new()
    },
    // 26: MidSld split + SniExt TLS record offset
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        tls_record_offset_base: Some(OffsetBase::SniExt),
        ..PoolEntry::new()
    },
    // 27: EndHost split + AutoBalanced TLS record offset
    PoolEntry {
        split_offset_base: Some(OffsetBase::EndHost),
        tls_record_offset_base: Some(OffsetBase::AutoBalanced),
        ..PoolEntry::new()
    },
    // 28-30: ECH-aware variants
    PoolEntry { split_offset_base: Some(OffsetBase::EchExt), ..PoolEntry::new() },
    PoolEntry { tls_record_offset_base: Some(OffsetBase::EchExt), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EchExt), fake_ttl: Some(8), ..PoolEntry::new() },
    // --- Combined multi-dimension entries ---
    // 31: AutoHost + Tight RandRec + Conservative UDP + TTL 8
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..PoolEntry::new()
    },
    // 32: MidSld + Wide RandRec + RealisticInitial QUIC + Shannon entropy
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide),
        quic_fake_profile: Some(QuicFakeProfile::RealisticInitial),
        entropy_mode: Some(EntropyMode::Shannon),
        ..PoolEntry::new()
    },
];

pub(super) fn combo_from_pool(index: usize) -> StrategyCombo {
    let entry = &COMBO_POOL[index % COMBO_POOL.len()];
    StrategyCombo {
        split_offset_base: entry.split_offset_base,
        tls_record_offset_base: entry.tls_record_offset_base,
        tlsrandrec_profile: entry.tlsrandrec_profile,
        udp_burst_profile: entry.udp_burst_profile,
        quic_fake_profile: entry.quic_fake_profile,
        fake_ttl: entry.fake_ttl,
        entropy_mode: entry.entropy_mode,
    }
}
