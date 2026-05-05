mod adaptive_port_impl;
mod background_probes_impl;
mod policy_port_impl;
mod services_state;

use std::sync::Arc;

pub use services_state::ServicesState;

/// A cheaply-cloneable handle to [`ServicesState`] that implements the
/// `PolicyPort`, `AdaptivePort`, and `BackgroundProbes` port traits.
///
/// The newtype wrapper is required because Rust's orphan rules forbid
/// implementing foreign traits for `Arc<T>` where `T` is defined in this
/// crate. By wrapping in a local newtype we satisfy the coherence rules while
/// keeping the cheap clone semantics of `Arc`.
#[derive(Clone)]
pub struct ServicesStateHandle(pub Arc<ServicesState>);

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
