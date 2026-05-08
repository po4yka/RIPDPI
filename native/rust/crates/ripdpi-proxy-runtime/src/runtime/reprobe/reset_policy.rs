use ripdpi_proxy_runtime_adapter::model::services::ReprobeResetHandle;

const FAILURE_THRESHOLD: usize = 2;

pub(crate) fn reset_if_strategy_mismatch(
    failures: usize,
    successes: usize,
    target_count: usize,
    reset_handle: &ReprobeResetHandle,
) {
    if failures >= FAILURE_THRESHOLD {
        tracing::info!(
            "network_reprobe: strategy_mismatch ({failures}/{target_count} failed), resetting evolver and adaptive cache"
        );
        reset_handle.reset_strategy_state();
    } else {
        tracing::info!("network_reprobe: passed ({successes}/{target_count} ok)");
    }
}
