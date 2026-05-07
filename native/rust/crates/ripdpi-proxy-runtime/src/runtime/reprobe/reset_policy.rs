use ripdpi_proxy_runtime_adapter::model::ports::AdaptiveFeedbackPort;
use ripdpi_proxy_runtime_adapter::model::services::ServicesStateHandle;

const FAILURE_THRESHOLD: usize = 2;

pub(crate) fn reset_if_strategy_mismatch(
    failures: usize,
    successes: usize,
    target_count: usize,
    services: &ServicesStateHandle,
) {
    if failures >= FAILURE_THRESHOLD {
        tracing::info!(
            "network_reprobe: strategy_mismatch ({failures}/{target_count} failed), resetting evolver and adaptive cache"
        );
        services.reset_evolver();
        services.clear_adaptive_tuning();
    } else {
        tracing::info!("network_reprobe: passed ({successes}/{target_count} ok)");
    }
}
