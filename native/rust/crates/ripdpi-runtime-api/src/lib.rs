//! `ripdpi-runtime-api` — runtime API / port types shared between the proxy
//! runtime (`ripdpi-proxy-runtime`) and the adapters around it.
//!
//! This is an **L2 contracts** crate: it owns the small set of traits and
//! handle types that let the runtime and its adapters communicate without
//! depending on each other's concrete internals. The public API splits into
//! three groups, all **stable** — downstream crates depend on them directly
//! (see the fan-in note for this crate in `docs/architecture/NATIVE_RUST.md`).
//!
//! # Contract ports — stable
//!
//! Traits implemented *outside* this crate and consumed by the runtime, so the
//! runtime never depends on a concrete adapter:
//!
//! - [`BackgroundProbes`] — background reprobe / warmup scheduling port,
//!   implemented by `ripdpi-runtime-services`.
//! - [`RuntimeTelemetrySink`] — per-event runtime telemetry port, implemented
//!   by the CLI, the Android telemetry adapter, and test harnesses.
//!
//! # Control surface — stable
//!
//! - [`EmbeddedProxyControl`] — the cloneable handle that carries shutdown
//!   state, an optional telemetry sink, the runtime context, and the live OS
//!   network snapshot into a running proxy.
//!
//! # Process-global telemetry registry — stable
//!
//! - [`install_runtime_telemetry`] / [`current_runtime_telemetry`] /
//!   [`clear_runtime_telemetry`] — a single process-global
//!   `RuntimeTelemetrySink` slot for code paths that cannot thread an
//!   [`EmbeddedProxyControl`] through (e.g. the global runtime state).
//!
//! # Internal modules — not API
//!
//! `network_snapshot` (`NetworkSnapshotState`) and `sync` (the loom/std
//! sync-primitive shim) are `pub(crate)`. They back the types above and are
//! intentionally unreachable from outside the crate.
//!
//! # Coupling to `ripdpi-proxy-config` / `ripdpi-failure-classifier`
//!
//! The port signatures here are **deliberately** expressed in terms of
//! `NetworkSnapshot` / `ProxyRuntimeContext` (from `ripdpi-proxy-config`) and
//! `ClassifiedFailure` (from `ripdpi-failure-classifier`). Those types are the
//! runtime's lingua franca; re-stating them as a parallel, decoupled hierarchy
//! in this crate would be churn without a payoff — every adapter would just
//! convert back at the boundary. The two dependencies are therefore expected
//! and load-bearing, not accidental coupling, and dropping them is explicitly
//! out of scope for this crate.

#![forbid(unsafe_code)]

// Contract ports (stable) -- traits implemented outside this crate.
mod background_probes;
mod desync_evidence;
mod telemetry_sink;

// Control surface (stable) -- the shared runtime handle.
mod embedded_control;

// Process-global telemetry registry (stable).
mod global_telemetry;

// Internal (pub(crate)) -- support modules, not part of the public API.
mod network_snapshot;
mod sync;

// --- Contract ports ---
pub use background_probes::BackgroundProbes;
pub use desync_evidence::{
    AttemptCorrelationId, DesyncExecutionDisposition, DesyncExecutionEvidence, DesyncExecutionReceipt,
    DesyncExecutionTransport, DesyncFallbackReason, DesyncOffsetMarkerBase, DesyncStrategyFamily, DesyncTerminalReason,
    DesyncTlsPreludeEvidence, DesyncTlsPreludeKind, DesyncUdpIpv6ExtensionProfile,
};
pub use telemetry_sink::RuntimeTelemetrySink;

// --- Control surface ---
pub use embedded_control::EmbeddedProxyControl;

// --- Process-global telemetry registry ---
pub use global_telemetry::{clear_runtime_telemetry, current_runtime_telemetry, install_runtime_telemetry};

#[cfg(test)]
mod tests;
