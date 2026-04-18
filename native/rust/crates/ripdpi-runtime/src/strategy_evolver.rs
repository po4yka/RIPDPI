//! Session-level strategy evolution for DPI evasion parameter combinations.
//!
//! This module implements a UCB1 multi-armed bandit that explores *combinations*
//! across the 5 adaptive dimensions plus fake-TTL using epsilon-greedy + UCB1
//! selection. It operates at the **session** level: a single [`StrategyEvolver`]
//! instance picks one [`StrategyCombo`] at a time and holds it until feedback
//! (success/failure) arrives.
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

use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::{EntropyMode, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_failure_classifier::FailureClass;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

/// Assign a stable discriminant for hashing. The exact values are arbitrary
/// but must be consistent for the lifetime of the process.
fn offset_base_disc(o: OffsetBase) -> u8 {
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

fn quic_fake_disc(q: QuicFakeProfile) -> u8 {
    match q {
        QuicFakeProfile::Disabled => 0,
        QuicFakeProfile::CompatDefault => 1,
        QuicFakeProfile::RealisticInitial => 2,
    }
}

fn tls_randrec_disc(t: AdaptiveTlsRandRecProfile) -> u8 {
    match t {
        AdaptiveTlsRandRecProfile::Balanced => 0,
        AdaptiveTlsRandRecProfile::Tight => 1,
        AdaptiveTlsRandRecProfile::Wide => 2,
    }
}

fn udp_burst_disc(u: AdaptiveUdpBurstProfile) -> u8 {
    match u {
        AdaptiveUdpBurstProfile::Balanced => 0,
        AdaptiveUdpBurstProfile::Conservative => 1,
        AdaptiveUdpBurstProfile::Aggressive => 2,
    }
}

fn entropy_mode_disc(e: EntropyMode) -> u8 {
    match e {
        EntropyMode::Disabled => 0,
        EntropyMode::Popcount => 1,
        EntropyMode::Shannon => 2,
        EntropyMode::Combined => 3,
    }
}

fn hash_option_disc<H: Hasher>(h: &mut H, tag: u8, disc: Option<u8>) {
    h.write_u8(tag);
    match disc {
        None => h.write_u8(0xFF),
        Some(d) => {
            h.write_u8(0);
            h.write_u8(d);
        }
    }
}

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
enum StrategyFamily {
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
}

#[derive(Debug, Clone, Default)]
struct FamilyStats {
    attempts: u32,
    total_reward: f64,
}

#[derive(Debug, Clone, Default)]
struct ContextBanditState {
    combos: HashMap<StrategyCombo, ComboStats>,
    families: HashMap<StrategyFamily, FamilyStats>,
    piloted_buckets: HashSet<LearningTargetBucket>,
    niche_winners: HashMap<LearningTargetBucket, StrategyCombo>,
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
    fn default_combo() -> Self {
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

    fn to_hints(&self) -> AdaptivePlannerHints {
        AdaptivePlannerHints {
            split_offset_base: self.split_offset_base,
            tls_record_offset_base: self.tls_record_offset_base,
            tlsrandrec_profile: self.tlsrandrec_profile,
            udp_burst_profile: self.udp_burst_profile,
            quic_fake_profile: self.quic_fake_profile,
            entropy_mode: self.entropy_mode,
        }
    }

    fn family(&self) -> StrategyFamily {
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
const FITNESS_SUCCESS_WEIGHT: f64 = 1000.0;

/// Maximum average latency (ms) considered in fitness scoring.
/// Latencies above this cap contribute the same penalty, preventing a single
/// slow outlier from dominating the score.
const FITNESS_LATENCY_CAP_MS: f64 = 5000.0;

/// Per-millisecond penalty weight for average latency in fitness scoring.
/// At the cap (5000 ms), the maximum penalty is -100, roughly 10% of the
/// success-rate range.
const FITNESS_LATENCY_PENALTY_PER_MS: f64 = 0.02;
const FITNESS_FAILURE_VARIANCE_WEIGHT: f64 = 80.0;
const FITNESS_DETECTABILITY_WEIGHT: f64 = 35.0;
const FITNESS_STABILITY_WEIGHT: f64 = 45.0;
const FITNESS_LATENCY_VARIANCE_WEIGHT: f64 = 20.0;
const FITNESS_ENERGY_WEIGHT: f64 = 18.0;

/// Per-combo performance statistics.
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
}

impl ComboStats {
    fn new() -> Self {
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
        }
    }

    fn record_attempt(
        &mut self,
        success: bool,
        latency_ms: u64,
        failure_class: Option<FailureClass>,
        last_attempt_ms: u64,
    ) {
        if self.last_outcome_success.is_some_and(|last| last != success) {
            self.outcome_flips += 1;
        }
        self.last_outcome_success = Some(success);
        self.attempts += 1;
        if success {
            self.successes += 1;
            self.total_latency_ms += latency_ms;
            self.total_latency_square_ms += u128::from(latency_ms) * u128::from(latency_ms);
            self.last_failure_class = None;
        } else {
            self.last_failure_class = failure_class;
            if failure_class.is_some_and(is_detectability_failure) {
                self.detectability_events += 1;
            }
        }
        self.last_attempt_ms = last_attempt_ms;
    }

    fn avg_latency_ms(&self) -> f64 {
        if self.successes > 0 {
            self.total_latency_ms as f64 / self.successes as f64
        } else {
            FITNESS_LATENCY_CAP_MS
        }
    }

    fn latency_variance_ms(&self) -> f64 {
        if self.successes <= 1 {
            return 0.0;
        }
        let successes = self.successes as f64;
        let mean = self.avg_latency_ms();
        let mean_square = self.total_latency_square_ms as f64 / successes;
        (mean_square - mean * mean).max(0.0)
    }

    /// Fitness score: higher is better.
    ///
    /// `success_rate * FITNESS_SUCCESS_WEIGHT - avg_latency.min(FITNESS_LATENCY_CAP_MS) * FITNESS_LATENCY_PENALTY_PER_MS`
    ///
    /// The success-rate term dominates (range 0..1000) so reliability always
    /// wins over speed. The latency penalty caps at -100 (~10% of the success
    /// range), which is large enough to differentiate fast vs slow strategies
    /// when success rates are comparable.
    pub fn fitness(&self) -> f64 {
        if self.attempts == 0 {
            return 0.0;
        }
        let success_rate = self.successes as f64 / self.attempts as f64;
        let avg_latency = self.avg_latency_ms();
        let failure_rate = 1.0 - success_rate;
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

fn is_detectability_failure(class: FailureClass) -> bool {
    matches!(
        class,
        FailureClass::TlsAlert | FailureClass::HttpBlockpage | FailureClass::Redirect | FailureClass::ConnectionFreeze
    )
}

fn combo_energy_cost(combo: &StrategyCombo) -> f64 {
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

fn combo_fitness(combo: &StrategyCombo, stats: &ComboStats) -> f64 {
    stats.fitness() - combo_energy_cost(combo) * FITNESS_ENERGY_WEIGHT
}

// ---------------------------------------------------------------------------
// Combo pool (fixed set of common combinations)
// ---------------------------------------------------------------------------

/// Pre-defined pool entry covering all 7 adaptive dimensions.
struct PoolEntry {
    split_offset_base: Option<OffsetBase>,
    tls_record_offset_base: Option<OffsetBase>,
    tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    quic_fake_profile: Option<QuicFakeProfile>,
    fake_ttl: Option<u8>,
    entropy_mode: Option<EntropyMode>,
}

impl PoolEntry {
    const fn new() -> Self {
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
const COMBO_POOL: &[PoolEntry] = &[
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

fn combo_from_pool(index: usize) -> StrategyCombo {
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

// ---------------------------------------------------------------------------
// StrategyEvolver
// ---------------------------------------------------------------------------

pub struct StrategyEvolver {
    combos: HashMap<StrategyCombo, ComboStats>,
    contexts: HashMap<LearningContext, ContextBanditState>,
    current_experiment: Option<StrategyCombo>,
    current_experiment_context: Option<LearningContext>,
    current_experiment_family: Option<StrategyFamily>,
    current_learning_context: LearningContext,
    explore_epsilon: f64,
    pub max_combos: usize,
    enabled: bool,
    rng_state: u64,
}

impl StrategyEvolver {
    pub fn new(enabled: bool, epsilon: f64) -> Self {
        Self {
            combos: HashMap::new(),
            contexts: HashMap::new(),
            current_experiment: None,
            current_experiment_context: None,
            current_experiment_family: None,
            current_learning_context: LearningContext::default(),
            explore_epsilon: epsilon,
            max_combos: 64,
            enabled,
            rng_state: now_millis()
                .wrapping_add(1)
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(std::process::id() as u64),
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn epsilon(&self) -> f64 {
        self.explore_epsilon
    }

    pub fn set_learning_context(&mut self, context: LearningContext) {
        if self.current_learning_context == context {
            return;
        }
        self.current_learning_context = context;
        self.current_experiment = None;
        self.current_experiment_context = None;
        self.current_experiment_family = None;
    }

    pub fn current_learning_context(&self) -> &LearningContext {
        &self.current_learning_context
    }

    /// Returns the currently pending experiment hints without mutating state.
    pub fn peek_hints(&self) -> Option<AdaptivePlannerHints> {
        if !self.enabled {
            return None;
        }
        self.current_experiment.as_ref().map(StrategyCombo::to_hints)
    }

    /// Returns adaptive hints if the evolver wants to override the default planner.
    ///
    /// When `Some` is returned, the caller should use these hints **instead of**
    /// per-flow adaptive hints from [`crate::adaptive_tuning::AdaptivePlannerResolver`].
    /// Called before each outbound send.
    pub fn suggest_hints(&mut self) -> Option<AdaptivePlannerHints> {
        if !self.enabled {
            return None;
        }

        // If we already have an outstanding experiment, return its hints.
        if let Some(ref combo) = self.current_experiment {
            let hints = combo.to_hints();
            tracing::debug!(
                combo = ?combo,
                hints = ?hints,
                "strategy evolution reused pending combo, overriding per-flow adaptive tuning",
            );
            return Some(hints);
        }

        let combo = self.select_next_combo();
        let hints = combo.to_hints();
        tracing::debug!(
            combo = ?combo,
            hints = ?hints,
            context = ?self.current_learning_context,
            "strategy evolution selected combo, overriding per-flow adaptive tuning",
        );
        self.current_experiment_context = Some(self.current_learning_context.clone());
        self.current_experiment_family = Some(combo.family());
        self.current_experiment = Some(combo);
        Some(hints)
    }

    /// Record successful connection with observed latency.
    pub fn record_success(&mut self, latency_ms: u64) {
        let Some(combo) = self.current_experiment.take() else {
            return;
        };
        let context = self.current_experiment_context.take().unwrap_or_else(|| self.current_learning_context.clone());
        let family = self.current_experiment_family.take().unwrap_or_else(|| combo.family());
        tracing::debug!(combo = ?combo, latency_ms, "strategy evolution recorded success");
        self.evict_if_needed(&combo);
        let stats = self.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
        stats.record_attempt(true, latency_ms, None, now_millis());
        let last_attempt_ms = stats.last_attempt_ms;
        self.record_contextual_feedback(&context, family, &combo, true, latency_ms, None, last_attempt_ms);
        tracing::debug!(
            combos_tested = self.combos_tested(),
            best_fitness = format_args!("{:.1}", self.best_fitness()),
            "strategy evolution progress",
        );
    }

    /// Record failed connection with failure class.
    ///
    /// `FailureClass::CapabilitySkipped` and
    /// `FailureClass::StrategyExecutionFailure` are no-ops: the tactic was
    /// never emitted successfully, so they must not affect arm counts or
    /// reward estimates.
    pub fn record_failure(&mut self, class: FailureClass) {
        if matches!(class, FailureClass::CapabilitySkipped | FailureClass::StrategyExecutionFailure) {
            // Discard the pending experiment without touching bandit state.
            // The run was skipped before any packet was sent, so it carries
            // no signal about the strategy's quality.
            self.current_experiment = None;
            self.current_experiment_context = None;
            self.current_experiment_family = None;
            return;
        }
        let Some(combo) = self.current_experiment.take() else {
            return;
        };
        let context = self.current_experiment_context.take().unwrap_or_else(|| self.current_learning_context.clone());
        let family = self.current_experiment_family.take().unwrap_or_else(|| combo.family());
        tracing::debug!(combo = ?combo, class = class.as_str(), "strategy evolution recorded failure");
        self.evict_if_needed(&combo);
        let stats = self.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
        stats.record_attempt(false, FITNESS_LATENCY_CAP_MS as u64, Some(class), now_millis());
        let last_attempt_ms = stats.last_attempt_ms;
        self.record_contextual_feedback(
            &context,
            family,
            &combo,
            false,
            FITNESS_LATENCY_CAP_MS as u64,
            Some(class),
            last_attempt_ms,
        );
    }

    /// Returns the best-performing combo found so far.
    pub fn best_combo(&self) -> Option<(&StrategyCombo, &ComboStats)> {
        self.combos.iter().max_by(|a, b| {
            combo_fitness(a.0, a.1).partial_cmp(&combo_fitness(b.0, b.1)).unwrap_or(std::cmp::Ordering::Equal)
        })
    }

    /// Number of unique combos tested.
    pub fn combos_tested(&self) -> usize {
        self.combos.len()
    }

    /// Best fitness score.
    pub fn best_fitness(&self) -> f64 {
        self.best_combo().map_or(0.0, |(combo, stats)| combo_fitness(combo, stats))
    }

    // -- internal -----------------------------------------------------------

    fn lcg_next(&mut self) -> u32 {
        self.rng_state = self.rng_state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        (self.rng_state >> 33) as u32
    }

    /// Returns a float in [0.0, 1.0).
    fn lcg_f64(&mut self) -> f64 {
        self.lcg_next() as f64 / (u32::MAX as f64 + 1.0)
    }

    fn generate_random_combo(&mut self) -> StrategyCombo {
        let idx = self.lcg_next() as usize;
        combo_from_pool(idx)
    }

    fn generate_random_combo_for_bucket(&mut self, bucket: LearningTargetBucket) -> StrategyCombo {
        let matching: Vec<usize> =
            (0..COMBO_POOL.len()).filter(|idx| combo_matches_bucket(&combo_from_pool(*idx), bucket)).collect();
        if matching.is_empty() {
            return self.generate_random_combo();
        }
        let idx = matching[self.lcg_next() as usize % matching.len()];
        combo_from_pool(idx)
    }

    fn select_next_combo(&mut self) -> StrategyCombo {
        let context = self.current_learning_context.clone();
        let bucket = context.target_bucket;
        let bucket_piloted = self.contexts.get(&context).is_some_and(|state| state.piloted_buckets.contains(&bucket));

        if self.combos.is_empty() {
            return pilot_combo_for_bucket(bucket);
        }
        if !bucket_piloted {
            return pilot_combo_for_bucket(bucket);
        }
        if self.lcg_f64() < self.explore_epsilon {
            return self.generate_random_combo_for_bucket(bucket);
        }
        let Some(state) = self.contexts.get(&context) else {
            return self.generate_random_combo_for_bucket(bucket);
        };
        if let Some(niche) = state.niche_winners.get(&bucket) {
            return niche.clone();
        }
        let Some(family) = self.select_next_family(state, bucket) else {
            return self.generate_random_combo_for_bucket(bucket);
        };
        self.best_context_combo_for_family(state, family)
            .unwrap_or_else(|| self.generate_random_combo_for_bucket(bucket))
    }

    fn evict_if_needed(&mut self, keep: &StrategyCombo) {
        if self.combos.len() < self.max_combos {
            return;
        }
        // Find the combo with the lowest fitness, excluding `keep`.
        let worst = self
            .combos
            .iter()
            .filter(|(k, _)| *k != keep)
            .min_by(|a, b| {
                combo_fitness(a.0, a.1).partial_cmp(&combo_fitness(b.0, b.1)).unwrap_or(std::cmp::Ordering::Equal)
            })
            .map(|(k, _)| k.clone());
        if let Some(w) = worst {
            self.combos.remove(&w);
        }
    }

    fn select_next_family(&self, state: &ContextBanditState, bucket: LearningTargetBucket) -> Option<StrategyFamily> {
        if state.families.is_empty() {
            return Some(default_family_for_bucket(bucket));
        }
        let total_attempts: u32 = state.families.values().map(|stats| stats.attempts.max(1)).sum();
        let ln_total = (total_attempts as f64).ln().max(1.0);
        state
            .families
            .iter()
            .map(|(family, stats)| {
                let score = if stats.attempts == 0 {
                    f64::MAX
                } else {
                    (stats.total_reward / stats.attempts as f64) + 1.41 * (ln_total / stats.attempts as f64).sqrt()
                };
                (*family, score)
            })
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(family, _)| family)
    }

    fn best_context_combo_for_family(
        &self,
        state: &ContextBanditState,
        family: StrategyFamily,
    ) -> Option<StrategyCombo> {
        let total_attempts: u32 = state.combos.values().map(|stats| stats.attempts.max(1)).sum();
        let ln_total = (total_attempts as f64).ln().max(1.0);
        state
            .combos
            .iter()
            .filter(|(combo, _)| combo.family() == family || family == StrategyFamily::Mixed)
            .map(|(combo, stats)| {
                let score = if stats.attempts == 0 {
                    f64::MAX
                } else {
                    combo_fitness(combo, stats) + 1.41 * (ln_total / stats.attempts as f64).sqrt()
                };
                (combo.clone(), score)
            })
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(combo, _)| combo)
    }

    fn record_contextual_feedback(
        &mut self,
        context: &LearningContext,
        family: StrategyFamily,
        combo: &StrategyCombo,
        success: bool,
        latency_ms: u64,
        failure_class: Option<FailureClass>,
        last_attempt_ms: u64,
    ) {
        let state = self.contexts.entry(context.clone()).or_default();
        state.piloted_buckets.insert(context.target_bucket);
        evict_context_if_needed(state, combo, self.max_combos);
        let stats = state.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
        stats.record_attempt(success, latency_ms, failure_class, last_attempt_ms);
        let updated_fitness = combo_fitness(combo, stats);
        let family_stats = state.families.entry(family).or_default();
        family_stats.attempts += 1;
        family_stats.total_reward += updated_fitness;
        let _ = stats;

        let niche_entry = state.niche_winners.entry(context.target_bucket).or_insert_with(|| combo.clone());
        let niche_fitness = state.combos.get(niche_entry).map_or(f64::MIN, |stats| combo_fitness(niche_entry, stats));
        if updated_fitness >= niche_fitness {
            *niche_entry = combo.clone();
        }
    }
}

fn combo_matches_bucket(combo: &StrategyCombo, bucket: LearningTargetBucket) -> bool {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => combo.family() == StrategyFamily::Baseline,
        LearningTargetBucket::Tls => {
            combo.split_offset_base.is_some()
                || combo.tls_record_offset_base.is_some()
                || combo.tlsrandrec_profile.is_some()
        }
        LearningTargetBucket::Ech => {
            combo.split_offset_base == Some(OffsetBase::EchExt)
                || combo.tls_record_offset_base == Some(OffsetBase::EchExt)
        }
        LearningTargetBucket::Quic => combo.quic_fake_profile.is_some() || combo.udp_burst_profile.is_some(),
    }
}

fn default_family_for_bucket(bucket: LearningTargetBucket) -> StrategyFamily {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => StrategyFamily::Baseline,
        LearningTargetBucket::Tls => StrategyFamily::SplitOffset,
        LearningTargetBucket::Ech => StrategyFamily::TlsRecordOffset,
        LearningTargetBucket::Quic => StrategyFamily::QuicFake,
    }
}

fn pilot_combo_for_bucket(bucket: LearningTargetBucket) -> StrategyCombo {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => StrategyCombo::default_combo(),
        LearningTargetBucket::Tls => {
            StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() }
        }
        LearningTargetBucket::Ech => {
            StrategyCombo { split_offset_base: Some(OffsetBase::EchExt), ..StrategyCombo::default_combo() }
        }
        LearningTargetBucket::Quic => {
            StrategyCombo { quic_fake_profile: Some(QuicFakeProfile::CompatDefault), ..StrategyCombo::default_combo() }
        }
    }
}

fn evict_context_if_needed(state: &mut ContextBanditState, keep: &StrategyCombo, max_combos: usize) {
    if state.combos.len() < max_combos {
        return;
    }
    let worst = state
        .combos
        .iter()
        .filter(|(combo, _)| *combo != keep)
        .min_by(|a, b| {
            combo_fitness(a.0, a.1).partial_cmp(&combo_fitness(b.0, b.1)).unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|(combo, _)| combo.clone());
    if let Some(worst_combo) = worst {
        state.combos.remove(&worst_combo);
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evolver_disabled_returns_none() {
        let mut e = StrategyEvolver::new(false, 0.1);
        assert!(e.suggest_hints().is_none());
    }

    #[test]
    fn evolver_selects_best_combo_after_training() {
        let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation

        // Combo A: AutoHost offset, no TTL
        let combo_a = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
        // Combo B: MidSld offset, no TTL
        let combo_b = StrategyCombo { split_offset_base: Some(OffsetBase::MidSld), ..StrategyCombo::default_combo() };

        // Manually insert combo A: 2 successes, 1 fail (3 attempts)
        e.combos.insert(
            combo_a.clone(),
            ComboStats { attempts: 3, successes: 2, total_latency_ms: 200, ..ComboStats::new() },
        );

        // Manually insert combo B: 3 successes (3 attempts)
        e.combos.insert(
            combo_b.clone(),
            ComboStats { attempts: 3, successes: 3, total_latency_ms: 300, ..ComboStats::new() },
        );

        let (best, _) = e.best_combo().expect("should have a best combo");
        assert_eq!(*best, combo_b, "combo B with 100% success rate should be best");
    }

    #[test]
    fn evolver_explores_with_epsilon_one() {
        let mut e = StrategyEvolver::new(true, 1.0); // pure exploration
        let context = LearningContext {
            target_bucket: LearningTargetBucket::Tls,
            transport: LearningTransportKind::Tcp,
            ..LearningContext::default()
        };
        e.set_learning_context(context.clone());

        // Seed some combos so select_next_combo hits the exploration path.
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.contexts.insert(
            context,
            ContextBanditState {
                piloted_buckets: [LearningTargetBucket::Tls].into_iter().collect(),
                ..ContextBanditState::default()
            },
        );

        // Collect several suggestions -- with epsilon=1.0 they are randomly generated
        // so we expect at least 2 distinct combos over many draws.
        let mut seen = std::collections::HashSet::new();
        for _ in 0..50 {
            e.suggest_hints();
            if let Some(ref combo) = e.current_experiment {
                seen.insert(combo.clone());
            }
            // Clear experiment to allow next suggestion.
            e.current_experiment = None;
        }
        assert!(seen.len() >= 2, "pure exploration should produce multiple distinct combos, got {}", seen.len());
    }

    #[test]
    fn evolver_records_failure_class() {
        let mut e = StrategyEvolver::new(true, 1.0);
        // Seed a combo so suggest_hints can proceed.
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.suggest_hints();

        let experiment = e.current_experiment.clone().expect("should have an experiment");
        e.record_failure(FailureClass::TcpReset);

        let stats = e.combos.get(&experiment).expect("stats should exist");
        assert_eq!(stats.last_failure_class, Some(FailureClass::TcpReset));
        assert_eq!(stats.attempts, 1);
        assert_eq!(stats.successes, 0);
    }

    #[test]
    fn strategy_execution_failure_does_not_penalize_learning_state() {
        let mut e = StrategyEvolver::new(true, 1.0);
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.set_learning_context(LearningContext {
            network_identity: Some("wifi-a".to_string()),
            target_bucket: LearningTargetBucket::Tls,
            transport: LearningTransportKind::Tcp,
            ..LearningContext::default()
        });
        e.suggest_hints();

        let experiment = e.current_experiment.clone().expect("should have an experiment");
        e.record_failure(FailureClass::StrategyExecutionFailure);

        assert!(!e.combos.contains_key(&experiment), "emitter failures must not create or update combo stats");
        assert!(
            e.contexts.get(e.current_learning_context()).is_none(),
            "emitter failures must not create contextual state"
        );
        assert!(e.current_experiment.is_none(), "pending experiment should still be cleared");
    }

    #[test]
    fn evolver_respects_max_combos_limit() {
        let mut e = StrategyEvolver::new(true, 1.0);
        e.max_combos = 4;

        // Insert 5 distinct combos via direct manipulation.
        for i in 0..5 {
            let combo = combo_from_pool(i);
            e.current_experiment = Some(combo);
            e.record_success(100);
        }

        assert!(e.combos.len() <= 4, "should respect max_combos limit, got {}", e.combos.len());
    }

    #[test]
    fn evolver_clears_experiment_after_feedback() {
        let mut e = StrategyEvolver::new(true, 1.0);
        // Seed a combo.
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.suggest_hints(); // sets current_experiment
        assert!(e.current_experiment.is_some());

        e.record_success(100);
        assert!(e.current_experiment.is_none(), "experiment should be cleared after feedback");

        // Next suggest_hints should pick a new combo.
        e.suggest_hints();
        assert!(e.current_experiment.is_some());
    }

    #[test]
    fn fitness_prefers_higher_success_rate() {
        let a = ComboStats { attempts: 10, successes: 8, total_latency_ms: 800, ..ComboStats::new() };
        let b = ComboStats { attempts: 10, successes: 3, total_latency_ms: 300, ..ComboStats::new() };
        assert!(a.fitness() > b.fitness(), "a.fitness={} should be > b.fitness={}", a.fitness(), b.fitness());
    }

    #[test]
    fn fitness_penalizes_high_latency() {
        let fast = ComboStats { attempts: 10, successes: 10, total_latency_ms: 500, ..ComboStats::new() };
        let slow = ComboStats { attempts: 10, successes: 10, total_latency_ms: 50_000, ..ComboStats::new() };
        assert!(fast.fitness() > slow.fitness());
    }

    #[test]
    fn zero_attempts_gives_zero_fitness() {
        let stats = ComboStats::new();
        assert!((stats.fitness() - 0.0).abs() < f64::EPSILON);
    }

    #[test]
    fn combo_hash_is_consistent() {
        use std::collections::hash_map::DefaultHasher;

        let combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::AutoHost),
            fake_ttl: Some(8),
            ..StrategyCombo::default_combo()
        };

        let hash1 = {
            let mut h = DefaultHasher::new();
            combo.hash(&mut h);
            h.finish()
        };
        let hash2 = {
            let mut h = DefaultHasher::new();
            combo.hash(&mut h);
            h.finish()
        };
        assert_eq!(hash1, hash2);
    }

    #[test]
    fn lcg_produces_deterministic_sequence() {
        let mut e1 = StrategyEvolver::new(true, 0.5);
        let mut e2 = StrategyEvolver::new(true, 0.5);
        // Force same seed for determinism.
        e1.rng_state = 42;
        e2.rng_state = 42;

        let seq1: Vec<u32> = (0..10).map(|_| e1.lcg_next()).collect();
        let seq2: Vec<u32> = (0..10).map(|_| e2.lcg_next()).collect();
        assert_eq!(seq1, seq2);
    }

    // ---- Edge case tests ----

    #[test]
    fn record_success_without_experiment_is_noop() {
        let mut e = StrategyEvolver::new(true, 0.0);
        // No current_experiment set, record_success should silently do nothing.
        e.record_success(100);
        assert_eq!(e.combos_tested(), 0);
    }

    #[test]
    fn record_failure_without_experiment_is_noop() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.record_failure(FailureClass::TcpReset);
        assert_eq!(e.combos_tested(), 0);
    }

    #[test]
    fn double_record_success_only_counts_first() {
        let mut e = StrategyEvolver::new(true, 1.0);
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.suggest_hints();
        assert!(e.current_experiment.is_some());

        e.record_success(50);
        // Second record should be a no-op (current_experiment already taken)
        e.record_success(50);

        let total_attempts: u32 = e.combos.values().map(|s| s.attempts).sum();
        assert_eq!(total_attempts, 1, "only one attempt should be recorded");
    }

    #[test]
    fn suggest_hints_returns_same_combo_while_experiment_pending() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());

        let first = e.suggest_hints().expect("first suggestion");
        let second = e.suggest_hints().expect("second suggestion");
        // Should return same hints since experiment is still outstanding
        assert_eq!(first, second);
    }

    #[test]
    fn peek_hints_returns_none_when_disabled() {
        let e = StrategyEvolver::new(false, 0.0);
        assert!(e.peek_hints().is_none());
    }

    #[test]
    fn peek_hints_returns_pending_experiment_without_mutation() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        let suggested = e.suggest_hints().expect("suggested hints");

        assert_eq!(e.peek_hints(), Some(suggested));
        assert!(e.current_experiment.is_some(), "peek should not clear the pending experiment");
    }

    #[test]
    fn best_combo_empty_returns_none() {
        let e = StrategyEvolver::new(true, 0.0);
        assert!(e.best_combo().is_none());
    }

    #[test]
    fn best_fitness_empty_returns_zero() {
        let e = StrategyEvolver::new(true, 0.0);
        assert!((e.best_fitness() - 0.0).abs() < f64::EPSILON);
    }

    #[test]
    fn combos_tested_counts_unique_combos() {
        let mut e = StrategyEvolver::new(true, 0.0);
        let combo_a = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
        let combo_b = StrategyCombo { split_offset_base: Some(OffsetBase::MidSld), ..StrategyCombo::default_combo() };
        e.combos.insert(combo_a, ComboStats::new());
        e.combos.insert(combo_b, ComboStats::new());
        assert_eq!(e.combos_tested(), 2);
    }

    #[test]
    fn evict_preserves_keep_combo() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.max_combos = 2;

        let keep = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
        let other = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };

        // Both have same (zero) fitness
        e.combos.insert(keep.clone(), ComboStats::new());
        e.combos.insert(other.clone(), ComboStats::new());

        // Evict should remove `other` (or any combo != keep), not `keep`
        e.evict_if_needed(&keep);
        assert!(e.combos.contains_key(&keep), "keep combo should survive eviction");
        assert_eq!(e.combos.len(), 1);
    }

    #[test]
    fn evict_removes_lowest_fitness() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.max_combos = 2;

        let good = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
        let bad = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };
        let new_combo = StrategyCombo { fake_ttl: Some(10), ..StrategyCombo::default_combo() };

        e.combos.insert(
            good.clone(),
            ComboStats { attempts: 10, successes: 9, total_latency_ms: 100, ..ComboStats::new() },
        );
        e.combos.insert(
            bad.clone(),
            ComboStats { attempts: 10, successes: 1, total_latency_ms: 5000, ..ComboStats::new() },
        );

        e.evict_if_needed(&new_combo);
        assert!(e.combos.contains_key(&good), "good combo should survive");
        assert!(!e.combos.contains_key(&bad), "bad combo should be evicted");
    }

    #[test]
    fn fitness_with_zero_successes_penalizes_latency() {
        let stats = ComboStats { attempts: 5, successes: 0, total_latency_ms: 0, ..ComboStats::new() };
        // 0% success rate, avg_latency defaults to FITNESS_LATENCY_CAP_MS
        // fitness = 0.0 * 1000 - 5000.0 * 0.02 = -100.0
        assert!(stats.fitness() < 0.0, "0% success should give negative fitness, got {}", stats.fitness());
    }

    #[test]
    fn combo_pool_is_non_empty_and_wraps() {
        assert!(!COMBO_POOL.is_empty());
        let c1 = combo_from_pool(0);
        let c2 = combo_from_pool(COMBO_POOL.len());
        assert_eq!(c1, c2, "pool should wrap around");
    }

    #[test]
    fn combo_pool_entries_are_unique() {
        let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
        let unique: std::collections::HashSet<_> = combos.iter().collect();
        assert_eq!(unique.len(), COMBO_POOL.len(), "all pool entries should be unique");
    }

    #[test]
    fn select_next_combo_returns_default_when_no_history() {
        let mut e = StrategyEvolver::new(true, 0.0);
        assert!(e.combos.is_empty());
        let combo = e.select_next_combo();
        assert_eq!(combo, StrategyCombo::default_combo());
    }

    #[test]
    fn ucb1_prefers_untried_combos() {
        let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation
        e.rng_state = 42;
        let context = LearningContext { target_bucket: LearningTargetBucket::Generic, ..LearningContext::default() };
        e.set_learning_context(context.clone());

        let tried = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
        let untried = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };
        let tried_stats = ComboStats { attempts: 10, successes: 5, total_latency_ms: 500, ..ComboStats::new() };
        let untried_stats = ComboStats::new();

        e.combos.insert(tried.clone(), tried_stats.clone());
        e.combos.insert(untried.clone(), untried_stats.clone()); // 0 attempts => UCB1 = MAX
        e.contexts.insert(
            context,
            ContextBanditState {
                combos: [(tried.clone(), tried_stats), (untried.clone(), untried_stats)].into_iter().collect(),
                families: [(StrategyFamily::FakeTtl, FamilyStats { attempts: 10, total_reward: 100.0 })]
                    .into_iter()
                    .collect(),
                piloted_buckets: [LearningTargetBucket::Generic].into_iter().collect(),
                ..ContextBanditState::default()
            },
        );

        let selected = e.select_next_combo();
        assert_eq!(selected, untried, "UCB1 should prefer untried combo (score = f64::MAX)");
    }

    #[test]
    fn to_hints_maps_all_fields() {
        let combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::AutoHost),
            tls_record_offset_base: Some(OffsetBase::EndHost),
            tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
            udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
            quic_fake_profile: Some(QuicFakeProfile::RealisticInitial),
            fake_ttl: Some(8),
            entropy_mode: Some(EntropyMode::Shannon),
        };
        let hints = combo.to_hints();
        assert_eq!(hints.split_offset_base, Some(OffsetBase::AutoHost));
        assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::EndHost));
        assert_eq!(hints.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Tight));
        assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
        assert_eq!(hints.entropy_mode, Some(EntropyMode::Shannon));
    }

    #[test]
    fn to_hints_maps_ech_ext_offsets() {
        let combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::EchExt),
            tls_record_offset_base: Some(OffsetBase::EchExt),
            ..StrategyCombo::default_combo()
        };

        let hints = combo.to_hints();

        assert_eq!(hints.split_offset_base, Some(OffsetBase::EchExt));
        assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::EchExt));
    }

    #[test]
    fn ech_ext_offset_base_hash_is_distinct_from_sni_ext() {
        use std::collections::hash_map::DefaultHasher;

        let hash_of = |combo: &StrategyCombo| -> u64 {
            let mut hasher = DefaultHasher::new();
            combo.hash(&mut hasher);
            hasher.finish()
        };
        let ech_combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::EchExt),
            tls_record_offset_base: Some(OffsetBase::EchExt),
            ..StrategyCombo::default_combo()
        };
        let sni_combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::SniExt),
            tls_record_offset_base: Some(OffsetBase::SniExt),
            ..StrategyCombo::default_combo()
        };

        assert_ne!(hash_of(&ech_combo), hash_of(&sni_combo));
    }

    #[test]
    fn entropy_mode_produces_distinct_hashes() {
        use std::collections::hash_map::DefaultHasher;

        let combo_none = StrategyCombo::default_combo();
        let combo_shannon =
            StrategyCombo { entropy_mode: Some(EntropyMode::Shannon), ..StrategyCombo::default_combo() };
        let combo_popcount =
            StrategyCombo { entropy_mode: Some(EntropyMode::Popcount), ..StrategyCombo::default_combo() };
        let combo_combined =
            StrategyCombo { entropy_mode: Some(EntropyMode::Combined), ..StrategyCombo::default_combo() };

        let hash_of = |combo: &StrategyCombo| -> u64 {
            let mut h = DefaultHasher::new();
            combo.hash(&mut h);
            h.finish()
        };

        let h_none = hash_of(&combo_none);
        let h_shannon = hash_of(&combo_shannon);
        let h_popcount = hash_of(&combo_popcount);
        let h_combined = hash_of(&combo_combined);

        // All four should have distinct hashes
        let hashes = [h_none, h_shannon, h_popcount, h_combined];
        for i in 0..hashes.len() {
            for j in (i + 1)..hashes.len() {
                assert_ne!(hashes[i], hashes[j], "hash collision between variant {i} and {j}");
            }
        }
    }

    #[test]
    fn combo_pool_contains_shannon_variants() {
        let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
        let shannon_count = combos.iter().filter(|c| matches!(c.entropy_mode, Some(EntropyMode::Shannon))).count();
        let combined_count = combos.iter().filter(|c| matches!(c.entropy_mode, Some(EntropyMode::Combined))).count();
        assert!(shannon_count >= 1, "pool should have at least one Shannon combo");
        assert!(combined_count >= 1, "pool should have at least one Combined combo");
    }

    #[test]
    fn combo_from_pool_includes_entropy_mode() {
        // Find a Shannon variant in the pool
        let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
        let shannon_idx = combos
            .iter()
            .position(|c| matches!(c.entropy_mode, Some(EntropyMode::Shannon)))
            .expect("pool should contain a Shannon variant");
        let combo = combo_from_pool(shannon_idx);
        assert_eq!(combo.entropy_mode, Some(EntropyMode::Shannon));
    }

    #[test]
    fn combo_pool_covers_all_dimensions() {
        let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
        assert!(
            combos.iter().any(|c| c.tls_record_offset_base.is_some()),
            "pool should have at least one entry with tls_record_offset_base"
        );
        assert!(
            combos.iter().any(|c| c.tlsrandrec_profile.is_some()),
            "pool should have at least one entry with tlsrandrec_profile"
        );
        assert!(
            combos.iter().any(|c| c.udp_burst_profile.is_some()),
            "pool should have at least one entry with udp_burst_profile"
        );
        assert!(
            combos.iter().any(|c| c.quic_fake_profile.is_some()),
            "pool should have at least one entry with quic_fake_profile"
        );
    }

    #[test]
    fn default_combo_has_no_entropy_mode() {
        let combo = StrategyCombo::default_combo();
        assert_eq!(combo.entropy_mode, None);
    }

    #[test]
    fn to_hints_maps_none_entropy_mode() {
        let combo = StrategyCombo::default_combo();
        let hints = combo.to_hints();
        assert_eq!(hints.entropy_mode, None);
    }

    #[test]
    fn evolver_can_track_entropy_combos() {
        let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation

        let combo_no_entropy = StrategyCombo::default_combo();
        let combo_with_entropy =
            StrategyCombo { entropy_mode: Some(EntropyMode::Shannon), ..StrategyCombo::default_combo() };

        // combo without entropy: 50% success rate
        e.combos.insert(
            combo_no_entropy.clone(),
            ComboStats { attempts: 10, successes: 5, total_latency_ms: 500, ..ComboStats::new() },
        );
        // combo with Shannon entropy: 90% success rate
        e.combos.insert(
            combo_with_entropy.clone(),
            ComboStats { attempts: 10, successes: 9, total_latency_ms: 900, ..ComboStats::new() },
        );

        let (best, _) = e.best_combo().expect("should have a best combo");
        assert_eq!(best.entropy_mode, Some(EntropyMode::Shannon), "Shannon combo should be best");
    }

    // ---- Slice 2.6: capability-skipped runs must not update bandit arms ----

    #[test]
    fn capability_skipped_does_not_update_bandit_arms() {
        // Session A: 1 successful run only.
        let mut session_a = StrategyEvolver::new(true, 0.0);
        session_a.rng_state = 99;
        let combo = StrategyCombo::default_combo();
        session_a.combos.insert(combo.clone(), ComboStats::new());

        session_a.current_experiment = Some(combo.clone());
        session_a.record_success(50);

        let stats_a = session_a.combos.get(&combo).expect("stats_a must exist");

        // Session B: 3 capability-skipped runs, then the same 1 successful run.
        let mut session_b = StrategyEvolver::new(true, 0.0);
        session_b.rng_state = 99;
        session_b.combos.insert(combo.clone(), ComboStats::new());

        for _ in 0..3 {
            session_b.current_experiment = Some(combo.clone());
            session_b.record_failure(FailureClass::CapabilitySkipped);
        }

        session_b.current_experiment = Some(combo.clone());
        session_b.record_success(50);

        let stats_b = session_b.combos.get(&combo).expect("stats_b must exist");

        assert_eq!(
            stats_a.attempts, stats_b.attempts,
            "capability-skipped runs must not increment attempt count \
             (session_a={}, session_b={})",
            stats_a.attempts, stats_b.attempts,
        );
        assert_eq!(stats_a.successes, stats_b.successes, "capability-skipped runs must not alter success count",);
        assert!(
            (stats_a.fitness() - stats_b.fitness()).abs() < f64::EPSILON,
            "capability-skipped runs must not change fitness: \
             session_a={:.4}, session_b={:.4}",
            stats_a.fitness(),
            stats_b.fitness(),
        );
    }

    #[test]
    fn learning_context_change_clears_pending_experiment() {
        let mut evolver = StrategyEvolver::new(true, 0.0);
        evolver.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        let _ = evolver.suggest_hints().expect("pending hints");
        assert!(evolver.current_experiment.is_some());

        evolver.set_learning_context(LearningContext {
            network_identity: Some("wifi:office".to_string()),
            target_bucket: LearningTargetBucket::Quic,
            transport: LearningTransportKind::UdpQuic,
            alpn_class: LearningAlpnClass::H3,
            ech_capable: false,
            resolver_health: ResolverHealthClass::Healthy,
            rooted: false,
            capability_context: CapabilityContext::Full,
            ..LearningContext::default()
        });

        assert!(evolver.current_experiment.is_none());
    }

    #[test]
    fn contextual_bandit_keeps_niche_winner_per_bucket() {
        let mut evolver = StrategyEvolver::new(true, 0.0);
        let tls_combo =
            StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
        let quic_combo =
            StrategyCombo { quic_fake_profile: Some(QuicFakeProfile::CompatDefault), ..StrategyCombo::default_combo() };

        evolver.set_learning_context(LearningContext {
            target_bucket: LearningTargetBucket::Tls,
            transport: LearningTransportKind::Tcp,
            alpn_class: LearningAlpnClass::H2Http11,
            ..LearningContext::default()
        });
        evolver.current_experiment = Some(tls_combo.clone());
        evolver.current_experiment_context = Some(evolver.current_learning_context.clone());
        evolver.current_experiment_family = Some(tls_combo.family());
        evolver.record_success(50);

        evolver.set_learning_context(LearningContext {
            target_bucket: LearningTargetBucket::Quic,
            transport: LearningTransportKind::UdpQuic,
            alpn_class: LearningAlpnClass::H3,
            ..LearningContext::default()
        });
        evolver.current_experiment = Some(quic_combo.clone());
        evolver.current_experiment_context = Some(evolver.current_learning_context.clone());
        evolver.current_experiment_family = Some(quic_combo.family());
        evolver.record_success(50);

        let tls_state = evolver
            .contexts
            .get(&LearningContext {
                target_bucket: LearningTargetBucket::Tls,
                transport: LearningTransportKind::Tcp,
                alpn_class: LearningAlpnClass::H2Http11,
                ..LearningContext::default()
            })
            .expect("tls context");
        assert_eq!(tls_state.niche_winners.get(&LearningTargetBucket::Tls), Some(&tls_combo));

        let quic_state = evolver
            .contexts
            .get(&LearningContext {
                target_bucket: LearningTargetBucket::Quic,
                transport: LearningTransportKind::UdpQuic,
                alpn_class: LearningAlpnClass::H3,
                ..LearningContext::default()
            })
            .expect("quic context");
        assert_eq!(quic_state.niche_winners.get(&LearningTargetBucket::Quic), Some(&quic_combo));
    }

    #[test]
    fn ech_bucket_pilot_prefers_ech_offset_combo() {
        let mut evolver = StrategyEvolver::new(true, 0.0);
        evolver.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        evolver.set_learning_context(LearningContext {
            target_bucket: LearningTargetBucket::Ech,
            transport: LearningTransportKind::Tcp,
            alpn_class: LearningAlpnClass::H2Http11,
            ech_capable: true,
            ..LearningContext::default()
        });

        let combo = evolver.select_next_combo();
        assert_eq!(combo.split_offset_base, Some(OffsetBase::EchExt));
    }
}
