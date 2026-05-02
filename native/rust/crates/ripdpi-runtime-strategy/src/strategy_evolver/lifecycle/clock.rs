use crate::strategy_evolver::StrategyEvolver;

impl StrategyEvolver {
    /// Monotonic ms-since-epoch tick used by all evolver-internal
    /// timestamps. Independent of `SystemTime`, so TTL/decay/cooldown
    /// survive NTP corrections.
    pub(in crate::strategy_evolver) fn monotonic_now_ms(&self) -> u64 {
        monotonic_now_ms(self)
    }

    /// Test-only: pin the evolver's monotonic clock so TTL / decay /
    /// cooldown can be exercised deterministically.
    #[cfg(test)]
    pub(in crate::strategy_evolver) fn set_test_clock_ms(&mut self, ms: u64) {
        self.test_clock_override_ms = Some(ms);
    }
}

pub(in crate::strategy_evolver) fn monotonic_now_ms(evolver: &StrategyEvolver) -> u64 {
    #[cfg(test)]
    if let Some(override_ms) = evolver.test_clock_override_ms {
        return override_ms;
    }
    evolver.epoch.elapsed().as_millis() as u64
}
