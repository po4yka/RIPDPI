//! TDD test file for DnsIntegrityProbe.
//!
//! Each `#[test]` exercises one row of the verdict matrix specified in the
//! goal. The `equivalence` test pins that the probe covers exactly the same
//! `ProbeResult.outcome` statuses the previous scheduled DNS-integrity path
//! produced.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_probes::probes::dns_integrity::{
    DnsIntegrityOutcome, DnsIntegrityProbe, DNS_INTEGRITY_PROBE_ID,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

fn expected_verdict(outcome: DnsIntegrityOutcome) -> ProbeVerdict {
    match outcome {
        DnsIntegrityOutcome::DnsMatch
        | DnsIntegrityOutcome::UdpPlainDnsUnstable
        | DnsIntegrityOutcome::CompatibleDivergence => ProbeVerdict::Pass,
        DnsIntegrityOutcome::ExpectedMismatch => ProbeVerdict::Fail { class: "dns-expected-mismatch".to_string() },
        DnsIntegrityOutcome::SuspiciousDivergence => {
            ProbeVerdict::Fail { class: "dns-suspicious-divergence".to_string() }
        }
        DnsIntegrityOutcome::SinkholeSubstitution => {
            ProbeVerdict::Fail { class: "dns-sinkhole-substitution".to_string() }
        }
        DnsIntegrityOutcome::NxdomainMismatch => ProbeVerdict::Fail { class: "dns-nxdomain-mismatch".to_string() },
        DnsIntegrityOutcome::UdpBlocked => ProbeVerdict::Fail { class: "dns-udp-blocked".to_string() },
        DnsIntegrityOutcome::OracleUnavailable => {
            ProbeVerdict::Inconclusive { reason: "encrypted DNS oracle unavailable".to_string() }
        }
        DnsIntegrityOutcome::UdpTimeoutTransient => {
            ProbeVerdict::Inconclusive { reason: "transient UDP DNS timeout".to_string() }
        }
        DnsIntegrityOutcome::UdpSkippedOrBlocked => {
            ProbeVerdict::Inconclusive { reason: "in-path UDP DNS skipped or blocked".to_string() }
        }
        DnsIntegrityOutcome::DnsUnavailable => {
            ProbeVerdict::Inconclusive { reason: "no resolver reachable".to_string() }
        }
    }
}

#[test]
fn id_and_family_are_correct() {
    let probe = DnsIntegrityProbe::new("example.com".to_string(), DnsIntegrityOutcome::DnsMatch);
    assert_eq!(probe.id(), DNS_INTEGRITY_PROBE_ID);
    assert_eq!(probe.family(), ProbeTaskFamily::Dns);
    assert_eq!(probe.domain(), "example.com");
}

#[test]
fn every_outcome_maps_to_expected_verdict() {
    for &outcome in DnsIntegrityOutcome::ALL {
        let probe = DnsIntegrityProbe::new("example.com".to_string(), outcome);
        let result = probe.run(&ProbeContext::empty());
        assert_eq!(result.probe_id, DNS_INTEGRITY_PROBE_ID);
        assert_eq!(result.family, ProbeTaskFamily::Dns);
        assert_eq!(result.verdict, expected_verdict(outcome), "unexpected verdict for {outcome:?}");
    }
}

#[test]
fn equivalence_scheduled_outcome_strings_are_pinned() {
    // Pins that the probe covers exactly the same `ProbeResult.outcome`
    // statuses the previous scheduled DNS-integrity path produced.
    for &outcome in DnsIntegrityOutcome::ALL {
        let expected = match outcome {
            DnsIntegrityOutcome::DnsMatch => "dns_match",
            DnsIntegrityOutcome::UdpPlainDnsUnstable => "udp_plain_dns_unstable",
            DnsIntegrityOutcome::CompatibleDivergence => "dns_compatible_divergence",
            DnsIntegrityOutcome::ExpectedMismatch => "dns_expected_mismatch",
            DnsIntegrityOutcome::SuspiciousDivergence => "dns_suspicious_divergence",
            DnsIntegrityOutcome::SinkholeSubstitution => "dns_sinkhole_substitution",
            DnsIntegrityOutcome::NxdomainMismatch => "dns_nxdomain_mismatch",
            DnsIntegrityOutcome::UdpBlocked => "udp_blocked",
            DnsIntegrityOutcome::OracleUnavailable => "dns_oracle_unavailable",
            DnsIntegrityOutcome::UdpTimeoutTransient => "udp_timeout_transient",
            DnsIntegrityOutcome::UdpSkippedOrBlocked => "udp_skipped_or_blocked",
            DnsIntegrityOutcome::DnsUnavailable => "dns_unavailable",
        };
        assert_eq!(outcome.scheduled_outcome(), expected, "scheduled_outcome drift for {outcome:?}");
    }
}
