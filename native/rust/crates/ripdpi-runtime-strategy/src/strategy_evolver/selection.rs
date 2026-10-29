use ripdpi_config::{OffsetBase, QuicFakeProfile};

use super::StrategyEvolver;
use super::shared_priors::canonical_combo_hash;
use super::thompson_sampling::BetaParams;
use super::types::{
    COMBO_POOL, ComboStats, ContextBanditState, LearningTargetBucket, StrategyCombo, StrategyFamily, combo_fitness_at,
    combo_from_pool,
};

impl StrategyEvolver {
    /// Let verified shared priors outrank the exploit choice for an arm with
    /// little local data. Each arm scores its blended success mean:
    /// `(w * prior_mean + local_successes) / (w + local_attempts)` with
    /// `w = min(alpha + beta, SHARED_PRIOR_WEIGHT_CAP)`. The cap makes the
    /// prior worth at most [`SHARED_PRIOR_WEIGHT_CAP`] local observations, so
    /// field data takes over as it accumulates. `base` is replaced only by a
    /// strictly better prior-backed arm; with no priors loaded it is returned
    /// untouched.
    fn apply_shared_prior_overlay(&self, base: StrategyCombo) -> StrategyCombo {
        let priors = self.effective_shared_priors();
        if priors.is_empty() {
            return base;
        }
        let context = &self.current_learning_context;
        let bucket = context.target_bucket;
        let Some(state) = self.contexts.get(context) else {
            return base;
        };
        let now_ms = self.monotonic_now_ms();
        let blended = |combo: &StrategyCombo| {
            blended_success_mean(priors.get(&canonical_combo_hash(combo)), state.combos.get(combo))
                .unwrap_or(UNKNOWN_ARM_MEAN)
        };
        let mut best = base.clone();
        let mut best_mean = blended(&base);
        for idx in 0..COMBO_POOL.len() {
            let combo = combo_from_pool(idx);
            if combo == base || !combo_matches_bucket(&combo, bucket) {
                continue;
            }
            if !priors.contains_key(&canonical_combo_hash(&combo)) {
                continue;
            }
            let stats = state.combos.get(&combo);
            if stats.is_some_and(|s| s.is_cooled(now_ms) || s.attempts >= self.max_arm_attempts) {
                continue;
            }
            let mean = blended(&combo);
            if mean > best_mean {
                best_mean = mean;
                best = combo;
            }
        }
        best
    }

    pub(super) fn select_next_combo(&mut self) -> StrategyCombo {
        let (combo, exploit) = self.select_next_combo_base();
        if exploit { self.apply_shared_prior_overlay(combo) } else { combo }
    }

    /// Epsilon-greedy + UCB1 selection. The flag is `true` only for the
    /// exploit paths (niche winner, family-best), the only ones a verified
    /// shared prior may override.
    fn select_next_combo_base(&mut self) -> (StrategyCombo, bool) {
        let context = self.current_learning_context.clone();
        let bucket = context.target_bucket;
        let bucket_piloted = self.contexts.get(&context).is_some_and(|state| state.piloted_buckets.contains(&bucket));
        let now_ms = self.monotonic_now_ms();
        let half_life = self.decay_half_life_ms;

        if self.combos.is_empty() {
            return (pilot_combo_for_bucket(bucket), false);
        }
        if !bucket_piloted {
            return (pilot_combo_for_bucket(bucket), false);
        }
        if self.lcg_f64() < self.explore_epsilon {
            return (self.pick_non_cooled_random_for_bucket(bucket, now_ms), false);
        }
        let Some(state) = self.contexts.get(&context) else {
            return (self.pick_non_cooled_random_for_bucket(bucket, now_ms), false);
        };
        if let Some(niche) = state.niche_winners.get(&bucket)
            && state.combos.get(niche).is_some_and(|stats| !stats.is_cooled(now_ms))
        {
            return (niche.clone(), true);
        }
        let Some(family) = Self::select_next_family(state, bucket) else {
            return (self.pick_non_cooled_random_for_bucket(bucket, now_ms), false);
        };
        match Self::best_context_combo_for_family(state, family, now_ms, half_life) {
            Some(combo) => (combo, true),
            None => (self.pick_non_cooled_random_for_bucket(bucket, now_ms), false),
        }
    }

    /// Random-from-pool fallback that prefers combos not currently cooling.
    /// Falls back to [`pilot_combo_for_bucket`] when every bucket-matching
    /// pool entry has stats still in cooldown or has exceeded the
    /// per-arm attempt budget.
    pub(super) fn pick_non_cooled_random_for_bucket(
        &mut self,
        bucket: LearningTargetBucket,
        now_ms: u64,
    ) -> StrategyCombo {
        let max_attempts = self.max_arm_attempts;
        let available: Vec<usize> = (0..COMBO_POOL.len())
            .filter(|idx| combo_matches_bucket(&combo_from_pool(*idx), bucket))
            .filter(|idx| {
                let combo = combo_from_pool(*idx);
                let stats = self.combos.get(&combo);
                let cooled = stats.is_some_and(|s| s.is_cooled(now_ms));
                // Frozen combos (attempt budget exhausted) are skipped
                // during random exploration; niche-winner and
                // family-best paths still keep using them.
                let frozen = stats.is_some_and(|s| s.attempts >= max_attempts);
                !cooled && !frozen
            })
            .collect();
        if available.is_empty() {
            return pilot_combo_for_bucket(bucket);
        }
        let idx = available[self.lcg_index(available.len())];
        combo_from_pool(idx)
    }

    pub(super) fn select_next_family(
        state: &ContextBanditState,
        bucket: LearningTargetBucket,
    ) -> Option<StrategyFamily> {
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
            // Score ties (untouched arms) are broken by declaration order so
            // the choice never depends on HashMap iteration order.
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal).then_with(|| b.0.cmp(&a.0)))
            .map(|(family, _)| family)
    }

    pub(super) fn best_context_combo_for_family(
        state: &ContextBanditState,
        family: StrategyFamily,
        now_ms: u64,
        half_life_ms: u64,
    ) -> Option<StrategyCombo> {
        let total_attempts: u32 = state.combos.values().map(|stats| stats.attempts.max(1)).sum();
        let ln_total = (total_attempts as f64).ln().max(1.0);
        state
            .combos
            .iter()
            .filter(|(combo, _)| combo.family() == family || family == StrategyFamily::Mixed)
            // Skip combos that are still cooling.
            .filter(|(_, stats)| !stats.is_cooled(now_ms))
            .map(|(combo, stats)| {
                let score = if stats.attempts == 0 {
                    f64::MAX
                } else {
                    combo_fitness_at(combo, stats, now_ms, half_life_ms)
                        + 1.41 * (ln_total / stats.attempts as f64).sqrt()
                };
                (combo.clone(), score)
            })
            // Score ties (untouched arms) are broken by the stable dimension
            // key so the choice never depends on HashMap iteration order.
            .max_by(|a, b| {
                a.1.partial_cmp(&b.1)
                    .unwrap_or(std::cmp::Ordering::Equal)
                    .then_with(|| a.0.disc_key().cmp(&b.0.disc_key()).reverse())
            })
            .map(|(combo, _)| combo)
    }
}

/// Maximum number of pseudo-observations a shared prior is worth.
pub(crate) const SHARED_PRIOR_WEIGHT_CAP: f64 = 8.0;
/// Mean assumed for an arm with neither prior nor local data.
const UNKNOWN_ARM_MEAN: f64 = 0.5;

/// Success-rate estimate combining a capped shared prior with local counts.
fn blended_success_mean(prior: Option<&BetaParams>, stats: Option<&ComboStats>) -> Option<f64> {
    let (weight, prior_mean) = prior
        .filter(|p| p.alpha.is_finite() && p.beta.is_finite() && p.alpha > 0.0 && p.beta > 0.0)
        .map_or((0.0, 0.0), |p| ((p.alpha + p.beta).min(SHARED_PRIOR_WEIGHT_CAP), p.mean()));
    let (attempts, successes) = stats.map_or((0.0, 0.0), |s| (f64::from(s.attempts), f64::from(s.successes)));
    let total = weight + attempts;
    (total > 0.0).then(|| (weight * prior_mean + successes) / total)
}

pub(super) fn combo_matches_bucket(combo: &StrategyCombo, bucket: LearningTargetBucket) -> bool {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => combo.family() == StrategyFamily::Baseline,
        LearningTargetBucket::Tls => {
            combo.split_offset_base.is_some()
                || combo.tls_record_offset_base.is_some()
                || combo.tlsrandrec_profile.is_some()
                || combo.timing_jitter_profile.is_some()
                || combo.oob_byte_placement.is_some()
        }
        LearningTargetBucket::Ech => {
            combo.split_offset_base == Some(OffsetBase::EchExt)
                || combo.tls_record_offset_base == Some(OffsetBase::EchExt)
        }
        LearningTargetBucket::Quic => combo.quic_fake_profile.is_some() || combo.udp_burst_profile.is_some(),
    }
}

pub(super) fn default_family_for_bucket(bucket: LearningTargetBucket) -> StrategyFamily {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => StrategyFamily::Baseline,
        LearningTargetBucket::Tls => StrategyFamily::SplitOffset,
        LearningTargetBucket::Ech => StrategyFamily::TlsRecordOffset,
        LearningTargetBucket::Quic => StrategyFamily::QuicFake,
    }
}

pub(super) fn pilot_combo_for_bucket(bucket: LearningTargetBucket) -> StrategyCombo {
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

pub(super) fn evict_context_if_needed(
    state: &mut ContextBanditState,
    keep: &StrategyCombo,
    max_combos: usize,
    now_ms: u64,
    half_life_ms: u64,
) {
    if state.combos.contains_key(keep) || state.combos.len() < max_combos {
        return;
    }
    let worst = state
        .combos
        .iter()
        .filter(|(combo, _)| *combo != keep)
        .min_by(|a, b| {
            combo_fitness_at(a.0, a.1, now_ms, half_life_ms)
                .partial_cmp(&combo_fitness_at(b.0, b.1, now_ms, half_life_ms))
                .unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|(combo, _)| combo.clone());
    if let Some(worst_combo) = worst {
        state.combos.remove(&worst_combo);
        state.niche_winners.retain(|_, winner| winner != &worst_combo);
    }
}
