use std::sync::{Arc, RwLock};

use ripdpi_config::RuntimeConfig;
use ripdpi_runtime_adaptive::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use ripdpi_runtime_adaptive::adaptive_tuning::AdaptivePlannerResolver;
use ripdpi_runtime_adaptive::retry_stealth::RetryPacer;

use crate::strategy_evolution::StrategyEvolutionResolver;

pub(crate) struct AdaptiveServicesHandle {
    pub(crate) fake_ttl: Arc<RwLock<AdaptiveFakeTtlResolver>>,
    pub(crate) tuning: Arc<RwLock<AdaptivePlannerResolver>>,
    pub(crate) retry_pacer: Arc<RwLock<RetryPacer>>,
    pub(crate) strategy_evolver: Arc<RwLock<StrategyEvolutionResolver>>,
}

impl AdaptiveServicesHandle {
    pub(crate) fn from_config(config: &RuntimeConfig) -> Self {
        Self {
            fake_ttl: Arc::new(RwLock::new(AdaptiveFakeTtlResolver::default())),
            tuning: Arc::new(RwLock::new(AdaptivePlannerResolver::load(config))),
            retry_pacer: Arc::new(RwLock::new(RetryPacer::default())),
            strategy_evolver: Arc::new(RwLock::new(StrategyEvolutionResolver::from_config(config))),
        }
    }
}
