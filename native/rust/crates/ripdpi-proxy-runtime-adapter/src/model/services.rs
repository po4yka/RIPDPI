use std::sync::Arc;

use super::config::RuntimeConfig;
use super::ports::AdaptiveFeedbackPort;
use super::proxy_config::ProxyRuntimeContext;
use super::runtime_api::RuntimeTelemetrySink;

pub use ripdpi_runtime_services::{GeoMatcher, ServicesState, ServicesStateHandle};

pub fn new_services_handle(
    config: RuntimeConfig,
    telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
) -> ServicesStateHandle {
    ServicesStateHandle::new(ServicesState::new(config, telemetry, runtime_context))
}

#[derive(Clone)]
pub struct ReprobeResetHandle {
    services: ServicesStateHandle,
}

pub fn reprobe_reset_handle(services: &ServicesStateHandle) -> ReprobeResetHandle {
    ReprobeResetHandle { services: services.clone() }
}

impl ReprobeResetHandle {
    pub fn reset_strategy_state(&self) {
        self.services.reset_evolver();
        self.services.clear_adaptive_tuning();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_services_handle_constructs_single_consumer_warmup_channel() {
        let services = new_services_handle(RuntimeConfig::default(), None, None);

        assert!(services.take_warmup_receiver().is_some());
        assert!(services.take_warmup_receiver().is_none());
    }

    #[test]
    fn reprobe_reset_handle_is_cloneable_and_invokes_services_state() {
        let services = new_services_handle(RuntimeConfig::default(), None, None);
        let handle = reprobe_reset_handle(&services);

        handle.clone().reset_strategy_state();
        handle.reset_strategy_state();
    }
}
