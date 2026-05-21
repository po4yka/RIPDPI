//! DNS-integrity classifier wrapped as a Probe.
//!
//! Pure offline classifier for the DNS-integrity stage: a probe that
//! compares the answer a plaintext / in-path resolver returns against an
//! encrypted-DNS oracle to detect tampering, sinkholing, NXDOMAIN
//! injection, and UDP-port blocking.
//!
//! ## Migration anchor
//!
//! This probe mirrors the scheduled connectivity stage emitted by
//! `run_dns_probe` / `classify_dns_probe_outcome` with `probe_type:
//! "dns_integrity"`. The [`DnsIntegrityOutcome::scheduled_outcome`]
//! strings are the exact `ProbeResult.outcome` values the previous
//! scheduled runner produced; they are the equivalence anchor for the
//! Probe-trait migration.
//!
//! The probe captures a [`DnsIntegrityOutcome`] at construction and
//! [`Probe::run`] performs no I/O.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier.
pub const DNS_INTEGRITY_PROBE_ID: &str = "dns_integrity";

/// Captured outcome of a DNS-integrity probe attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsIntegrityOutcome {
    /// The in-path resolver answer matched the encrypted oracle.
    /// Scheduled `ProbeResult.outcome`: `"dns_match"`.
    DnsMatch,
    /// Plaintext UDP DNS was unstable but no tampering was detected.
    /// Scheduled `ProbeResult.outcome`: `"udp_plain_dns_unstable"`.
    UdpPlainDnsUnstable,
    /// The answers diverged in a benign, compatible way (e.g. CDN
    /// geo-steering) — not a block.
    /// Scheduled `ProbeResult.outcome`: `"dns_compatible_divergence"`.
    CompatibleDivergence,
    /// The answers diverged in a way consistent with an expected,
    /// known mismatch.
    /// Scheduled `ProbeResult.outcome`: `"dns_expected_mismatch"`.
    ExpectedMismatch,
    /// The answers diverged suspiciously — likely tampering.
    /// Scheduled `ProbeResult.outcome`: `"dns_suspicious_divergence"`.
    SuspiciousDivergence,
    /// The in-path resolver substituted a sinkhole address.
    /// Scheduled `ProbeResult.outcome`: `"dns_sinkhole_substitution"`.
    SinkholeSubstitution,
    /// The in-path resolver returned NXDOMAIN where the oracle resolved.
    /// Scheduled `ProbeResult.outcome`: `"dns_nxdomain_mismatch"`.
    NxdomainMismatch,
    /// UDP DNS to the resolver was outright blocked.
    /// Scheduled `ProbeResult.outcome`: `"udp_blocked"`.
    UdpBlocked,
    /// The encrypted-DNS oracle was unavailable, so no comparison could
    /// be made.
    /// Scheduled `ProbeResult.outcome`: `"dns_oracle_unavailable"`.
    OracleUnavailable,
    /// A transient UDP DNS timeout prevented a conclusion.
    /// Scheduled `ProbeResult.outcome`: `"udp_timeout_transient"`.
    UdpTimeoutTransient,
    /// In-path UDP DNS was skipped or blocked, so no conclusion could be
    /// drawn.
    /// Scheduled `ProbeResult.outcome`: `"udp_skipped_or_blocked"`.
    UdpSkippedOrBlocked,
    /// No resolver was reachable at all.
    /// Scheduled `ProbeResult.outcome`: `"dns_unavailable"`.
    DnsUnavailable,
}

impl DnsIntegrityOutcome {
    /// Every variant of [`DnsIntegrityOutcome`], in declaration order.
    pub const ALL: &'static [Self] = &[
        Self::DnsMatch,
        Self::UdpPlainDnsUnstable,
        Self::CompatibleDivergence,
        Self::ExpectedMismatch,
        Self::SuspiciousDivergence,
        Self::SinkholeSubstitution,
        Self::NxdomainMismatch,
        Self::UdpBlocked,
        Self::OracleUnavailable,
        Self::UdpTimeoutTransient,
        Self::UdpSkippedOrBlocked,
        Self::DnsUnavailable,
    ];

    /// The exact `ProbeResult.outcome` string the upstream scheduled
    /// runner emits for this condition. This is the migration's
    /// equivalence anchor — do not change without updating the scheduled
    /// `classify_dns_probe_outcome` source in lockstep.
    pub fn scheduled_outcome(self) -> &'static str {
        match self {
            Self::DnsMatch => "dns_match",
            Self::UdpPlainDnsUnstable => "udp_plain_dns_unstable",
            Self::CompatibleDivergence => "dns_compatible_divergence",
            Self::ExpectedMismatch => "dns_expected_mismatch",
            Self::SuspiciousDivergence => "dns_suspicious_divergence",
            Self::SinkholeSubstitution => "dns_sinkhole_substitution",
            Self::NxdomainMismatch => "dns_nxdomain_mismatch",
            Self::UdpBlocked => "udp_blocked",
            Self::OracleUnavailable => "dns_oracle_unavailable",
            Self::UdpTimeoutTransient => "udp_timeout_transient",
            Self::UdpSkippedOrBlocked => "udp_skipped_or_blocked",
            Self::DnsUnavailable => "dns_unavailable",
        }
    }
}

/// Pure [`Probe`] adapter for the DNS-integrity stage.
#[derive(Debug, Clone)]
pub struct DnsIntegrityProbe {
    domain: String,
    outcome: DnsIntegrityOutcome,
}

impl DnsIntegrityProbe {
    /// Construct from a target domain (for evidence) and the captured
    /// DNS-integrity outcome.
    pub fn new(domain: String, outcome: DnsIntegrityOutcome) -> Self {
        Self { domain, outcome }
    }

    /// Domain identifier passed to [`Self::new`]. Exposed so consumers
    /// can correlate the probe outcome with the requested domain.
    pub fn domain(&self) -> &str {
        &self.domain
    }
}

impl Probe for DnsIntegrityProbe {
    fn id(&self) -> &'static str {
        DNS_INTEGRITY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Dns
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(self.outcome);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

fn classify_verdict(outcome: DnsIntegrityOutcome) -> ProbeVerdict {
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
