use std::sync::{Arc, Mutex};

use arc_swap::ArcSwap;
use ripdpi_proxy_config::NetworkSnapshot;

pub(crate) struct NetworkStateHandle {
    /// Most-recently pushed OS network snapshot, written by `notify_network_change`.
    /// Lock-free reads via ArcSwap; written infrequently on network transitions.
    pub(crate) snapshot: Arc<ArcSwap<Option<NetworkSnapshot>>>,
    /// Tracks the last network identity string so we only surface real network
    /// changes rather than minor metadata updates.
    pub(crate) last_identity: Mutex<Option<String>>,
}

impl NetworkStateHandle {
    pub(crate) fn new() -> Self {
        Self { snapshot: Arc::new(ArcSwap::from_pointee(None)), last_identity: Mutex::new(None) }
    }
}
