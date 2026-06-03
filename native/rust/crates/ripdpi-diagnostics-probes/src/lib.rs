//! ripdpi-diagnostics-probes
//!
//! Probe definitions for the RIPDPI diagnostics subsystem. A *probe* is a
//! single, named check that exercises one classification class (HTTP
//! injection, MTProto DC reachability, DoH availability, ECH negotiation,
//! QUIC PMTU, etc.) and produces a [`ProbeOutcome`].
//!
//! ## Why this crate exists
//!
//! The `ripdpi-diagnostics-runner` crate orchestrates probes; the
//! per-classification probes themselves used to be ad-hoc helpers scattered
//! across `ripdpi-monitor-engine` and `ripdpi-diagnostics-runner`. This crate
//! gives them a shared, narrow trait surface so that:
//!
//! 1. New probes land in one place with a single contract.
//! 2. The runner can iterate probes uniformly without per-probe glue.
//! 3. Probes carry an explicit [`ProbeContext`] that pins the probe to the
//!    user's *active* DNS / relay / desync policy — closing the historical
//!    "probe always uses Cloudflare DoH" blind spot (audit finding A-1).
//!
//! The `compat-facade` feature is on by default as an empty compatibility
//! marker. Root exports are unconditional today.

#![forbid(unsafe_code)]
#![deny(missing_docs)]

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

/// Active-path context handed to every probe.
///
/// This is the explicit hook that makes probes validate the *user's* active
/// path instead of a hard-coded baseline. A probe is free to ignore fields it
/// does not need, but the runner is responsible for filling this in from the
/// current `ConnectionPolicy` before invoking the probe.
#[derive(Debug, Clone)]
pub struct ProbeContext {
    /// Stable identifier for the network scope the probe is running in.
    /// Probes should treat this as opaque; the runner uses it to scope
    /// caches and recommendation persistence.
    pub network_scope_key: Option<String>,

    /// The user's active resolver hint (e.g. encrypted-DNS path identifier).
    /// `None` means "probe should use whatever the runtime decides", not
    /// "probe should fall back to a hard-coded public resolver".
    pub resolver_hint: Option<String>,

    /// The user's active relay / outbound identifier, when one is configured.
    /// `None` means direct path.
    pub relay_hint: Option<String>,

    /// Active desync strategy signature, when one is pinned. Probes that
    /// want to attribute a verdict to a specific strategy include this in
    /// their report so the classification subsystem can correlate.
    pub strategy_signature: Option<String>,
}

impl ProbeContext {
    /// Construct an empty context. Tests and CLI smoke runs use this; the
    /// production runner builds a populated context per probe invocation.
    pub fn empty() -> Self {
        Self { network_scope_key: None, resolver_hint: None, relay_hint: None, strategy_signature: None }
    }
}

/// Verdict produced by a single probe.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProbeVerdict {
    /// Probe completed and the observed behaviour matches the expected
    /// healthy path.
    Pass,
    /// Probe completed and the observed behaviour matches a known failure
    /// class. The `class` string is a probe-specific identifier that the
    /// classifier crate maps to a user-facing reason.
    Fail {
        /// Probe-defined failure class identifier (e.g. `"tspu-rst"`,
        /// `"sni-blocked"`, `"middlebox-html"`).
        class: String,
    },
    /// Probe could not reach a conclusion (transient I/O, timeout before
    /// any signal, unexpected upstream behaviour). Inconclusive results
    /// must not be used to drive automatic strategy changes.
    Inconclusive {
        /// Short human-readable reason. Not a stable contract.
        reason: String,
    },
}

/// Outcome of a single probe execution.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProbeOutcome {
    /// Stable identifier of the probe (matches [`Probe::id`]).
    pub probe_id: &'static str,
    /// Family the probe belongs to. Mirrors the contract enum so the runner
    /// can route outcomes without per-probe glue.
    pub family: ProbeTaskFamily,
    /// Verdict produced by the probe.
    pub verdict: ProbeVerdict,
}

/// A single named probe.
///
/// Probes are stateless: a probe implementation captures its parameters at
/// construction and the runner invokes [`Probe::run`] once per scheduling
/// tick. Concrete probes live in submodules and migrate in over time.
pub trait Probe {
    /// Stable identifier. Embedded in goldens and telemetry — treat as a
    /// public contract.
    fn id(&self) -> &'static str;

    /// Family this probe belongs to.
    fn family(&self) -> ProbeTaskFamily;

    /// Execute the probe in the supplied context.
    ///
    /// Implementations must not perform network I/O directly against
    /// hard-coded endpoints. Any endpoint the probe contacts must be
    /// derivable from `ctx` or from probe-construction parameters supplied
    /// by the runner.
    fn run(&self, ctx: &ProbeContext) -> ProbeOutcome;
}

pub mod probe_descriptor;
pub mod probes;
pub mod scheduled_inventory;

pub use probe_descriptor::{
    PROBE_DESCRIPTORS, ProbeDescriptor, ProbePathRequirement, descriptor_by_id, descriptor_by_probe_type,
    descriptors_for_family,
};
pub use probes::{
    CircumventionReachabilityProbe, DnsIntegrityProbe, DnsTamperingConfirmationProbe, DohJsonSurveyProbe,
    DohSurveyProbe, DomainReachabilityProbe, EchHandshakeProbe, HickoryRustlsEchHandshakeDriver,
    HttpInjectionOfflineProbe, HttpResponseBlockOfflineProbe, IpBlockSuspectProbe, MtprotoReachabilityProbe,
    NetworkEnvironmentProbe, QuicProbeOfflineProbe, ServiceReachabilityProbe, TcpFatHeaderProbe, ThroughputProbe,
    TlsAlertOfflineProbe,
};

#[cfg(feature = "compat-facade")]
/// Reserved compatibility namespace. Empty today.
pub mod compat {}

#[cfg(test)]
mod tests {
    use super::*;

    struct StubProbe;

    impl Probe for StubProbe {
        fn id(&self) -> &'static str {
            "stub-pass"
        }
        fn family(&self) -> ProbeTaskFamily {
            ProbeTaskFamily::Web
        }
        fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
            ProbeOutcome { probe_id: self.id(), family: self.family(), verdict: ProbeVerdict::Pass }
        }
    }

    #[test]
    fn probe_outcome_carries_probe_id_and_family() {
        let outcome = StubProbe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, "stub-pass");
        assert_eq!(outcome.family, ProbeTaskFamily::Web);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn empty_context_has_no_hints() {
        let ctx = ProbeContext::empty();
        assert!(ctx.network_scope_key.is_none());
        assert!(ctx.resolver_hint.is_none());
        assert!(ctx.relay_hint.is_none());
        assert!(ctx.strategy_signature.is_none());
    }

    #[test]
    fn fail_verdict_round_trips_class() {
        let v = ProbeVerdict::Fail { class: "tspu-rst".into() };
        match v {
            ProbeVerdict::Fail { class } => assert_eq!(class, "tspu-rst"),
            other => panic!("unexpected verdict {other:?}"),
        }
    }
}
