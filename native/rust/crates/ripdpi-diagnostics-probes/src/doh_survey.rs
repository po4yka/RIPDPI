//! DoH-survey probe: per-resolver reachability across public DoH endpoints.
//!
//! Split into two halves:
//!
//! **Half 1 — pure adapter** ([`DohSurveyProbe`]): accepts pre-captured
//! [`DohResolverResult`] values and produces a [`ProbeOutcome`] with no I/O.
//! This half is always usable in unit tests and in the offline classification
//! pipeline.
//!
//! **Half 2 — async runner** ([`DohSurveyRunner`]): fires one RFC 8484 JSON
//! query per resolver in parallel and builds a [`DohSurveyProbe`] from the
//! responses. The runner is generic over an HTTP client trait so the parent
//! crate can wire whatever HTTP stack is available without pulling a new
//! dependency through this crate.
//!
//! # HTTP client integration
//!
//! ```text
//! The async runner requires an HTTP client that implements `DohHttpClient`.
//! Wire the concrete client in the runner crate by wrapping the available HTTP
//! stack in a newtype and passing it to `DohSurveyRunner`.
//! ```

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

// ── stable probe id ───────────────────────────────────────────────────────────

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const DOH_SURVEY_PROBE_ID: &str = "doh_survey";

// ── result types (produced by the runner, consumed by the probe) ──────────────

/// Reachability status of a single DoH resolver.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DohResolverStatus {
    /// The resolver answered with at least one usable A or AAAA record.
    Ok {
        /// First IP address returned by the resolver.
        answered_ip: String,
    },
    /// The resolver returned NXDOMAIN (DNS answer present, host not found).
    NxDomain,
    /// The resolver did not reply within the configured timeout.
    Timeout,
    /// The resolver replied with a non-2xx HTTP status.
    HttpError {
        /// HTTP status code returned by the resolver endpoint.
        status: u16,
    },
    /// TLS handshake failed.
    TlsError {
        /// Short description of the TLS failure.
        detail: String,
    },
    /// TCP/IP or OS-level network error before any HTTP response was received.
    NetworkError {
        /// Short description of the network failure.
        detail: String,
    },
}

impl DohResolverStatus {
    /// Returns `true` when the resolver successfully answered a query.
    fn is_ok(&self) -> bool {
        matches!(self, Self::Ok { .. })
    }

    /// Returns `true` when the resolver is reachable but returned NXDOMAIN.
    fn is_nxdomain(&self) -> bool {
        matches!(self, Self::NxDomain)
    }
}

/// Outcome for a single DoH resolver endpoint.
#[derive(Debug, Clone)]
pub struct DohResolverResult {
    /// Short human-readable label for the resolver (e.g. `"cloudflare"`,
    /// `"google"`, `"user-active"`).
    pub resolver_label: String,
    /// Reachability status observed for this resolver.
    pub status: DohResolverStatus,
    /// Round-trip time in milliseconds, when a response was received.
    pub round_trip_ms: Option<u64>,
}

// ── Half 1: pure probe adapter ────────────────────────────────────────────────

/// Surveys public DoH resolvers and reports per-resolver reachability.
///
/// Constructed from upstream-captured [`DohResolverResult`] values (typically
/// produced by [`DohSurveyRunner::survey`]). `run` is pure — no I/O.
///
/// ## Verdict mapping
///
/// | Condition | Verdict |
/// |---|---|
/// | At least one resolver returned `Ok` | `Pass` |
/// | All resolvers returned `NxDomain` | `Fail { class: "doh-only-nxdomain" }` |
/// | All resolvers returned non-`Ok`, non-`NxDomain` | `Fail { class: "doh-all-resolvers-blocked" }` |
/// | Mixed `Ok`/non-`Ok` (at least one `Ok`) | `Pass` |
/// | No resolvers configured | `Inconclusive { reason: "no resolvers configured" }` |
#[derive(Debug, Clone)]
pub struct DohSurveyProbe {
    results: Vec<DohResolverResult>,
}

impl DohSurveyProbe {
    /// Construct from a pre-captured list of resolver results.
    ///
    /// Pass an empty `Vec` to get an `Inconclusive` verdict indicating that no
    /// resolvers were configured.
    pub fn new(results: Vec<DohResolverResult>) -> Self {
        Self { results }
    }
}

impl Probe for DohSurveyProbe {
    fn id(&self) -> &'static str {
        DOH_SURVEY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Dns
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = if self.results.is_empty() {
            ProbeVerdict::Inconclusive { reason: "no resolvers configured".to_string() }
        } else if self.results.iter().any(|r| r.status.is_ok()) {
            ProbeVerdict::Pass
        } else if self.results.iter().all(|r| r.status.is_nxdomain()) {
            ProbeVerdict::Fail { class: "doh-only-nxdomain".to_string() }
        } else {
            // Every result is non-Ok and not all are NxDomain — the DoH path
            // itself looks blocked (TlsError, NetworkError, HttpError, Timeout).
            ProbeVerdict::Fail { class: "doh-all-resolvers-blocked".to_string() }
        };

        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

// ── Half 2: async runner ──────────────────────────────────────────────────────

/// A single DoH resolver endpoint to query during a survey.
#[derive(Debug, Clone)]
pub struct DohResolverEndpoint {
    /// Short human-readable label (e.g. `"cloudflare"`, `"google"`).
    pub label: String,
    /// RFC 8484 JSON endpoint URL
    /// (e.g. `"https://cloudflare-dns.com/dns-query"`).
    pub url: String,
}

/// Minimal HTTP response contract needed by the survey runner.
///
/// Implement this for a `reqwest::Client` newtype, a `hyper` wrapper, or a
/// test double. The runner crate is responsible for the concrete implementation
/// — this crate stays HTTP-stack agnostic.
///
/// ```text
/// Implement this trait for the HTTP client used by the runner crate.
/// For reqwest, wrap `reqwest::Client` in a newtype and call
/// `client.get(url).timeout(timeout).send().await`.
/// For hyper, build a request and drive it through hyper's client.
/// ```
pub trait DohHttpClient: Send + Sync {
    /// Execute a GET request to `url` and return the raw body bytes and HTTP
    /// status code, or an error string if the request failed at the
    /// network/TLS layer.
    ///
    /// The future must complete within `timeout`; implementations should
    /// enforce the deadline internally and return `Err("timeout")` (or an
    /// equivalent string) when exceeded.
    fn get<'a>(&'a self, url: &'a str, timeout: std::time::Duration) -> DohHttpFuture<'a>;
}

/// Future returned by [`DohHttpClient::get`]. Aliased to keep the trait
/// signature readable.
pub type DohHttpFuture<'a> =
    std::pin::Pin<Box<dyn std::future::Future<Output = Result<(u16, Vec<u8>), String>> + Send + 'a>>;

/// Fires one RFC 8484 JSON DoH query per configured resolver in parallel and
/// returns a fully populated [`DohSurveyProbe`].
///
/// The runner itself does no verdict computation; that is the probe's
/// responsibility.
#[derive(Debug, Clone)]
pub struct DohSurveyRunner<C> {
    /// Hostname to query (e.g. `"example.com"`).
    pub query_host: String,
    /// Per-request timeout. Exceeded requests map to
    /// [`DohResolverStatus::Timeout`].
    pub timeout: std::time::Duration,
    /// Resolver endpoints to survey. When [`ProbeContext::resolver_hint`] is
    /// present, a synthetic `"user-active"` endpoint is prepended at runtime.
    pub resolvers: Vec<DohResolverEndpoint>,
    /// HTTP client implementation. Generic so the parent wires whatever stack
    /// is available without touching this crate's `Cargo.toml`.
    pub client: C,
}

impl<C: DohHttpClient> DohSurveyRunner<C> {
    /// Survey all configured resolvers (plus the user-active resolver from
    /// `ctx.resolver_hint` when present) in parallel and return a
    /// [`DohSurveyProbe`] ready for verdict computation.
    pub async fn survey(&self, ctx: &ProbeContext) -> DohSurveyProbe {
        // Build the effective resolver list: user-active first (audit fix A-1),
        // then the statically configured resolvers.
        let mut effective: Vec<DohResolverEndpoint> = Vec::new();
        if let Some(hint) = &ctx.resolver_hint {
            effective.push(DohResolverEndpoint { label: "user-active".to_string(), url: hint.clone() });
        }
        effective.extend(self.resolvers.iter().cloned());

        let futures: Vec<_> = effective
            .into_iter()
            .map(|ep| {
                let url = format!("{}?name={}&type=A", ep.url, self.query_host);
                let timeout = self.timeout;
                let client = &self.client;
                let label = ep.label.clone();
                async move {
                    let start = std::time::Instant::now();
                    let raw = client.get(&url, timeout).await;
                    let round_trip_ms = Some(start.elapsed().as_millis() as u64);

                    let status = match raw {
                        Ok((code, body)) if (200..300).contains(&code) => {
                            // Parse the JSON response body for an A/AAAA answer.
                            // RFC 8484 JSON format: {"Answer":[{"data":"<ip>"},...]}
                            // Use a minimal string search to avoid pulling in serde_json.
                            parse_doh_answer(&body)
                        }
                        Ok((code, _)) if is_timeout_code(code) => DohResolverStatus::Timeout,
                        Ok((code, _)) => DohResolverStatus::HttpError { status: code },
                        Err(e) if is_tls_error(&e) => DohResolverStatus::TlsError { detail: e },
                        Err(e) if e.contains("timeout") || e.contains("timed out") => DohResolverStatus::Timeout,
                        Err(e) => DohResolverStatus::NetworkError { detail: e },
                    };

                    DohResolverResult { resolver_label: label, status, round_trip_ms }
                }
            })
            .collect();

        let results = futures::future::join_all(futures).await;
        DohSurveyProbe::new(results)
    }
}

// ── private helpers ───────────────────────────────────────────────────────────

/// Parse a DoH JSON response body and return the appropriate status.
///
/// Uses a minimal string search to extract the first `"data"` field value
/// without requiring `serde_json` in this crate.
fn parse_doh_answer(body: &[u8]) -> DohResolverStatus {
    let Ok(text) = std::str::from_utf8(body) else {
        return DohResolverStatus::NetworkError { detail: "non-utf8 response body".to_string() };
    };

    // NXDOMAIN is signalled by status=3 in the JSON envelope.
    if text.contains("\"Status\":3") || text.contains("\"status\":3") {
        return DohResolverStatus::NxDomain;
    }

    // Look for the first "data":"<value>" field in an Answer section.
    if let Some(pos) = text.find("\"data\":\"") {
        let after = &text[pos + 8..];
        if let Some(end) = after.find('"')
            && !after[..end].is_empty()
        {
            return DohResolverStatus::Ok { answered_ip: after[..end].to_string() };
        }
    }

    // No Answer section and no NXDOMAIN — treat as effectively NXDOMAIN.
    DohResolverStatus::NxDomain
}

/// Returns `true` for HTTP status codes that conventionally indicate a
/// gateway/proxy timeout rather than an explicit server error.
fn is_timeout_code(code: u16) -> bool {
    matches!(code, 408 | 504 | 524)
}

/// Returns `true` when the error string indicates a TLS-layer failure.
fn is_tls_error(e: &str) -> bool {
    e.contains("tls") || e.contains("TLS") || e.contains("ssl") || e.contains("SSL") || e.contains("certificate")
}

// ── tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn ok_result(label: &str, ip: &str) -> DohResolverResult {
        DohResolverResult {
            resolver_label: label.to_string(),
            status: DohResolverStatus::Ok { answered_ip: ip.to_string() },
            round_trip_ms: Some(12),
        }
    }

    fn net_err_result(label: &str) -> DohResolverResult {
        DohResolverResult {
            resolver_label: label.to_string(),
            status: DohResolverStatus::NetworkError { detail: "connection refused".to_string() },
            round_trip_ms: None,
        }
    }

    fn nxdomain_result(label: &str) -> DohResolverResult {
        DohResolverResult {
            resolver_label: label.to_string(),
            status: DohResolverStatus::NxDomain,
            round_trip_ms: Some(8),
        }
    }

    #[test]
    fn empty_results_yields_inconclusive() {
        let probe = DohSurveyProbe::new(vec![]);
        let outcome = probe.run(&ProbeContext::empty());
        match outcome.verdict {
            ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("resolvers"), "reason: {reason}"),
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }

    #[test]
    fn any_ok_yields_pass() {
        let results = vec![
            net_err_result("cloudflare"),
            ok_result("google", "8.8.8.8"),
            DohResolverResult {
                resolver_label: "quad9".to_string(),
                status: DohResolverStatus::Timeout,
                round_trip_ms: None,
            },
        ];
        let outcome = DohSurveyProbe::new(results).run(&ProbeContext::empty());
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn all_network_error_yields_fail_doh_all_resolvers_blocked() {
        let results = vec![
            net_err_result("cloudflare"),
            DohResolverResult {
                resolver_label: "google".to_string(),
                status: DohResolverStatus::TlsError { detail: "cert verify failed".to_string() },
                round_trip_ms: None,
            },
            DohResolverResult {
                resolver_label: "quad9".to_string(),
                status: DohResolverStatus::HttpError { status: 403 },
                round_trip_ms: Some(50),
            },
        ];
        match DohSurveyProbe::new(results).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "doh-all-resolvers-blocked"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn all_nxdomain_yields_fail_doh_only_nxdomain() {
        let results = vec![nxdomain_result("cloudflare"), nxdomain_result("google"), nxdomain_result("quad9")];
        match DohSurveyProbe::new(results).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "doh-only-nxdomain"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn mixed_nxdomain_and_ok_yields_pass() {
        let results = vec![nxdomain_result("cloudflare"), ok_result("google", "8.8.8.8")];
        assert_eq!(DohSurveyProbe::new(results).run(&ProbeContext::empty()).verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn probe_id_and_family_match_constants() {
        let probe = DohSurveyProbe::new(vec![ok_result("test", "1.2.3.4")]);
        let outcome = probe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, DOH_SURVEY_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Dns);
    }

    #[test]
    fn timeout_result_contributes_to_blocked_class() {
        let results = vec![DohResolverResult {
            resolver_label: "cloudflare".to_string(),
            status: DohResolverStatus::Timeout,
            round_trip_ms: None,
        }];
        match DohSurveyProbe::new(results).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "doh-all-resolvers-blocked"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn parse_doh_answer_ok_extracts_ip() {
        let body =
            br#"{"Status":0,"TC":false,"Answer":[{"name":"example.com.","type":1,"TTL":300,"data":"93.184.216.34"}]}"#;
        match parse_doh_answer(body) {
            DohResolverStatus::Ok { answered_ip } => assert_eq!(answered_ip, "93.184.216.34"),
            other => panic!("expected Ok, got {other:?}"),
        }
    }

    #[test]
    fn parse_doh_answer_nxdomain_status_3() {
        let body = br#"{"Status":3,"Authority":[]}"#;
        assert_eq!(parse_doh_answer(body), DohResolverStatus::NxDomain);
    }
}
