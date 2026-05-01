use arc_swap::ArcSwap;
use ripdpi_proxy_config::NetworkSnapshot;

#[derive(Clone)]
pub(crate) struct NetworkSnapshotState {
    inner: std::sync::Arc<ArcSwap<Option<NetworkSnapshot>>>,
}

impl NetworkSnapshotState {
    pub(crate) fn update(&self, snapshot: NetworkSnapshot) {
        self.inner.store(std::sync::Arc::new(Some(snapshot)));
    }

    pub(crate) fn current(&self) -> Option<NetworkSnapshot> {
        (**self.inner.load()).clone()
    }
}

impl Default for NetworkSnapshotState {
    fn default() -> Self {
        Self { inner: std::sync::Arc::new(ArcSwap::from_pointee(None)) }
    }
}
