//! DoH-JSON survey probe: per-resolver reachability across the *JSON* DoH APIs.
//!
//! This is a sibling to [`crate::probes::doh_survey`]. The two probes exist
//! because resolver operators expose the two DoH transports independently and
//! middleboxes filter them independently:
//!
//! * **DoH wire** (RFC 8484, `application/dns-message`) — the format the
//!   runtime resolver in `ripdpi-dns-resolver` actually uses.
//! * **DoH JSON** (vendor `?name=…&type=A` GET, `application/dns-json`) — a
//!   non-IETF convenience API offered by Google, Cloudflare, AdGuard and
//!   Alibaba. Some ISPs block the wire format while leaving the JSON API
//!   reachable, or vice-versa.
//!
//! Probing both lets the classifier represent split outcomes such as
//! "Google wire blocked, Google JSON reachable". This probe reports a verdict
//! **per endpoint**, with its own stable probe id, so its results never alias
//! the wire survey's results for the same operator.
//!
//! ## Diagnostics-only — never a runtime resolver path
//!
//! DoH JSON is vendor-specific and not an IETF interface. This probe is a
//! diagnostics *evidence source* only. The runtime resolver
//! (`ripdpi-dns-resolver`) stays wire-only; there is deliberately no
//! wire→JSON fallback anywhere on the resolution hot path.
//!
//! Split into two halves, mirroring [`crate::probes::doh_survey`]:
//!
//! **Half 1 — pure adapter** ([`DohJsonSurveyProbe`]): turns pre-captured
//! [`DohJsonResolverResult`] values into a [`ProbeOutcome`] with no I/O.
//!
//! **Half 2 — async runner** ([`DohJsonSurveyRunner`]): fires one JSON GET per
//! resolver in parallel, reusing the [`DohHttpClient`] trait so no new HTTP
//! dependency is pulled through this crate.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::probes::doh_survey::DohHttpClient;
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

// ── stable probe id ───────────────────────────────────────────────────────────

/// Stable probe identifier. Embedded in goldens and telemetry — distinct from
/// [`crate::probes::doh_survey::DOH_SURVEY_PROBE_ID`] so wire and JSON verdicts
/// for the same operator stay independent.
pub const DOH_JSON_SURVEY_PROBE_ID: &str = "doh_json_survey";

// ── result types (produced by the runner, consumed by the probe) ──────────────

/// Reachability status of a single DoH-JSON resolver endpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DohJsonResolverStatus {
    /// The resolver answered with at least one usable A or AAAA record.
    Ok {
        /// First IP address returned by the resolver.
        answered_ip: String,
    },
    /// The resolver returned NXDOMAIN (RCODE 3) or a well-formed JSON envelope
    /// with no usable A/AAAA answer (e.g. CNAME-only).
    NxDomain,
    /// A 2xx HTTP response was received but the body was not parseable DoH
    /// JSON (HTML captive-portal page, truncated body, wire-format bytes).
    /// Treated as a probe *failure*, never a panic.
    MalformedJson {
        /// Short description of why the body failed to parse.
        detail: String,
    },
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

impl DohJsonResolverStatus {
    /// Returns `true` when the resolver successfully answered a query.
    fn is_ok(&self) -> bool {
        matches!(self, Self::Ok { .. })
    }

    /// Returns `true` when the resolver is reachable but returned NXDOMAIN.
    fn is_nxdomain(&self) -> bool {
        matches!(self, Self::NxDomain)
    }
}

/// Outcome for a single DoH-JSON resolver endpoint.
#[derive(Debug, Clone)]
pub struct DohJsonResolverResult {
    /// Short human-readable label for the resolver (e.g. `"google"`,
    /// `"cloudflare"`, `"adguard"`, `"alibaba"`).
    pub resolver_label: String,
    /// Reachability status observed for this resolver's JSON endpoint.
    pub status: DohJsonResolverStatus,
    /// Round-trip time in milliseconds, when a response was received.
    pub round_trip_ms: Option<u64>,
}

// ── Half 1: pure probe adapter ────────────────────────────────────────────────

/// Surveys public DoH-JSON resolvers and reports per-resolver reachability.
///
/// Constructed from upstream-captured [`DohJsonResolverResult`] values
/// (typically produced by [`DohJsonSurveyRunner::survey`]). `run` is pure.
///
/// ## Verdict mapping
///
/// | Condition | Verdict |
/// |---|---|
/// | At least one resolver returned `Ok` | `Pass` |
/// | All resolvers returned `NxDomain` | `Fail { class: "doh-json-only-nxdomain" }` |
/// | All resolvers non-`Ok`, not all `NxDomain` | `Fail { class: "doh-json-all-resolvers-blocked" }` |
/// | No resolvers configured | `Inconclusive { reason: "no resolvers configured" }` |
#[derive(Debug, Clone)]
pub struct DohJsonSurveyProbe {
    results: Vec<DohJsonResolverResult>,
}

impl DohJsonSurveyProbe {
    /// Construct from a pre-captured list of resolver results.
    ///
    /// Pass an empty `Vec` to get an `Inconclusive` verdict indicating that no
    /// resolvers were configured.
    pub fn new(results: Vec<DohJsonResolverResult>) -> Self {
        Self { results }
    }
}

impl Probe for DohJsonSurveyProbe {
    fn id(&self) -> &'static str {
        DOH_JSON_SURVEY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::DohJsonSurvey
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = if self.results.is_empty() {
            ProbeVerdict::Inconclusive { reason: "no resolvers configured".to_string() }
        } else if self.results.iter().any(|r| r.status.is_ok()) {
            ProbeVerdict::Pass
        } else if self.results.iter().all(|r| r.status.is_nxdomain()) {
            ProbeVerdict::Fail { class: "doh-json-only-nxdomain".to_string() }
        } else {
            // Non-Ok and not all NxDomain — the JSON path itself looks blocked
            // (MalformedJson, TlsError, NetworkError, HttpError, Timeout).
            ProbeVerdict::Fail { class: "doh-json-all-resolvers-blocked".to_string() }
        };

        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

// ── Half 2: async runner ──────────────────────────────────────────────────────

/// A single DoH-JSON resolver endpoint to query during a survey.
#[derive(Debug, Clone)]
pub struct DohJsonResolverEndpoint {
    /// Short human-readable label (e.g. `"google"`, `"cloudflare"`).
    pub label: String,
    /// JSON DoH endpoint URL (e.g. `"https://dns.google/resolve"`). The runner
    /// appends `?name=…&type=A&ct=application/dns-json`.
    pub url: String,
}

/// The default JSON-only DoH endpoint panel.
///
/// These are the vendor JSON APIs that are *distinct* from the operators'
/// RFC 8484 wire endpoints. The `ct=application/dns-json` query parameter the
/// runner appends forces JSON on operators (Cloudflare) that share one path
/// for both transports; it is ignored harmlessly by dedicated `/resolve`
/// endpoints.
pub fn default_json_resolvers() -> Vec<DohJsonResolverEndpoint> {
    vec![
        DohJsonResolverEndpoint { label: "google".to_string(), url: "https://dns.google/resolve".to_string() },
        DohJsonResolverEndpoint {
            label: "cloudflare".to_string(),
            url: "https://cloudflare-dns.com/dns-query".to_string(),
        },
        DohJsonResolverEndpoint {
            label: "adguard".to_string(),
            url: "https://dns.adguard-dns.com/resolve".to_string(),
        },
        DohJsonResolverEndpoint { label: "alibaba".to_string(), url: "https://dns.alidns.com/resolve".to_string() },
    ]
}

/// Fires one JSON DoH query per configured resolver in parallel and returns a
/// fully populated [`DohJsonSurveyProbe`].
///
/// The runner does no verdict computation; that is the probe's responsibility.
#[derive(Debug, Clone)]
pub struct DohJsonSurveyRunner<C> {
    /// Hostname to query (e.g. `"example.com"`).
    pub query_host: String,
    /// Per-request timeout. Exceeded requests map to
    /// [`DohJsonResolverStatus::Timeout`].
    pub timeout: std::time::Duration,
    /// JSON resolver endpoints to survey. Defaults available via
    /// [`default_json_resolvers`].
    pub resolvers: Vec<DohJsonResolverEndpoint>,
    /// HTTP client implementation. Reuses the same [`DohHttpClient`] trait as
    /// the wire survey so the parent crate wires one client for both.
    pub client: C,
}

impl<C: DohHttpClient> DohJsonSurveyRunner<C> {
    /// Survey all configured JSON resolvers in parallel and return a
    /// [`DohJsonSurveyProbe`] ready for verdict computation.
    pub async fn survey(&self, _ctx: &ProbeContext) -> DohJsonSurveyProbe {
        let futures: Vec<_> = self
            .resolvers
            .iter()
            .map(|ep| {
                let url = format!("{}?name={}&type=A&ct=application/dns-json", ep.url, self.query_host);
                let timeout = self.timeout;
                let client = &self.client;
                let label = ep.label.clone();
                async move {
                    let start = std::time::Instant::now();
                    let raw = client.get(&url, timeout).await;
                    let round_trip_ms = Some(start.elapsed().as_millis() as u64);

                    let status = match raw {
                        Ok((code, body)) if (200..300).contains(&code) => parse_doh_json_answer(&body),
                        Ok((code, _)) if is_timeout_code(code) => DohJsonResolverStatus::Timeout,
                        Ok((code, _)) => DohJsonResolverStatus::HttpError { status: code },
                        Err(e) if is_tls_error(&e) => DohJsonResolverStatus::TlsError { detail: e },
                        Err(e) if e.contains("timeout") || e.contains("timed out") => DohJsonResolverStatus::Timeout,
                        Err(e) => DohJsonResolverStatus::NetworkError { detail: e },
                    };

                    DohJsonResolverResult { resolver_label: label, status, round_trip_ms }
                }
            })
            .collect();

        let results = futures::future::join_all(futures).await;
        DohJsonSurveyProbe::new(results)
    }
}

// ── private helpers ───────────────────────────────────────────────────────────

/// Parse a DoH-JSON response body into a [`DohJsonResolverStatus`].
///
/// Permissive by design: Google and Cloudflare share an identical schema
/// (`{"Status":0,"Answer":[{"data":"<ip>"},…]}`), and this walks every
/// `"data"` field accepting the first value that parses as an IP address. A
/// body that is not a JSON envelope with a `Status` field is reported as
/// [`DohJsonResolverStatus::MalformedJson`] — a probe failure, never a panic.
/// Avoids `serde_json` to keep this crate's dependency surface unchanged.
fn parse_doh_json_answer(body: &[u8]) -> DohJsonResolverStatus {
    let Ok(text) = std::str::from_utf8(body) else {
        return DohJsonResolverStatus::MalformedJson { detail: "non-utf8 response body".to_string() };
    };

    // Must look like a DoH-JSON object envelope. Google/Cloudflare/AdGuard/
    // Alibaba all return a top-level object carrying a "Status" RCODE field.
    // Anything else (HTML error page, wire bytes, truncated body) is malformed.
    if !text.trim_start().starts_with('{') || !(text.contains("\"Status\"") || text.contains("\"status\"")) {
        return DohJsonResolverStatus::MalformedJson { detail: "response is not a DoH-JSON object".to_string() };
    }

    // NXDOMAIN is signalled by RCODE 3 in the Status field.
    if text.contains("\"Status\":3") || text.contains("\"status\":3") {
        return DohJsonResolverStatus::NxDomain;
    }

    // Walk every "data":"<value>" field and return the first that parses as an
    // IP address. This skips CNAME chains (whose data is a hostname) and only
    // accepts a usable A/AAAA answer, matching the runtime resolver contract.
    let mut rest = text;
    while let Some(pos) = rest.find("\"data\":\"") {
        let after = &rest[pos + "\"data\":\"".len()..];
        let Some(end) = after.find('"') else { break };
        let value = &after[..end];
        if value.parse::<std::net::IpAddr>().is_ok() {
            return DohJsonResolverStatus::Ok { answered_ip: value.to_string() };
        }
        rest = &after[end..];
    }

    // Well-formed envelope, Status present and != 3, but no usable A/AAAA data
    // (CNAME-only answer or empty Answer section).
    DohJsonResolverStatus::NxDomain
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

    fn ok_result(label: &str, ip: &str) -> DohJsonResolverResult {
        DohJsonResolverResult {
            resolver_label: label.to_string(),
            status: DohJsonResolverStatus::Ok { answered_ip: ip.to_string() },
            round_trip_ms: Some(12),
        }
    }

    fn nxdomain_result(label: &str) -> DohJsonResolverResult {
        DohJsonResolverResult {
            resolver_label: label.to_string(),
            status: DohJsonResolverStatus::NxDomain,
            round_trip_ms: Some(8),
        }
    }

    fn malformed_result(label: &str) -> DohJsonResolverResult {
        DohJsonResolverResult {
            resolver_label: label.to_string(),
            status: DohJsonResolverStatus::MalformedJson { detail: "html".to_string() },
            round_trip_ms: Some(20),
        }
    }

    #[test]
    fn empty_results_yields_inconclusive() {
        let outcome = DohJsonSurveyProbe::new(vec![]).run(&ProbeContext::empty());
        match outcome.verdict {
            ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("resolvers"), "reason: {reason}"),
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }

    #[test]
    fn any_ok_yields_pass() {
        let results = vec![malformed_result("cloudflare"), ok_result("google", "8.8.8.8")];
        assert_eq!(DohJsonSurveyProbe::new(results).run(&ProbeContext::empty()).verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn all_nxdomain_yields_fail_json_only_nxdomain() {
        let results = vec![nxdomain_result("google"), nxdomain_result("cloudflare")];
        match DohJsonSurveyProbe::new(results).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "doh-json-only-nxdomain"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn malformed_and_blocked_yield_fail_all_resolvers_blocked() {
        let results = vec![
            malformed_result("google"),
            DohJsonResolverResult {
                resolver_label: "cloudflare".to_string(),
                status: DohJsonResolverStatus::HttpError { status: 403 },
                round_trip_ms: Some(40),
            },
        ];
        match DohJsonSurveyProbe::new(results).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "doh-json-all-resolvers-blocked"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn probe_id_and_family_match_constants() {
        let outcome = DohJsonSurveyProbe::new(vec![ok_result("google", "1.2.3.4")]).run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, DOH_JSON_SURVEY_PROBE_ID);
        assert_ne!(outcome.probe_id, crate::probes::doh_survey::DOH_SURVEY_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::DohJsonSurvey);
    }

    #[test]
    fn default_panel_covers_required_operators() {
        let labels: Vec<_> = default_json_resolvers().into_iter().map(|r| r.label).collect();
        for required in ["google", "cloudflare", "adguard", "alibaba"] {
            assert!(labels.iter().any(|l| l == required), "missing {required} in default JSON panel");
        }
    }

    #[test]
    fn parse_google_answer_extracts_ip() {
        let body =
            br#"{"Status":0,"TC":false,"Answer":[{"name":"example.com.","type":1,"TTL":300,"data":"93.184.216.34"}]}"#;
        match parse_doh_json_answer(body) {
            DohJsonResolverStatus::Ok { answered_ip } => assert_eq!(answered_ip, "93.184.216.34"),
            other => panic!("expected Ok, got {other:?}"),
        }
    }

    #[test]
    fn parse_cloudflare_identical_schema_extracts_ip() {
        // Cloudflare's JSON schema is byte-for-byte the same shape as Google's.
        let body = br#"{"Status":0,"Answer":[{"name":"cloudflare.com","type":1,"data":"104.16.132.229"}]}"#;
        match parse_doh_json_answer(body) {
            DohJsonResolverStatus::Ok { answered_ip } => assert_eq!(answered_ip, "104.16.132.229"),
            other => panic!("expected Ok, got {other:?}"),
        }
    }

    #[test]
    fn parse_skips_cname_data_and_finds_ip() {
        let body = br#"{"Status":0,"Answer":[{"type":5,"data":"alias.example.net."},{"type":1,"data":"203.0.113.7"}]}"#;
        match parse_doh_json_answer(body) {
            DohJsonResolverStatus::Ok { answered_ip } => assert_eq!(answered_ip, "203.0.113.7"),
            other => panic!("expected Ok, got {other:?}"),
        }
    }

    #[test]
    fn parse_status_3_is_nxdomain() {
        assert_eq!(parse_doh_json_answer(br#"{"Status":3,"Authority":[]}"#), DohJsonResolverStatus::NxDomain);
    }

    #[test]
    fn parse_cname_only_envelope_is_nxdomain() {
        let body = br#"{"Status":0,"Answer":[{"type":5,"data":"alias.example.net."}]}"#;
        assert_eq!(parse_doh_json_answer(body), DohJsonResolverStatus::NxDomain);
    }

    #[test]
    fn parse_html_body_is_malformed_not_panic() {
        let body = b"<!DOCTYPE html><html><body>blocked by gateway</body></html>";
        match parse_doh_json_answer(body) {
            DohJsonResolverStatus::MalformedJson { .. } => {}
            other => panic!("expected MalformedJson, got {other:?}"),
        }
    }

    #[test]
    fn parse_non_utf8_is_malformed_not_panic() {
        // Invalid UTF-8 byte sequence (wire-format-looking bytes).
        let body = &[0xff, 0xfe, 0x00, 0x01u8];
        match parse_doh_json_answer(body) {
            DohJsonResolverStatus::MalformedJson { .. } => {}
            other => panic!("expected MalformedJson, got {other:?}"),
        }
    }
}
