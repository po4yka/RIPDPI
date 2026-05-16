//! ECH-handshake probe: attempts TLS with Encrypted Client Hello and reports
//! whether ECH negotiation succeeded.
//!
//! The module is split into two halves:
//!
//! * **Pure Probe adapter** ([`EchHandshakeProbe`]) — constructed from
//!   upstream-captured [`EchHandshakeOutcome`] evidence; `Probe::run` is pure
//!   (no I/O). Use this in unit tests and in the runner after the async half
//!   completes.
//!
//! * **Async Runner** ([`EchHandshakeRunner`]) — performs the live DNS + TLS
//!   work and returns a populated `EchHandshakeProbe` ready to be scheduled.
//!
// PARENT-INTEGRATION: EchHandshakeRunner is a stub that returns
// SetupError{"ech-runner-not-implemented"} because rustls 0.23 ECH support
// is in the `experimental` feature flag (not yet in the published release at
// the time of writing). Wire this up once `rustls::ClientConfig::ech_mode` is
// stabilised and added to the workspace's Cargo.toml. The pure Probe adapter
// and all unit tests are fully functional today.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const ECH_HANDSHAKE_PROBE_ID: &str = "ech_handshake";

/// Maximum byte length of the sanitized `detail` field embedded in a
/// `"ech-handshake-failure::"` class string.
const MAX_DETAIL_LEN: usize = 64;

// ---------------------------------------------------------------------------
// Outcome enum
// ---------------------------------------------------------------------------

/// Outcome of an ECH-handshake attempt, captured by [`EchHandshakeRunner`]
/// and consumed by [`EchHandshakeProbe::run`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EchHandshakeOutcome {
    /// ECH config retrieved from DNS HTTPS RR and the TLS handshake completed
    /// with ECH accepted by the server.
    EchAccepted {
        /// ALPN protocol negotiated during the handshake, if any
        /// (e.g. `"h2"`, `"http/1.1"`).
        selected_alpn: Option<String>,
    },
    /// TLS handshake succeeded but the server did not accept ECH and returned
    /// retry configs instead.
    EchRejectedWithRetryConfig,
    /// No HTTPS resource record was found for the target host, or the record
    /// contained no `ech=` parameter — ECH cannot be attempted.
    NoEchConfig,
    /// TLS handshake failed for a reason unrelated to ECH (timeout, TCP RST,
    /// certificate mismatch, etc.).
    HandshakeFailure {
        /// Short description of the failure, sanitized before embedding in the
        /// `ProbeVerdict` class string.
        detail: String,
    },
    /// The probe could not run at all (resolver unavailable, internal error,
    /// runner not implemented).
    SetupError {
        /// Short description of why the probe could not run.
        detail: String,
    },
}

// ---------------------------------------------------------------------------
// Pure Probe adapter
// ---------------------------------------------------------------------------

/// Offline ECH-handshake probe.
///
/// Wraps a pre-captured [`EchHandshakeOutcome`] and maps it to a
/// [`ProbeVerdict`] without performing any network I/O. Construct via
/// [`EchHandshakeProbe::new`] after [`EchHandshakeRunner::attempt`] returns,
/// or directly in tests with a synthetic outcome.
#[derive(Debug, Clone)]
pub struct EchHandshakeProbe {
    /// The hostname against which ECH was (or would have been) attempted.
    pub target_host: String,
    /// Pre-captured outcome produced by the async runner.
    pub outcome: EchHandshakeOutcome,
    /// Optional relay identifier that was active when the runner attempted the
    /// handshake. Surfaces `ctx.relay_hint` so callers can correlate verdicts
    /// with the user's outbound path. `None` when the runner used the direct
    /// path.
    pub relay_hint_used: Option<String>,
}

impl EchHandshakeProbe {
    /// Construct from a target hostname and a pre-captured outcome.
    ///
    /// `relay_hint_used` should be `None` when the runner used the direct
    /// path, or the relay identifier from [`ProbeContext::relay_hint`] when a
    /// relay was active.
    pub fn new(target_host: impl Into<String>, outcome: EchHandshakeOutcome, relay_hint_used: Option<String>) -> Self {
        Self { target_host: target_host.into(), outcome, relay_hint_used }
    }
}

impl Probe for EchHandshakeProbe {
    fn id(&self) -> &'static str {
        ECH_HANDSHAKE_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        // ECH is a TLS-layer feature exercised over HTTPS; Web is the closest
        // umbrella family for TLS/HTTPS diagnostics.
        ProbeTaskFamily::Web
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = match &self.outcome {
            EchHandshakeOutcome::EchAccepted { .. } => ProbeVerdict::Pass,

            EchHandshakeOutcome::EchRejectedWithRetryConfig => ProbeVerdict::Fail { class: "ech-rejected".to_string() },

            EchHandshakeOutcome::NoEchConfig => ProbeVerdict::Fail { class: "ech-config-unavailable".to_string() },

            EchHandshakeOutcome::HandshakeFailure { detail } => {
                let sanitized = sanitize_detail(detail);
                ProbeVerdict::Fail { class: format!("ech-handshake-failure::{sanitized}") }
            }

            EchHandshakeOutcome::SetupError { detail } => ProbeVerdict::Inconclusive { reason: detail.clone() },
        };

        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

/// Sanitize a raw detail string for embedding in a `ProbeVerdict` class.
///
/// Keeps only ASCII alphanumeric characters and `-`; truncates to
/// [`MAX_DETAIL_LEN`] bytes. The result is stable across runs and safe to
/// embed in telemetry keys.
fn sanitize_detail(raw: &str) -> String {
    let sanitized: String =
        raw.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').take(MAX_DETAIL_LEN).collect();
    if sanitized.is_empty() {
        "unknown".to_string()
    } else {
        sanitized
    }
}

// ---------------------------------------------------------------------------
// Async Runner (stub pending rustls ECH stabilisation)
// ---------------------------------------------------------------------------

/// Async runner that performs DNS HTTPS-RR resolution and a live TLS
/// handshake with ECH enabled, then returns a populated [`EchHandshakeProbe`].
///
/// # Integration status
///
/// The runner is currently a **stub** — see the `PARENT-INTEGRATION` comment
/// at the top of this file. It always returns
/// `SetupError { detail: "ech-runner-not-implemented" }`.
///
/// When rustls ECH is stabilised, replace the body of [`EchHandshakeRunner::attempt`]
/// with:
///
/// 1. Resolve `target_host` HTTPS RR (RFC 9460) using `ctx.resolver_hint`
///    (or the platform resolver when `None`) to obtain ECH config bytes.
/// 2. Return `NoEchConfig` if no HTTPS RR or no `ech=` parameter is present.
/// 3. Open TCP to `target_host:443`.
/// 4. Perform TLS handshake with ECH mode enabled; inspect the negotiated ECH
///    result.
/// 5. Map the TLS outcome to [`EchHandshakeOutcome`] and construct the probe.
pub struct EchHandshakeRunner {
    /// Hostname to probe (e.g. `"cloudflare.com"`).
    pub target_host: String,
    /// Maximum wall-clock time for the combined DNS + TLS attempt.
    pub timeout: std::time::Duration,
}

impl EchHandshakeRunner {
    /// Attempt ECH negotiation against `self.target_host` and return a probe
    /// carrying the captured [`EchHandshakeOutcome`].
    ///
    /// The probe is ready for immediate scheduling via `Probe::run`; no
    /// further I/O is performed after this method returns.
    pub async fn attempt(&self, ctx: &ProbeContext) -> EchHandshakeProbe {
        // PARENT-INTEGRATION: stub — replace with real DNS + TLS logic once
        // rustls ECH is available in the workspace dependency tree.
        let _ = &self.timeout;
        EchHandshakeProbe::new(
            self.target_host.clone(),
            EchHandshakeOutcome::SetupError { detail: "ech-runner-not-implemented".to_string() },
            ctx.relay_hint.clone(),
        )
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ProbeContext {
        ProbeContext::empty()
    }

    fn probe(outcome: EchHandshakeOutcome) -> EchHandshakeProbe {
        EchHandshakeProbe::new("example.com", outcome, None)
    }

    #[test]
    fn ech_accepted_yields_pass() {
        let outcome = probe(EchHandshakeOutcome::EchAccepted { selected_alpn: Some("h2".to_string()) });
        assert_eq!(outcome.run(&ctx()).verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn ech_rejected_yields_fail_with_class() {
        match probe(EchHandshakeOutcome::EchRejectedWithRetryConfig).run(&ctx()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "ech-rejected"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn no_ech_config_yields_fail_with_class() {
        match probe(EchHandshakeOutcome::NoEchConfig).run(&ctx()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "ech-config-unavailable"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn handshake_failure_yields_fail_with_sanitized_detail() {
        let raw_detail = "tcp RST after 500ms (errno=104)!!$$$";
        let p = probe(EchHandshakeOutcome::HandshakeFailure { detail: raw_detail.to_string() });
        match p.run(&ctx()).verdict {
            ProbeVerdict::Fail { class } => {
                assert!(class.starts_with("ech-handshake-failure::"), "class={class:?}");
                let detail_part = class.strip_prefix("ech-handshake-failure::").unwrap();
                // Only alphanumeric and '-' should survive sanitization.
                assert!(
                    detail_part.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'),
                    "non-sanitized chars in detail: {detail_part:?}"
                );
                // Must not exceed MAX_DETAIL_LEN.
                assert!(detail_part.len() <= MAX_DETAIL_LEN, "detail too long: {}", detail_part.len());
            }
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn setup_error_yields_inconclusive() {
        let detail = "resolver-not-available".to_string();
        match probe(EchHandshakeOutcome::SetupError { detail: detail.clone() }).run(&ctx()).verdict {
            ProbeVerdict::Inconclusive { reason } => assert_eq!(reason, detail),
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }

    #[test]
    fn probe_id_and_family_match_constants() {
        let p = probe(EchHandshakeOutcome::EchAccepted { selected_alpn: None });
        assert_eq!(p.id(), ECH_HANDSHAKE_PROBE_ID);
        assert_eq!(p.family(), ProbeTaskFamily::Web);
    }
}
