//! Control surface — **stable** public API.
//!
//! [`EmbeddedProxyControl`] is the cloneable handle threaded into a running
//! proxy. Clones share the same shutdown flag and network-snapshot slot, so a
//! `request_shutdown` or `update_network_snapshot` on any clone is visible to
//! all of them. The handle reuses `ProxyRuntimeContext` / `NetworkSnapshot`
//! from `ripdpi-proxy-config` by design — see the crate-level docs.

use std::fmt;

use ripdpi_proxy_config::{NetworkSnapshot, ProxyRuntimeContext};

use crate::network_snapshot::NetworkSnapshotState;
use crate::sync::{Arc, AtomicBool, Ordering};
use crate::telemetry_sink::RuntimeTelemetrySink;

/// Shared, cloneable control handle for an embedded proxy runtime.
///
/// Carries the cooperative shutdown flag, an optional [`RuntimeTelemetrySink`],
/// the immutable [`ProxyRuntimeContext`], and the live OS [`NetworkSnapshot`].
/// All clones alias the same shutdown flag and snapshot slot.
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
    /// Build a control handle with an optional telemetry sink and no runtime
    /// context. Shorthand for [`new_with_context`](Self::new_with_context).
    pub fn new(telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>) -> Self {
        Self::new_with_context(telemetry, None)
    }

    /// Build a control handle with an optional telemetry sink and runtime
    /// context. The shutdown flag starts cleared and the network snapshot empty.
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

    /// Signal a cooperative shutdown. Visible to every clone of this handle.
    pub fn request_shutdown(&self) {
        self.shutdown.store(true, Ordering::Release);
    }

    /// Clear the shutdown flag so the handle can be reused for a new session.
    pub fn reset_shutdown(&self) {
        self.shutdown.store(false, Ordering::Release);
    }

    /// Returns `true` once [`request_shutdown`](Self::request_shutdown) has
    /// been called on this handle or any of its clones.
    pub fn shutdown_requested(&self) -> bool {
        self.shutdown.load(Ordering::Acquire)
    }

    /// The telemetry sink supplied at construction, if any.
    pub fn telemetry_sink(&self) -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
        self.telemetry.clone()
    }

    /// The immutable runtime context supplied at construction, if any.
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
