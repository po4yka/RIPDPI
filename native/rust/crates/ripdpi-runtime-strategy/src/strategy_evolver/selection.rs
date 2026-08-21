use ripdpi_config::{OffsetBase, QuicFakeProfile};

use super::StrategyEvolver;
use super::types::{
    COMBO_POOL, ContextBanditState, LearningTargetBucket, StrategyCombo, StrategyFamily, combo_fitness_at,
    combo_from_pool,
};

impl StrategyEvolver {
    pub(super) fn select_next_combo(&mut self) -> StrategyCombo {
        let context = self.current_learning_context.clone();
        let bucket = context.target_bucket;
        let bucket_piloted = self.contexts.get(&context).is_some_and(|state| state.piloted_buckets.contains(&bucket));
        let now_ms = self.monotonic_now_ms();
        let half_life = self.decay_half_life_ms;

        if self.combos.is_empty() {
            return pilot_combo_for_bucket(bucket);
        }
        if !bucket_piloted {
            return pilot_combo_for_bucket(bucket);
        }
        if self.lcg_f64() < self.explore_epsilon {
            return self.pick_non_cooled_random_for_bucket(bucket, now_ms);
        }
        let Some(state) = self.contexts.get(&context) else {
            return self.pick_non_cooled_random_for_bucket(bucket, now_ms);
        };
        if let Some(niche) = state.niche_winners.get(&bucket)
            && !state.combos.get(niche).is_some_and(|stats| stats.is_cooled(now_ms))
        {
            return niche.clone();
        }
        let Some(family) = Self::select_next_family(state, bucket) else {
            return self.pick_non_cooled_random_for_bucket(bucket, now_ms);
        };
        Self::best_context_combo_for_family(state, family, now_ms, half_life)
            .unwrap_or_else(|| self.pick_non_cooled_random_for_bucket(bucket, now_ms))
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
    if state.combos.len() < max_combos {
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
    }
}
