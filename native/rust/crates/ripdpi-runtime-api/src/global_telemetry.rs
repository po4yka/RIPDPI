//! Process-global telemetry registry — **stable** public API.
//!
//! A single process-wide `RuntimeTelemetrySink` slot for code paths that
//! cannot thread an [`EmbeddedProxyControl`](crate::EmbeddedProxyControl)
//! through (notably the global runtime state). Prefer the per-runtime sink on
//! `EmbeddedProxyControl` when a handle is reachable; this global is the
//! fallback, not the default.
//!
//! Under `--features loom` the `OnceLock`-backed global is compiled out (loom
//! does not model `OnceLock`); the accessors become no-ops returning `None`.
//! The loom test in `tests/loom_telemetry_sink.rs` exercises the underlying
//! `Mutex<Option<Arc<...>>>` slot pattern directly instead.

#[cfg(not(feature = "loom"))]
use std::sync::OnceLock;

use crate::telemetry_sink::RuntimeTelemetrySink;

// OnceLock is not modeled by loom, so the global static and its accessor are
// compiled only on the production path. Loom tests exercise the underlying
// Mutex<Option<Arc<...>>> pattern directly via local instances.
#[cfg(not(feature = "loom"))]
static TELEMETRY_SINK: OnceLock<std::sync::Mutex<Option<std::sync::Arc<dyn RuntimeTelemetrySink>>>> = OnceLock::new();

/// Install (or replace) the process-global telemetry sink.
#[cfg(not(feature = "loom"))]
pub fn install_runtime_telemetry(sink: std::sync::Arc<dyn RuntimeTelemetrySink>) {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = Some(sink);
    }
}

/// Clear the process-global telemetry sink, if one is installed.
#[cfg(not(feature = "loom"))]
pub fn clear_runtime_telemetry() {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = None;
    }
}

/// Clone the currently installed process-global telemetry sink, or `None`.
#[cfg(not(feature = "loom"))]
pub fn current_runtime_telemetry() -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
    telemetry_slot().lock().ok().and_then(|slot| slot.clone())
}

// Under loom the OnceLock-based global is compiled out. Callers that use
// current_runtime_telemetry in production code paths get None, which is the
// correct behaviour for tests that exercise concurrency primitives only.
#[cfg(feature = "loom")]
pub fn current_runtime_telemetry() -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
    None
}

#[cfg(feature = "loom")]
pub fn install_runtime_telemetry(_sink: std::sync::Arc<dyn RuntimeTelemetrySink>) {}

#[cfg(feature = "loom")]
pub fn clear_runtime_telemetry() {}

#[cfg(not(feature = "loom"))]
fn telemetry_slot() -> &'static std::sync::Mutex<Option<std::sync::Arc<dyn RuntimeTelemetrySink>>> {
    TELEMETRY_SINK.get_or_init(|| std::sync::Mutex::new(None))
}
