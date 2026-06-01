//! TDD test file for DomainReachabilityProbe.
//!
//! Each `#[test]` exercises one row of the verdict matrix specified in the
//! goal. The `equivalence` test pins that the probe covers exactly the same
//! `ProbeResult.outcome` statuses the previous scheduled domain-reachability
//! path produced.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_probes::probes::domain_reachability::{
    DOMAIN_REACHABILITY_PROBE_ID, DomainReachabilityOutcome, DomainReachabilityProbe,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

fn expected_verdict(outcome: DomainReachabilityOutcome) -> ProbeVerdict {
    match outcome {
        DomainReachabilityOutcome::TlsOk | DomainReachabilityOutcome::HttpOk => ProbeVerdict::Pass,
        DomainReachabilityOutcome::TlsEchOnly => ProbeVerdict::Fail { class: "domain-sni-blocked".to_string() },
        DomainReachabilityOutcome::TlsVersionSplit => {
            ProbeVerdict::Fail { class: "domain-tls-version-split".to_string() }
        }
        DomainReachabilityOutcome::TlsCertInvalid => {
            ProbeVerdict::Fail { class: "domain-tls-cert-invalid".to_string() }
        }
        DomainReachabilityOutcome::HttpBlockpage => ProbeVerdict::Fail { class: "domain-http-blockpage".to_string() },
        DomainReachabilityOutcome::Unreachable => ProbeVerdict::Fail { class: "domain-unreachable".to_string() },
    }
}

#[test]
fn id_and_family_are_correct() {
    let probe = DomainReachabilityProbe::new("youtube.com".to_string(), DomainReachabilityOutcome::TlsOk);
    assert_eq!(probe.id(), DOMAIN_REACHABILITY_PROBE_ID);
    assert_eq!(probe.family(), ProbeTaskFamily::Web);
    assert_eq!(probe.host(), "youtube.com");
}

#[test]
fn every_outcome_maps_to_expected_verdict() {
    for &outcome in DomainReachabilityOutcome::ALL {
        let probe = DomainReachabilityProbe::new("youtube.com".to_string(), outcome);
        let result = probe.run(&ProbeContext::empty());
        assert_eq!(result.probe_id, DOMAIN_REACHABILITY_PROBE_ID);
        assert_eq!(result.family, ProbeTaskFamily::Web);
        assert_eq!(result.verdict, expected_verdict(outcome), "unexpected verdict for {outcome:?}");
    }
}

#[test]
fn equivalence_scheduled_outcome_strings_are_pinned() {
    // Pins that the probe covers exactly the same `ProbeResult.outcome`
    // statuses the previous scheduled domain-reachability path produced.
    for &outcome in DomainReachabilityOutcome::ALL {
        let expected = match outcome {
            DomainReachabilityOutcome::TlsOk => "tls_ok",
            DomainReachabilityOutcome::HttpOk => "http_ok",
            DomainReachabilityOutcome::TlsEchOnly => "tls_ech_only",
            DomainReachabilityOutcome::TlsVersionSplit => "tls_version_split",
            DomainReachabilityOutcome::TlsCertInvalid => "tls_cert_invalid",
            DomainReachabilityOutcome::HttpBlockpage => "http_blockpage",
            DomainReachabilityOutcome::Unreachable => "unreachable",
        };
        assert_eq!(outcome.scheduled_outcome(), expected, "scheduled_outcome drift for {outcome:?}");
    }
}
