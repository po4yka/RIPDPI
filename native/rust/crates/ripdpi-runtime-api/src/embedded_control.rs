use std::fmt;

use ripdpi_proxy_config::{NetworkSnapshot, ProxyRuntimeContext};

use crate::network_snapshot::NetworkSnapshotState;
use crate::sync::{Arc, AtomicBool, Ordering};
use crate::telemetry_sink::RuntimeTelemetrySink;

#[derive(Clone)]
pub struct EmbeddedProxyControl {
    shutdown: Arc<AtomicBool>,
    /// Uses `std::sync::Arc` explicitly: the telemetry sink is not exercised by
    /// loom tests and must stay compatible with downstream crates that always
    /// use `std::sync::Arc` (ripdpi-android, ripdpi-cli test harnesses).
    telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    /// Live OS network state snapshot, pushed from Kotlin on each NetworkCallback event.
    /// Uses `ArcSwap` for lock-free reads on the per-connection hot path.
    /// Uses `std::sync::Arc` explicitly: arc-swap is not loom-compatible,
    /// and no loom tests exercise this field.
    network_snapshot: NetworkSnapshotState,
}

impl EmbeddedProxyControl {
    pub fn new(telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>) -> Self {
        Self::new_with_context(telemetry, None)
    }

    pub fn new_with_context(
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        Self {
            shutdown: Arc::new(AtomicBool::new(false)),
            telemetry,
            runtime_context,
            network_snapshot: NetworkSnapshotState::default(),
        }
    }

    pub fn request_shutdown(&self) {
        self.shutdown.store(true, Ordering::Release);
    }

    pub fn reset_shutdown(&self) {
        self.shutdown.store(false, Ordering::Release);
    }

    pub fn shutdown_requested(&self) -> bool {
        self.shutdown.load(Ordering::Acquire)
    }

    pub fn telemetry_sink(&self) -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
        self.telemetry.clone()
    }

    pub fn runtime_context(&self) -> Option<ProxyRuntimeContext> {
        self.runtime_context.clone()
    }

    /// Push a fresh OS network state snapshot. Safe to call from any thread while the proxy runs.
    pub fn update_network_snapshot(&self, snapshot: NetworkSnapshot) {
        self.network_snapshot.update(snapshot);
    }

    /// Read the most recently pushed OS network state snapshot, if any.
    pub fn current_network_snapshot(&self) -> Option<NetworkSnapshot> {
        self.network_snapshot.current()
    }
}

impl Default for EmbeddedProxyControl {
    fn default() -> Self {
        Self::new(None)
    }
}

impl fmt::Debug for EmbeddedProxyControl {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EmbeddedProxyControl")
            .field("shutdown_requested", &self.shutdown_requested())
            .field("has_telemetry_sink", &self.telemetry.is_some())
            .field("has_runtime_context", &self.runtime_context.is_some())
            .finish()
    }
}
