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
//! `EchHandshakeRunner` dispatches through the [`EchHandshakeDriver`] trait.
//! The live Hickory + rustls backend lives in `hickory_rustls_ech_driver.rs`;
//! [`NoopEchHandshakeDriver`] remains available for tests and callers that
//! intentionally want the historical `"ech-runner-not-implemented"` verdict.

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
    if sanitized.is_empty() { "unknown".to_string() } else { sanitized }
}

// ---------------------------------------------------------------------------
// Async Runner with pluggable driver
// ---------------------------------------------------------------------------

/// Pluggable backend for the ECH handshake runner.
///
/// Real implementations wire DNS HTTPS-RR resolution (e.g.
/// `hickory-resolver`) and a TLS handshake stack with ECH support
/// (e.g. rustls 0.23+ `ClientConfig::ech_mode`). Tests use the
/// in-process fake produced by [`NoopEchHandshakeDriver`] or a custom
/// fake.
///
/// The trait uses native `async fn in trait` (stable Rust 1.75+) so
/// the runner can stay generic over the driver without boxing.
pub trait EchHandshakeDriver: Send + Sync {
    /// Resolve the HTTPS RR for `host` and return the parsed `ech=`
    /// parameter bytes, or `None` if the record exists but carries no
    /// ECH config. Returns `Err` only when the lookup itself failed
    /// (resolver unavailable, timeout, malformed response); a clean
    /// "no HTTPS RR" answer is `Ok(None)`.
    fn lookup_ech_config(
        &self,
        host: &str,
    ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>, String>> + Send;

    /// Perform the TLS handshake against `host:port` with ECH enabled
    /// using `ech_config`. Return the [`EchHandshakeOutcome`] directly
    /// so the runner can stay agnostic of the underlying TLS stack's
    /// error model.
    fn handshake_with_ech(
        &self,
        host: &str,
        port: u16,
        ech_config: &[u8],
        timeout: std::time::Duration,
    ) -> impl std::future::Future<Output = EchHandshakeOutcome> + Send;
}

/// In-process driver that preserves the pre-refactor stub behaviour.
///
/// Always returns
/// `SetupError { detail: "ech-runner-not-implemented" }` so callers
/// that have not yet picked a real driver see the documented
/// "not implemented" signal instead of silent success.
#[derive(Debug, Clone, Copy, Default)]
pub struct NoopEchHandshakeDriver;

impl EchHandshakeDriver for NoopEchHandshakeDriver {
    async fn lookup_ech_config(&self, _host: &str) -> Result<Option<Vec<u8>>, String> {
        Err("ech-runner-not-implemented".to_string())
    }

    async fn handshake_with_ech(
        &self,
        _host: &str,
        _port: u16,
        _ech_config: &[u8],
        _timeout: std::time::Duration,
    ) -> EchHandshakeOutcome {
        EchHandshakeOutcome::SetupError { detail: "ech-runner-not-implemented".to_string() }
    }
}

/// Async runner that performs DNS HTTPS-RR resolution and a live TLS
/// handshake with ECH enabled, then returns a populated [`EchHandshakeProbe`].
///
/// Generic over the backend [`EchHandshakeDriver`]; the runner itself
/// is pure dispatcher logic and is exercised by the FakeDriver tests
/// in `tests/ech_handshake_runner_tdd.rs`.
///
/// # ⚠️ Dormant — protect landmine (DIAG-3)
///
/// NOT dispatched by `ripdpi-monitor-engine` / `ripdpi-diagnostics-runner`.
/// With a live driver its connect is **Direct + UNPROTECTED** (no
/// `VpnService.protect`). Do **not** wire it into the runner registry without
/// first routing the driver's connect through the protect-aware
/// `ripdpi-diagnostics-runner` seam, or it reintroduces a DIAG-1/DIAG-2-class
/// TUN-recursion loop when the VPN is up. See
/// `.claude/rules/vpnservice-protect-invariant.md`.
#[doc(hidden)]
pub struct EchHandshakeRunner<D: EchHandshakeDriver> {
    /// Backend driver responsible for the DNS + TLS I/O.
    pub driver: D,
    /// Hostname to probe (e.g. `"cloudflare.com"`).
    pub target_host: String,
    /// Maximum wall-clock time for the TLS handshake. The DNS lookup
    /// uses the driver's own deadline.
    pub timeout: std::time::Duration,
}

impl<D: EchHandshakeDriver> EchHandshakeRunner<D> {
    /// Construct a runner with the supplied driver and target.
    pub fn new(driver: D, target_host: impl Into<String>, timeout: std::time::Duration) -> Self {
        Self { driver, target_host: target_host.into(), timeout }
    }

    /// Attempt ECH negotiation and return a probe carrying the
    /// captured [`EchHandshakeOutcome`].
    pub async fn attempt(&self, ctx: &ProbeContext) -> EchHandshakeProbe {
        let outcome = match self.driver.lookup_ech_config(&self.target_host).await {
            Err(detail) => EchHandshakeOutcome::SetupError { detail },
            Ok(None) => EchHandshakeOutcome::NoEchConfig,
            Ok(Some(config)) => self.driver.handshake_with_ech(&self.target_host, 443, &config, self.timeout).await,
        };
        EchHandshakeProbe::new(self.target_host.clone(), outcome, ctx.relay_hint.clone())
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
