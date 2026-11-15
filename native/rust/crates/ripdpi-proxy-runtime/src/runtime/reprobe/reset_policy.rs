use ripdpi_proxy_runtime_adapter::model::services::ReprobeResetHandle;

const FAILURE_THRESHOLD: usize = 2;

pub(crate) fn reset_after_baseline_failures(
    failures: usize,
    successes: usize,
    target_count: usize,
    reset_handle: &ReprobeResetHandle,
) {
    if failures >= FAILURE_THRESHOLD {
        tracing::info!(
            "network_reprobe: baseline_failures ({failures}/{target_count} failed), resetting evolver and adaptive cache"
        );
        reset_handle.reset_strategy_state();
    } else {
        tracing::info!("network_reprobe: passed ({successes}/{target_count} ok)");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
    use ripdpi_proxy_runtime_adapter::model::services::{new_services_handle, reprobe_reset_handle};

    #[test]
    fn reset_policy_only_resets_at_failure_threshold() {
        let services = new_services_handle(RuntimeConfig::default(), None, None);
        let reset_handle = reprobe_reset_handle(&services);

        reset_after_baseline_failures(0, 3, 3, &reset_handle);
        reset_after_baseline_failures(1, 2, 3, &reset_handle);
        reset_after_baseline_failures(2, 1, 3, &reset_handle);
    }
}
