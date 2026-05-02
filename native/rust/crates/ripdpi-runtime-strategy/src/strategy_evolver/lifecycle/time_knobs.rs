use crate::strategy_evolver::StrategyEvolver;

impl StrategyEvolver {
    /// Builder-style override for offline-learner hardening knobs. Both knobs
    /// default to OFF in [`Self::new`] so existing call sites are unaffected;
    /// opt in here.
    ///
    /// `max_arm_attempts` is the hard cap before a combo is skipped during
    /// random exploration (use `u32::MAX` to keep the cap disabled).
    /// `penalties_enabled` toggles the rarity / retry-cost terms on top of
    /// the standard fitness function.
    pub fn with_learning_hardening(mut self, max_arm_attempts: u32, penalties_enabled: bool) -> Self {
        self.max_arm_attempts = max_arm_attempts;
        self.penalties_enabled = penalties_enabled;
        self
    }

    /// Number of attempts left for `combo` before the attempt-budget cap
    /// kicks in. Returns `0` when the combo has already been frozen out of
    /// random exploration. When the cap is disabled (`u32::MAX`), returns
    /// `u32::MAX - attempts` so callers can treat the value uniformly.
    pub fn attempts_budget_remaining(&self, combo: &crate::strategy_evolver::StrategyCombo) -> u32 {
        let used = self.combos.get(combo).map_or(0, |stats| stats.attempts);
        self.max_arm_attempts.saturating_sub(used)
    }

    /// Builder-style override for the four time-driven knobs. Used by the
    /// runtime listener to thread `RuntimeAdaptiveSettings::evolution_*`
    /// into the evolver without touching [`Self::new`]'s signature.
    pub fn with_time_knobs(
        mut self,
        experiment_ttl_ms: u64,
        decay_half_life_ms: u64,
        cooldown_after_failures: u32,
        cooldown_ms: u64,
    ) -> Self {
        self.experiment_ttl_ms = experiment_ttl_ms;
        self.decay_half_life_ms = decay_half_life_ms;
        self.cooldown_after_failures = cooldown_after_failures;
        self.cooldown_ms = cooldown_ms;
        self
    }
}
