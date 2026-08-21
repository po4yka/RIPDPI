use crate::strategy_evolver::StrategyEvolver;

impl StrategyEvolver {
    pub(in crate::strategy_evolver) fn lcg_next(&mut self) -> u32 {
        self.rng_state = self.rng_state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        // Take the upper 32 bits of the 64-bit state; this is the standard
        // PCG/Lehmer pattern. Shifting by 33 (the previous value) only kept
        // 31 bits, which made `lcg_f64` produce values in [0, 0.5) — a
        // distribution-skewing bug for epsilon-greedy and bucket selection.
        (self.rng_state >> 32) as u32
    }

    /// Returns a float in [0.0, 1.0).
    pub(in crate::strategy_evolver) fn lcg_f64(&mut self) -> f64 {
        self.lcg_next() as f64 / (u32::MAX as f64 + 1.0)
    }

    /// Uniform index in [0, len) via multiply-shift (Lemire), avoiding the
    /// modulo bias that `lcg_next() % len` incurs whenever 2^32 is not a
    /// multiple of `len`.
    pub(in crate::strategy_evolver) fn lcg_index(&mut self, len: usize) -> usize {
        debug_assert!(len > 0);
        ((u64::from(self.lcg_next()) * len as u64) >> 32) as usize
    }
}
