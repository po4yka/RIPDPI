//! Selected-decision ports for socket runtime execution.
//!
//! `ripdpi-proxy-runtime` should depend on this narrow adapter instead of the
//! policy/adaptive engines directly. The engines remain behind these port traits
//! and selected-route data contracts.

pub use ripdpi_runtime_adaptive::{
    AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, PreferredTargets, RetryPacingPort,
};
pub use ripdpi_runtime_policy::{DirectPathLearningPort, PolicyPort};

pub mod adaptive {
    pub use ripdpi_runtime_adaptive::morph_policy;
    pub use ripdpi_runtime_adaptive::strategy_context;
}

pub mod direct_path_learning {
    pub use ripdpi_runtime_policy::direct_path_learning::*;
}

pub mod policy {
    pub use ripdpi_runtime_policy::runtime_policy::*;
}
