//! Service-reachability classifier wrapped as a Probe.
//!
//! Walks per-layer captured outcomes (DNS / TCP / TLS / HTTP) for a
//! user-visible service (e.g. YouTube, Telegram web, Signal API) and
//! reports a single verdict that the diagnostics runner can hand back
//! to the user.
//!
//! ## Verdict mapping (first match wins)
//!
//! 1. `Inconclusive { reason: "no layer outcomes captured" }` — empty input.
//! 2. `Inconclusive { reason: "every layer hit a setup error" }` — every
//!    captured layer status is `SetupError`.
//! 3. `Pass` — every non-setup layer is `Ok`.
//! 4. `Fail { class: "service-<layer>-blocked" }` — exactly one layer is
//!    `Failed`. Layer tag lower-cased (e.g. `dns`, `tcp`, `tls`, `http`).
//! 5. `Fail { class: "service-multi-layer-blocked" }` — two or more layers
//!    are `Failed`.
//!
//! `SetupError` layers do not count toward the multi-layer threshold —
//! they mark "harness was unable to evaluate this layer", not "the layer
//! is broken on the wire".

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier.
pub const SERVICE_REACHABILITY_PROBE_ID: &str = "service_reachability";

/// One layer of the end-to-end reachability check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServiceLayer {
    /// DNS resolution for the service host.
    Dns,
    /// TCP connect to the service endpoint.
    Tcp,
    /// TLS handshake against the service SNI.
    Tls,
    /// Bootstrap HTTP request against the service URL.
    Http,
}

impl ServiceLayer {
    fn class_tag(self) -> &'static str {
        match self {
            ServiceLayer::Dns => "dns",
            ServiceLayer::Tcp => "tcp",
            ServiceLayer::Tls => "tls",
            ServiceLayer::Http => "http",
        }
    }
}

/// Per-layer outcome status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LayerStatus {
    /// Layer executed successfully.
    Ok,
    /// Layer executed but the wire-level check failed.
    Failed {
        /// Short description of the failure for triage.
        detail: String,
    },
    /// The harness could not run this layer (e.g. missing dependency).
    /// Does not count as a wire-level failure.
    SetupError {
        /// Short description of the setup error.
        detail: String,
    },
}

/// Outcome record for a single layer.
#[derive(Debug, Clone)]
pub struct ServiceLayerOutcome {
    /// Which layer this outcome describes.
    pub layer: ServiceLayer,
    /// Layer status.
    pub status: LayerStatus,
}

/// Pure [`Probe`] adapter for service reachability.
#[derive(Debug, Clone)]
pub struct ServiceReachabilityProbe {
    service_id: String,
    layers: Vec<ServiceLayerOutcome>,
}

impl ServiceReachabilityProbe {
    /// Construct from a service identifier (for evidence) and a list of
    /// captured per-layer outcomes.
    pub fn new(service_id: String, layers: Vec<ServiceLayerOutcome>) -> Self {
        Self { service_id, layers }
    }

    /// Service identifier passed to [`Self::new`]. Exposed so consumers
    /// can correlate the probe outcome with the requested service.
    pub fn service_id(&self) -> &str {
        &self.service_id
    }
}

impl Probe for ServiceReachabilityProbe {
    fn id(&self) -> &'static str {
        SERVICE_REACHABILITY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Service
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(&self.layers);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

fn classify_verdict(layers: &[ServiceLayerOutcome]) -> ProbeVerdict {
    if layers.is_empty() {
        return ProbeVerdict::Inconclusive { reason: "no layer outcomes captured".to_string() };
    }
    let all_setup = layers.iter().all(|o| matches!(o.status, LayerStatus::SetupError { .. }));
    if all_setup {
        return ProbeVerdict::Inconclusive { reason: "every layer hit a setup error".to_string() };
    }
    let failed: Vec<&ServiceLayerOutcome> =
        layers.iter().filter(|o| matches!(o.status, LayerStatus::Failed { .. })).collect();
    if failed.is_empty() {
        return ProbeVerdict::Pass;
    }
    if failed.len() == 1 {
        return ProbeVerdict::Fail { class: format!("service-{}-blocked", failed[0].layer.class_tag()) };
    }
    ProbeVerdict::Fail { class: "service-multi-layer-blocked".to_string() }
}
