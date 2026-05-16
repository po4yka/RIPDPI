//! Encrypted-DNS tampering classifier wrapped as a Probe.
//!
//! Adapts `ripdpi_failure_classifier::confirm_dns_tampering` to the
//! [`Probe`] trait. The upstream classifier compares an
//! upstream-supplied target IP (e.g. the IP the system resolver
//! returned) against an encrypted-DNS answer set; if the target is
//! absent from the encrypted answers, that is strong evidence of
//! DNS tampering on the system path.

use std::net::IpAddr;

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_failure_classifier::confirm_dns_tampering;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const DNS_TAMPERING_CONFIRMATION_PROBE_ID: &str = "dns_tampering_confirmation";

/// Wraps the offline encrypted-DNS tampering confirmation classifier.
#[derive(Debug, Clone)]
pub struct DnsTamperingConfirmationProbe {
    host: String,
    target_ip: IpAddr,
    encrypted_answers: Vec<IpAddr>,
    source_label: String,
}

impl DnsTamperingConfirmationProbe {
    /// Construct from upstream-captured DNS evidence:
    ///
    /// - `host`: the queried host.
    /// - `target_ip`: the IP the unprotected resolver returned.
    /// - `encrypted_answers`: the IPs the encrypted-DNS path returned.
    ///   When empty the classifier returns Inconclusive (no signal).
    /// - `source_label`: short string describing the encrypted source,
    ///   embedded in the failure evidence for triage.
    pub fn new(
        host: impl Into<String>,
        target_ip: IpAddr,
        encrypted_answers: Vec<IpAddr>,
        source_label: impl Into<String>,
    ) -> Self {
        Self { host: host.into(), target_ip, encrypted_answers, source_label: source_label.into() }
    }
}

impl Probe for DnsTamperingConfirmationProbe {
    fn id(&self) -> &'static str {
        DNS_TAMPERING_CONFIRMATION_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Dns
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        if self.encrypted_answers.is_empty() {
            return ProbeOutcome {
                probe_id: self.id(),
                family: self.family(),
                verdict: ProbeVerdict::Inconclusive { reason: "encrypted-DNS answer set is empty".to_string() },
            };
        }
        let verdict =
            match confirm_dns_tampering(&self.host, self.target_ip, &self.encrypted_answers, &self.source_label) {
                None => ProbeVerdict::Pass,
                Some(_failure) => ProbeVerdict::Fail { class: "dns-tampering".to_string() },
            };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;

    use super::*;

    #[test]
    fn target_in_encrypted_answers_yields_pass() {
        let probe = DnsTamperingConfirmationProbe::new(
            "example.com",
            IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34)),
            vec![IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34))],
            "cloudflare-doh",
        );
        let outcome = probe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, DNS_TAMPERING_CONFIRMATION_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Dns);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn target_absent_from_encrypted_answers_yields_fail() {
        let probe = DnsTamperingConfirmationProbe::new(
            "example.com",
            IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)),
            vec![IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34))],
            "cloudflare-doh",
        );
        match probe.run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "dns-tampering"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn empty_encrypted_answers_yields_inconclusive() {
        let probe = DnsTamperingConfirmationProbe::new(
            "example.com",
            IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)),
            Vec::new(),
            "cloudflare-doh",
        );
        match probe.run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("empty")),
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }
}
