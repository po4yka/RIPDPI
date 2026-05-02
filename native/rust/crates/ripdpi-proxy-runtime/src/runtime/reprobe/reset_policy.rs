use ripdpi_runtime_adaptive::adaptive_tuning::AdaptivePlannerResolver;
use ripdpi_runtime_adaptive::strategy_evolution::StrategyEvolutionResolver;

const FAILURE_THRESHOLD: usize = 2;

pub(crate) fn reset_if_strategy_mismatch(
    failures: usize,
    successes: usize,
    target_count: usize,
    evolver: &crate::sync::Arc<crate::sync::RwLock<StrategyEvolutionResolver>>,
    adaptive_tuning: &crate::sync::Arc<crate::sync::RwLock<AdaptivePlannerResolver>>,
) {
    if failures >= FAILURE_THRESHOLD {
        tracing::info!(
            "network_reprobe: strategy_mismatch ({failures}/{target_count} failed), resetting evolver and adaptive cache"
        );
        reset_evolver(evolver);
        clear_adaptive_tuning(adaptive_tuning);
    } else {
        tracing::info!("network_reprobe: passed ({successes}/{target_count} ok)");
    }
}

fn reset_evolver(evolver: &crate::sync::Arc<crate::sync::RwLock<StrategyEvolutionResolver>>) {
    if let Ok(mut ev) = evolver.write() {
        ev.reset();
    }
}

fn clear_adaptive_tuning(adaptive_tuning: &crate::sync::Arc<crate::sync::RwLock<AdaptivePlannerResolver>>) {
    if let Ok(mut tuning) = adaptive_tuning.write() {
        tuning.clear_all();
    }
}
