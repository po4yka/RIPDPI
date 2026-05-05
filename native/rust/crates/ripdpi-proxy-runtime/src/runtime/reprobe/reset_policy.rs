use std::sync::Arc;

use ripdpi_runtime_adaptive::AdaptivePort;

const FAILURE_THRESHOLD: usize = 2;

pub(crate) fn reset_if_strategy_mismatch(
    failures: usize,
    successes: usize,
    target_count: usize,
    adaptive: &Arc<dyn AdaptivePort>,
) {
    if failures >= FAILURE_THRESHOLD {
        tracing::info!(
            "network_reprobe: strategy_mismatch ({failures}/{target_count} failed), resetting evolver and adaptive cache"
        );
        adaptive.reset_evolver();
        adaptive.clear_adaptive_tuning();
    } else {
        tracing::info!("network_reprobe: passed ({successes}/{target_count} ok)");
    }
}
