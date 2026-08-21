use std::collections::HashMap;
use std::time::Instant;

use super::super::{
    DEFAULT_COOLDOWN_AFTER_FAILURES, DEFAULT_COOLDOWN_MS, DEFAULT_DECAY_HALF_LIFE_MS, DEFAULT_EXPERIMENT_TTL_MS,
    StrategyEvolver,
};
use crate::strategy_evolver::types::{LearningContext, now_millis};

impl StrategyEvolver {
    pub fn new(enabled: bool, epsilon: f64) -> Self {
        // Clamp the caller-supplied exploration rate into [0, 1]. NaN would
        // otherwise compare false against every threshold and silently
        // disable exploration forever.
        let explore_epsilon = if epsilon.is_finite() { epsilon.clamp(0.0, 1.0) } else { 0.0 };
        Self {
            combos: HashMap::new(),
            contexts: HashMap::new(),
            current_experiment: None,
            current_experiment_context: None,
            current_experiment_family: None,
            current_experiment_started_ms: None,
            current_learning_context: LearningContext::default(),
            explore_epsilon,
            max_combos: 64,
            enabled,
            rng_state: initial_rng_state(),
            epoch: Instant::now(),
            experiment_ttl_ms: DEFAULT_EXPERIMENT_TTL_MS,
            decay_half_life_ms: DEFAULT_DECAY_HALF_LIFE_MS,
            cooldown_after_failures: DEFAULT_COOLDOWN_AFTER_FAILURES,
            cooldown_ms: DEFAULT_COOLDOWN_MS,
            max_arm_attempts: u32::MAX,
            penalties_enabled: false,
            shared_priors: HashMap::new(),
            #[cfg(test)]
            test_clock_override_ms: None,
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
        clear_pending_experiment(self);
    }

    pub fn current_learning_context(&self) -> &LearningContext {
        &self.current_learning_context
    }
}

pub(in crate::strategy_evolver) fn clear_pending_experiment(evolver: &mut StrategyEvolver) {
    evolver.current_experiment = None;
    evolver.current_experiment_context = None;
    evolver.current_experiment_family = None;
    evolver.current_experiment_started_ms = None;
}

fn initial_rng_state() -> u64 {
    // Mix per-process ASLR-backed entropy (RandomState) with the clock and
    // pid; the wall-clock-plus-constant product alone was trivially
    // predictable across devices booting at the same time.
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    let mut hasher = RandomState::new().build_hasher();
    hasher.write_u64(now_millis());
    hasher.write_u32(std::process::id());
    hasher.finish()
}
