use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_strategy::strategy_evolver::StrategyEvolver;

use crate::strategy_context::{tcp_learning_context, udp_learning_context};

pub struct StrategyEvolutionResolver {
    evolver: StrategyEvolver,
}

impl StrategyEvolutionResolver {
    pub fn from_config(config: &RuntimeConfig) -> Self {
        let enabled = config.adaptive.strategy_evolution;
        let epsilon = config.adaptive.evolution_epsilon_permil as f64 / 1000.0;
        Self {
            evolver: StrategyEvolver::new(enabled, epsilon).with_time_knobs(
                config.adaptive.evolution_experiment_ttl_ms,
                config.adaptive.evolution_decay_half_life_ms,
                config.adaptive.evolution_cooldown_after_failures,
                config.adaptive.evolution_cooldown_ms,
            ),
        }
    }

    pub fn tcp_hints(
        &mut self,
        config: &RuntimeConfig,
        runtime_context: Option<&ProxyRuntimeContext>,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> Option<AdaptivePlannerHints> {
        if !config.adaptive.strategy_evolution {
            return None;
        }
        self.evolver.set_learning_context(tcp_learning_context(config, runtime_context, target, host, payload));
        self.evolver.peek_hints().or_else(|| self.evolver.suggest_hints())
    }

    pub fn udp_hints(
        &mut self,
        config: &RuntimeConfig,
        runtime_context: Option<&ProxyRuntimeContext>,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> Option<AdaptivePlannerHints> {
        if !config.adaptive.strategy_evolution {
            return None;
        }
        self.evolver.set_learning_context(udp_learning_context(config, runtime_context, target, host, payload));
        self.evolver.peek_hints().or_else(|| self.evolver.suggest_hints())
    }

    pub fn record_success(&mut self, latency_ms: u64) {
        self.evolver.record_success(latency_ms);
    }

    pub fn record_failure(&mut self, class: FailureClass) {
        self.evolver.record_failure(class);
    }

    pub fn reset(&mut self) {
        self.evolver = StrategyEvolver::new(self.evolver.is_enabled(), self.evolver.epsilon());
    }
}
