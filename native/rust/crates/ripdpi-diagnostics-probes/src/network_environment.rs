//! Network-environment scan stage wrapped as a Probe.
//!
//! `network_environment` is the diagnostics scan's first stage. It does not
//! classify connectivity — it captures the OS-reported network environment
//! (transport, validation, captive portal, DNS, Wi-Fi / cellular detail) into
//! a synthetic [`ProbeResult`] whose `probe_type` is `"network_environment"`.
//!
//! ## Adapter, not a re-implementation
//!
//! The `NetworkSnapshot -> ProbeResult` report-building logic lives in
//! `ripdpi-diagnostics-runner` (`build_network_environment_probe`). This
//! module does NOT duplicate it. [`NetworkEnvironmentProbe`] is a thin adapter:
//! it is constructed from an already-built `network_environment`
//! [`ProbeResult`] and re-presents it through the [`crate::Probe`] trait so the
//! scheduled-probe inventory can record this stage as Probe-backed. The output
//! shape ([`ProbeResult`]) is carried through verbatim.
//!
//! ## Family
//!
//! [`ProbeTaskFamily`] has no `Environment` variant, and adding one would
//! change the diagnostics contract enum, so [`crate::Probe::family`] reports
//! [`ProbeTaskFamily::Service`] as the nearest fit. The family is inert for
//! this probe today — nothing routes `network_environment` outcomes by family
//! (the unified `ProbeDescriptor` table is not built yet).

use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Equal to the `ProbeResult.probe_type` the
/// `network_environment` stage emits.
pub const NETWORK_ENVIRONMENT_PROBE_ID: &str = "network_environment";

/// `ProbeResult.outcome` emitted when a usable network transport is present.
const OUTCOME_NETWORK_AVAILABLE: &str = "network_available";

/// `ProbeResult.outcome` emitted when the OS reports no network and no VPN.
const OUTCOME_NETWORK_UNAVAILABLE: &str = "network_unavailable";

/// `ProbeResult.outcome` emitted when the OS reports no transport but a VPN
/// session was active — the tunnel is down, the physical network is unknown.
const OUTCOME_VPN_TUNNEL_DOWN: &str = "vpn_tunnel_down";

/// [`Probe`] adapter over a `network_environment` [`ProbeResult`].
///
/// Constructed from the output of the runner's network-environment report
/// builder; it carries that [`ProbeResult`] unchanged and exposes it through
/// the [`Probe`] trait.
#[derive(Debug, Clone)]
pub struct NetworkEnvironmentProbe {
    report: ProbeResult,
}

impl NetworkEnvironmentProbe {
    /// Adapt an already-built `network_environment` [`ProbeResult`] — the
    /// output of `ripdpi-diagnostics-runner`'s `build_network_environment_probe`
    /// — into a [`Probe`].
    ///
    /// The `report` is carried verbatim: its `probe_type`, `target`,
    /// `outcome`, and `details` are not modified. Callers pass a [`ProbeResult`]
    /// whose `probe_type` is `"network_environment"`; [`Self::id`] reports
    /// [`NETWORK_ENVIRONMENT_PROBE_ID`] regardless of the carried value.
    pub fn new(report: ProbeResult) -> Self {
        Self { report }
    }

    /// The carried `network_environment` [`ProbeResult`], unchanged.
    pub fn report(&self) -> &ProbeResult {
        &self.report
    }
}

impl Probe for NetworkEnvironmentProbe {
    fn id(&self) -> &'static str {
        NETWORK_ENVIRONMENT_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Service
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        ProbeOutcome {
            probe_id: self.id(),
            family: self.family(),
            verdict: classify_verdict(self.report.outcome.as_str()),
        }
    }
}

/// Map a `network_environment` `ProbeResult.outcome` string to a probe verdict.
///
/// The three recognised strings are exactly those `build_network_environment_probe`
/// emits; an unrecognised outcome is reported as `Inconclusive` rather than
/// guessed.
fn classify_verdict(outcome: &str) -> ProbeVerdict {
    match outcome {
        OUTCOME_NETWORK_AVAILABLE => ProbeVerdict::Pass,
        OUTCOME_NETWORK_UNAVAILABLE => ProbeVerdict::Fail { class: "network-unavailable".to_string() },
        OUTCOME_VPN_TUNNEL_DOWN => {
            ProbeVerdict::Inconclusive { reason: "vpn tunnel down; physical network state unknown".to_string() }
        }
        other => ProbeVerdict::Inconclusive { reason: format!("unrecognized network_environment outcome: {other}") },
    }
}

#[cfg(test)]
mod tests {
    use ripdpi_diagnostics_contracts::ProbeDetail;

    use super::*;

    fn report(outcome: &str) -> ProbeResult {
        ProbeResult {
            probe_type: NETWORK_ENVIRONMENT_PROBE_ID.to_string(),
            target: "wifi".to_string(),
            outcome: outcome.to_string(),
            details: vec![ProbeDetail { key: "transport".to_string(), value: "wifi".to_string() }],
        }
    }

    #[test]
    fn probe_id_is_stable_and_non_empty() {
        assert_eq!(NETWORK_ENVIRONMENT_PROBE_ID, "network_environment");
        assert!(!NETWORK_ENVIRONMENT_PROBE_ID.is_empty());
        let probe = NetworkEnvironmentProbe::new(report(OUTCOME_NETWORK_AVAILABLE));
        assert_eq!(probe.id(), NETWORK_ENVIRONMENT_PROBE_ID);
    }

    #[test]
    fn run_reports_the_probe_id_and_family() {
        let probe = NetworkEnvironmentProbe::new(report(OUTCOME_NETWORK_AVAILABLE));
        let outcome = probe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, NETWORK_ENVIRONMENT_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Service);
    }

    #[test]
    fn report_is_carried_through_verbatim() {
        let source = report(OUTCOME_NETWORK_AVAILABLE);
        let probe = NetworkEnvironmentProbe::new(source.clone());

        assert_eq!(probe.report().probe_type, source.probe_type);
        assert_eq!(probe.report().target, source.target);
        assert_eq!(probe.report().outcome, source.outcome);
        assert_eq!(probe.report().details.len(), source.details.len());
        assert_eq!(probe.report().details[0].key, "transport");
        assert_eq!(probe.report().details[0].value, "wifi");
    }

    #[test]
    fn network_available_maps_to_pass() {
        let probe = NetworkEnvironmentProbe::new(report(OUTCOME_NETWORK_AVAILABLE));
        assert_eq!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn network_unavailable_maps_to_fail() {
        let probe = NetworkEnvironmentProbe::new(report(OUTCOME_NETWORK_UNAVAILABLE));
        match probe.run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "network-unavailable"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn vpn_tunnel_down_maps_to_inconclusive() {
        let probe = NetworkEnvironmentProbe::new(report(OUTCOME_VPN_TUNNEL_DOWN));
        assert!(matches!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Inconclusive { .. }));
    }

    #[test]
    fn unrecognized_outcome_maps_to_inconclusive() {
        let probe = NetworkEnvironmentProbe::new(report("something_new"));
        assert!(matches!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Inconclusive { .. }));
    }
}
