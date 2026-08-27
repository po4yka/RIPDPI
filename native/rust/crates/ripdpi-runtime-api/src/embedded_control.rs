//! Control surface — **stable** public API.
//!
//! [`EmbeddedProxyControl`] is the cloneable handle threaded into a running
//! proxy. Clones share the same shutdown flag and network-snapshot slot, so a
//! `request_shutdown` or `update_network_snapshot` on any clone is visible to
//! all of them. The handle reuses `ProxyRuntimeContext` / `NetworkSnapshot`
//! from `ripdpi-proxy-config` by design — see the crate-level docs.

use std::collections::BTreeMap;
use std::fmt;

use ripdpi_proxy_config::{NetworkSnapshot, ProxyRuntimeContext};

use crate::desync_evidence::{AttemptCorrelationId, DesyncExecutionEvidence};
use crate::network_snapshot::NetworkSnapshotState;
use crate::sync::{Arc, AtomicBool, Mutex, Ordering};
use crate::telemetry_sink::RuntimeTelemetrySink;

const DESYNC_EXECUTION_EVIDENCE_LIMIT: usize = 32;

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
    desync_execution_evidence: DesyncExecutionEvidenceCollector,
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
        Self::new_with_desync_execution_evidence(telemetry, runtime_context, 0)
    }

    pub fn new_with_desync_execution_evidence(
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
        desync_evidence_generation: u64,
    ) -> Self {
        Self {
            shutdown: Arc::new(AtomicBool::new(false)),
            telemetry,
            desync_execution_evidence: DesyncExecutionEvidenceCollector::new(desync_evidence_generation),
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

    pub fn desync_execution_generation(&self) -> u64 {
        self.desync_execution_evidence.generation()
    }

    pub fn record_desync_execution_evidence(&self, evidence: DesyncExecutionEvidence) -> bool {
        self.desync_execution_evidence.record(evidence)
    }

    pub fn next_desync_connection_ordinal(&self, attempt_token: &AttemptCorrelationId) -> Option<u64> {
        self.desync_execution_evidence.next_connection_ordinal(attempt_token)
    }

    pub fn desync_execution_evidence(&self) -> Vec<DesyncExecutionEvidence> {
        self.desync_execution_evidence.snapshot()
    }

    pub fn desync_execution_evidence_overflowed(&self) -> bool {
        self.desync_execution_evidence.overflowed()
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
            .field("desync_execution_evidence_overflowed", &self.desync_execution_evidence_overflowed())
            .field("has_runtime_context", &self.runtime_context.is_some())
            .finish()
    }
}

#[derive(Clone)]
struct DesyncExecutionEvidenceCollector {
    generation: u64,
    state: Arc<Mutex<DesyncExecutionEvidenceCollectorState>>,
}

impl DesyncExecutionEvidenceCollector {
    fn new(generation: u64) -> Self {
        Self { generation, state: Arc::new(Mutex::new(DesyncExecutionEvidenceCollectorState::empty())) }
    }

    fn generation(&self) -> u64 {
        self.generation
    }

    fn record(&self, evidence: DesyncExecutionEvidence) -> bool {
        if evidence.generation() != self.generation || self.generation == 0 {
            return false;
        }
        {
            let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if state.evidence.len() >= DESYNC_EXECUTION_EVIDENCE_LIMIT {
                state.overflowed = true;
                return false;
            }
            state.evidence.push(evidence.clone());
        }
        true
    }

    fn next_connection_ordinal(&self, attempt_token: &AttemptCorrelationId) -> Option<u64> {
        if self.generation == 0 {
            return None;
        }
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let key = attempt_token.as_opaque_str();
        if !state.connection_ordinals.contains_key(key)
            && state.connection_ordinals.len() >= DESYNC_EXECUTION_EVIDENCE_LIMIT
        {
            state.overflowed = true;
            return None;
        }
        let ordinal = state.connection_ordinals.entry(key.to_owned()).or_default();
        *ordinal = ordinal.checked_add(1)?;
        Some(*ordinal)
    }

    fn snapshot(&self) -> Vec<DesyncExecutionEvidence> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).evidence.clone()
    }

    fn overflowed(&self) -> bool {
        let state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        // A receipt rejected before collection still consumed a connection ordinal.
        // Preserve that missing terminal evidence even when it was the last connection.
        state.overflowed
            || state.connection_ordinals.iter().any(|(token, allocated)| {
                state.evidence.iter().filter(|evidence| evidence.attempt_token().as_opaque_str() == token).count()
                    as u64
                    != *allocated
            })
    }
}

struct DesyncExecutionEvidenceCollectorState {
    evidence: Vec<DesyncExecutionEvidence>,
    connection_ordinals: BTreeMap<String, u64>,
    overflowed: bool,
}

impl DesyncExecutionEvidenceCollectorState {
    fn empty() -> Self {
        Self { evidence: Vec::new(), connection_ordinals: BTreeMap::new(), overflowed: false }
    }
}
