//! Circumvention-reachability classifier wrapped as a Probe.
//!
//! Walks per-layer captured outcomes (Bootstrap / Tcp / TlsHandshake /
//! Liveness) for a circumvention tool endpoint (VLESS relay, Hysteria2
//! node, MASQUE provider, etc.) and reports a single verdict.
//!
//! ## Verdict mapping (first match wins)
//!
//! 1. `Inconclusive { reason: "no layer outcomes captured" }` — empty input.
//! 2. `Inconclusive { reason: "every layer hit a setup error" }` — every
//!    captured layer status is `SetupError`.
//! 3. `Pass` — every non-setup layer is `Ok`.
//! 4. `Fail { class: "circumvention-<layer>-blocked" }` — exactly one
//!    layer is `Failed`. Layer tag lower-cased (`bootstrap`, `tcp`,
//!    `tlshandshake`, `liveness`).
//! 5. `Fail { class: "circumvention-multi-layer-blocked" }` — two or
//!    more layers are `Failed`.
//!
//! `SetupError` layers do not count toward the multi-layer threshold.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier.
pub const CIRCUMVENTION_REACHABILITY_PROBE_ID: &str = "circumvention_reachability";

/// One layer of the circumvention reachability check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CircumventionLayer {
    /// HTTP bootstrap (subscription / health endpoint).
    Bootstrap,
    /// TCP connect to the handshake endpoint.
    Tcp,
    /// TLS handshake against the configured SNI.
    TlsHandshake,
    /// Tool-specific liveness signal (e.g. PING/PONG, control-channel echo).
    Liveness,
}

impl CircumventionLayer {
    fn class_tag(self) -> &'static str {
        match self {
            CircumventionLayer::Bootstrap => "bootstrap",
            CircumventionLayer::Tcp => "tcp",
            CircumventionLayer::TlsHandshake => "tlshandshake",
            CircumventionLayer::Liveness => "liveness",
        }
    }
}

/// Per-layer outcome status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CircumventionLayerStatus {
    /// Layer executed successfully.
    Ok,
    /// Layer executed but the wire-level check failed.
    Failed {
        /// Short description of the failure for triage.
        detail: String,
    },
    /// The harness could not run this layer.
    SetupError {
        /// Short description of the setup error.
        detail: String,
    },
}

/// Outcome record for a single layer.
#[derive(Debug, Clone)]
pub struct CircumventionLayerOutcome {
    /// Which layer this outcome describes.
    pub layer: CircumventionLayer,
    /// Layer status.
    pub status: CircumventionLayerStatus,
}

/// Pure [`Probe`] adapter for circumvention reachability.
#[derive(Debug, Clone)]
pub struct CircumventionReachabilityProbe {
    tool_id: String,
    layers: Vec<CircumventionLayerOutcome>,
}

impl CircumventionReachabilityProbe {
    /// Construct from a tool/endpoint identifier and a list of
    /// captured per-layer outcomes.
    pub fn new(tool_id: String, layers: Vec<CircumventionLayerOutcome>) -> Self {
        Self { tool_id, layers }
    }

    /// Tool identifier passed to [`Self::new`].
    pub fn tool_id(&self) -> &str {
        &self.tool_id
    }
}

impl Probe for CircumventionReachabilityProbe {
    fn id(&self) -> &'static str {
        CIRCUMVENTION_REACHABILITY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Circumvention
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(&self.layers);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

fn classify_verdict(layers: &[CircumventionLayerOutcome]) -> ProbeVerdict {
    if layers.is_empty() {
        return ProbeVerdict::Inconclusive { reason: "no layer outcomes captured".to_string() };
    }
    let all_setup = layers.iter().all(|o| matches!(o.status, CircumventionLayerStatus::SetupError { .. }));
    if all_setup {
        return ProbeVerdict::Inconclusive { reason: "every layer hit a setup error".to_string() };
    }
    let failed: Vec<&CircumventionLayerOutcome> =
        layers.iter().filter(|o| matches!(o.status, CircumventionLayerStatus::Failed { .. })).collect();
    if failed.is_empty() {
        return ProbeVerdict::Pass;
    }
    if failed.len() == 1 {
        return ProbeVerdict::Fail { class: format!("circumvention-{}-blocked", failed[0].layer.class_tag()) };
    }
    ProbeVerdict::Fail { class: "circumvention-multi-layer-blocked".to_string() }
}
