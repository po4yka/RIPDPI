//! IP-block-suspect classifier wrapped as a Probe.
//!
//! Adapts `ripdpi_diagnostics_classification::classify_ip_block_suspect`
//! to the [`Probe`] trait. The classifier is pure: it takes SYN-layer
//! counters captured upstream and returns a verdict that determines
//! whether subsequent retries are restricted to owned-stack arms.

use ripdpi_diagnostics_classification::classification::ip_block_suspect::{
    IpBlockSuspectInput, classify_ip_block_suspect,
};
use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_failure_classifier::IpBlockVerdict;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const IP_BLOCK_SUSPECT_PROBE_ID: &str = "ip_block_suspect";

/// Wraps the pure-function classifier; inputs are captured upstream by
/// the runner and supplied at construction.
#[derive(Debug, Clone)]
pub struct IpBlockSuspectProbe {
    input: IpBlockSuspectInput,
}

impl IpBlockSuspectProbe {
    /// Construct from upstream-captured SYN-layer counters and the
    /// second-flow guard status.
    pub fn new(input: IpBlockSuspectInput) -> Self {
        Self { input }
    }
}

impl Probe for IpBlockSuspectProbe {
    fn id(&self) -> &'static str {
        IP_BLOCK_SUSPECT_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Tcp
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_ip_block_suspect(self.input);
        let probe_verdict = match verdict.verdict {
            IpBlockVerdict::NotIpBlock => ProbeVerdict::Pass,
            IpBlockVerdict::IpBlockSuspect => ProbeVerdict::Fail { class: "ip-block-suspect".to_string() },
            IpBlockVerdict::PendingSecondFlow => {
                ProbeVerdict::Inconclusive { reason: "second-flow guard not yet passed".to_string() }
            }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict: probe_verdict }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn baseline() -> IpBlockSuspectInput {
        IpBlockSuspectInput {
            v4_syn_failures: 0,
            v4_syn_attempts: 0,
            v6_syn_failures: 0,
            v6_syn_attempts: 0,
            cdn_successes: 0,
            cdn_attempts: 0,
            attempt_budget: 0,
            second_flow_guard_passed: false,
        }
    }

    #[test]
    fn no_attempts_yields_pass() {
        let probe = IpBlockSuspectProbe::new(baseline());
        let outcome = probe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, IP_BLOCK_SUSPECT_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Tcp);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn full_block_with_guard_passed_yields_fail() {
        let input = IpBlockSuspectInput {
            v4_syn_failures: 3,
            v4_syn_attempts: 3,
            v6_syn_failures: 2,
            v6_syn_attempts: 2,
            cdn_successes: 0,
            cdn_attempts: 4,
            attempt_budget: 4,
            second_flow_guard_passed: true,
        };
        match IpBlockSuspectProbe::new(input).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "ip-block-suspect"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn full_block_without_guard_yields_inconclusive() {
        let input = IpBlockSuspectInput {
            v4_syn_failures: 3,
            v4_syn_attempts: 3,
            v6_syn_failures: 2,
            v6_syn_attempts: 2,
            cdn_successes: 0,
            cdn_attempts: 4,
            attempt_budget: 4,
            second_flow_guard_passed: false,
        };
        match IpBlockSuspectProbe::new(input).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("second-flow")),
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }
}
