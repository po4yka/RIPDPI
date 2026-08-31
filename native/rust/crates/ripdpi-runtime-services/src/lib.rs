#![forbid(unsafe_code)]

mod adaptive_port_impl;
mod background_probes_impl;
pub mod decision_helpers;
mod policy_port_impl;
mod services_state;
mod strategy_evolution;

use std::sync::Arc;

pub use ripdpi_runtime_policy::GeoMatcher;
pub use services_state::ServicesState;

/// A cheaply-cloneable handle to [`ServicesState`] that implements the
/// split policy selection/learning ports, focused adaptive port traits, and
/// `BackgroundProbes` port traits.
///
/// The newtype wrapper is required because Rust's orphan rules forbid
/// implementing foreign traits for `Arc<T>` where `T` is defined in this
/// crate. By wrapping in a local newtype we satisfy the coherence rules while
/// keeping the cheap clone semantics of `Arc`.
#[derive(Clone)]
pub struct ServicesStateHandle(pub(crate) Arc<ServicesState>);

impl ServicesStateHandle {
    pub fn new(state: Arc<ServicesState>) -> Self {
        Self(state)
    }
}

impl std::ops::Deref for ServicesStateHandle {
    type Target = ServicesState;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
