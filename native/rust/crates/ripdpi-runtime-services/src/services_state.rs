mod adaptive;
mod network;
mod policy;
mod runtime_context;
mod warmup;

use std::sync::Arc;

use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_api::RuntimeTelemetrySink;

pub(crate) use adaptive::AdaptiveServicesHandle;
pub(crate) use network::NetworkStateHandle;
pub(crate) use policy::PolicyServicesHandle;
pub(crate) use runtime_context::RuntimeContextHandle;
pub(crate) use warmup::WarmupQueueHandle;
pub use warmup::WarmupRequest;

// Stateful inventory:
// - cache: route policy, autolearn, and host persistence.
// - adaptive_fake_ttl: fake-TTL resolver feedback.
// - adaptive_tuning: adaptive split/disorder hint feedback and persistence.
// - retry_pacer: reconnect pacing and retry-selection cooldowns.
// - strategy_evolver: strategy-evolution experiment state.
// - direct_path_learning: direct-path UDP/QUIC/TCP reachability state.
//
// Lock order: policy cache -> adaptive fake-TTL -> adaptive tuning.
// Never acquire more than one simultaneously; if needed, always in this order.
pub struct ServicesState {
    pub(crate) config: Arc<RuntimeConfig>,
    pub(crate) policy: PolicyServicesHandle,
    pub(crate) adaptive: AdaptiveServicesHandle,
    pub(crate) runtime: RuntimeContextHandle,
    pub(crate) network: NetworkStateHandle,
    pub(crate) warmup: WarmupQueueHandle,
}

impl ServicesState {
    pub fn new(
        config: RuntimeConfig,
        telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Arc<Self> {
        Arc::new(Self {
            policy: PolicyServicesHandle::from_config(&config),
            adaptive: AdaptiveServicesHandle::from_config(&config),
            runtime: RuntimeContextHandle::new(telemetry, runtime_context),
            network: NetworkStateHandle::new(),
            warmup: WarmupQueueHandle::new(32),
            config: Arc::new(config),
        })
    }

    /// Take the warmup receiver for consumption by proxy-runtime.
    ///
    /// Returns `Some` on the first call and `None` on all subsequent calls.
    /// Proxy-runtime should call this once during initialisation and drive
    /// the returned receiver on its warmup thread.
    pub fn take_warmup_receiver(&self) -> Option<std::sync::mpsc::Receiver<WarmupRequest>> {
        self.warmup.take_receiver()
    }
}

impl Drop for ServicesState {
    fn drop(&mut self) {
        if let Ok(mut cache) = self.policy.cache.write() {
            cache.flush_host_store(&self.config);
            let _ = cache.dump_stdout_groups(&self.config, std::io::stdout());
        }
        if let Ok(mut adaptive_tuning) = self.adaptive.tuning.write() {
            adaptive_tuning.flush_store(self.config.as_ref());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::ServicesState;
    use ripdpi_config::RuntimeConfig;

    #[test]
    fn warmup_receiver_is_single_owner() {
        let state = ServicesState::new(RuntimeConfig::default(), None, None);

        assert!(state.take_warmup_receiver().is_some());
        assert!(state.take_warmup_receiver().is_none());
    }
}
