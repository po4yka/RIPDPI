use std::sync::Arc;

use super::config::RuntimeConfig;
use super::ports::AdaptiveFeedbackPort;
use super::proxy_config::ProxyRuntimeContext;
use super::runtime_api::RuntimeTelemetrySink;

pub use ripdpi_runtime_decision_engine::{
    FlowRouteInputs, RuntimeDecisionEngine, RuntimeDecisionInputs, RuntimeDecisionOutputs,
};
pub use ripdpi_runtime_services::{GeoMatcher, ServicesState, ServicesStateHandle};

pub fn new_services_handle(
    config: RuntimeConfig,
    telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
) -> ServicesStateHandle {
    ServicesStateHandle::new(ServicesState::new(config, telemetry, runtime_context))
}

/// Construct a [`RuntimeDecisionEngine`] over an existing services handle.
///
/// The engine is a clone over the same underlying ports; constructing it
/// here lets proxy-runtime hold the engine without depending on
/// `ripdpi-runtime-decision-engine` directly — the adapter is the seam.
pub fn new_decision_engine(services: &ServicesStateHandle) -> RuntimeDecisionEngine {
    RuntimeDecisionEngine::new(services.clone())
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
