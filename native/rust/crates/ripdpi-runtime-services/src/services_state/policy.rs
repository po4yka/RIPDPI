use std::sync::{Arc, RwLock};

use ripdpi_config::RuntimeConfig;
use ripdpi_runtime_policy::direct_path_learning::DirectPathLearningState;
use ripdpi_runtime_policy::runtime_policy::RuntimePolicy;

pub(crate) struct PolicyServicesHandle {
    pub(crate) cache: Arc<RwLock<RuntimePolicy>>,
    pub(crate) direct_path_learning: Arc<RwLock<DirectPathLearningState>>,
}

impl PolicyServicesHandle {
    pub(crate) fn from_config(config: &RuntimeConfig) -> Self {
        Self {
            cache: Arc::new(RwLock::new(RuntimePolicy::load(config))),
            direct_path_learning: Arc::new(RwLock::new(DirectPathLearningState::default())),
        }
    }
}
