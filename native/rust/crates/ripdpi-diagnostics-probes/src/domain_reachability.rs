//! Domain-reachability classifier wrapped as a Probe.
//!
//! Pure offline classifier for the per-domain web reachability stage: a
//! probe that resolves a domain, opens TLS to it, and issues a bootstrap
//! HTTP request to detect SNI-based blocking, TLS-version splitting,
//! certificate substitution, and HTTP block pages.
//!
//! ## Migration anchor
//!
//! This probe mirrors the scheduled connectivity stage emitted by
//! `run_domain_probe` with `probe_type: "domain_reachability"`. The
//! [`DomainReachabilityOutcome::scheduled_outcome`] strings are the exact
//! `ProbeResult.outcome` values the previous scheduled runner produced;
//! they are the equivalence anchor for the Probe-trait migration.
//!
//! The probe captures a [`DomainReachabilityOutcome`] at construction and
//! [`Probe::run`] performs no I/O.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier.
pub const DOMAIN_REACHABILITY_PROBE_ID: &str = "domain_reachability";

/// Captured outcome of a domain-reachability probe attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DomainReachabilityOutcome {
    /// TLS handshake completed cleanly against the domain SNI.
    /// Scheduled `ProbeResult.outcome`: `"tls_ok"`.
    TlsOk,
    /// HTTP bootstrap request completed with a healthy response.
    /// Scheduled `ProbeResult.outcome`: `"http_ok"`.
    HttpOk,
    /// TLS only succeeded when the SNI was hidden via ECH — the plaintext
    /// SNI path is blocked.
    /// Scheduled `ProbeResult.outcome`: `"tls_ech_only"`.
    TlsEchOnly,
    /// TLS behaviour diverges by negotiated protocol version — a version
    /// split consistent with selective middlebox interference.
    /// Scheduled `ProbeResult.outcome`: `"tls_version_split"`.
    TlsVersionSplit,
    /// The presented certificate did not validate against the expected
    /// chain — certificate substitution.
    /// Scheduled `ProbeResult.outcome`: `"tls_cert_invalid"`.
    TlsCertInvalid,
    /// The HTTP response was an injected block page rather than the real
    /// upstream content.
    /// Scheduled `ProbeResult.outcome`: `"http_blockpage"`.
    HttpBlockpage,
    /// The domain could not be reached at all.
    /// Scheduled `ProbeResult.outcome`: `"unreachable"`.
    Unreachable,
}

impl DomainReachabilityOutcome {
    /// Every variant of [`DomainReachabilityOutcome`], in declaration order.
    pub const ALL: &'static [Self] = &[
        Self::TlsOk,
        Self::HttpOk,
        Self::TlsEchOnly,
        Self::TlsVersionSplit,
        Self::TlsCertInvalid,
        Self::HttpBlockpage,
        Self::Unreachable,
    ];

    /// The exact `ProbeResult.outcome` string the upstream scheduled
    /// runner emits for this condition. This is the migration's
    /// equivalence anchor — do not change without updating the scheduled
    /// `run_domain_probe` source in lockstep.
    pub fn scheduled_outcome(self) -> &'static str {
        match self {
            Self::TlsOk => "tls_ok",
            Self::HttpOk => "http_ok",
            Self::TlsEchOnly => "tls_ech_only",
            Self::TlsVersionSplit => "tls_version_split",
            Self::TlsCertInvalid => "tls_cert_invalid",
            Self::HttpBlockpage => "http_blockpage",
            Self::Unreachable => "unreachable",
        }
    }
}

/// Pure [`Probe`] adapter for the domain-reachability stage.
#[derive(Debug, Clone)]
pub struct DomainReachabilityProbe {
    host: String,
    outcome: DomainReachabilityOutcome,
}

impl DomainReachabilityProbe {
    /// Construct from a target host (for evidence) and the captured
    /// domain-reachability outcome.
    pub fn new(host: String, outcome: DomainReachabilityOutcome) -> Self {
        Self { host, outcome }
    }

    /// Host identifier passed to [`Self::new`]. Exposed so consumers can
    /// correlate the probe outcome with the requested domain.
    pub fn host(&self) -> &str {
        &self.host
    }
}

impl Probe for DomainReachabilityProbe {
    fn id(&self) -> &'static str {
        DOMAIN_REACHABILITY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Web
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(self.outcome);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

fn classify_verdict(outcome: DomainReachabilityOutcome) -> ProbeVerdict {
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
