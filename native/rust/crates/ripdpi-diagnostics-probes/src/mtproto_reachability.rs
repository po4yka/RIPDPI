//! MTProto DC reachability probe.
//!
//! Connects to each Telegram MTProto data-centre (DC1–DC5) over TCP and
//! reports per-DC reachability. Split into two halves:
//!
//! - [`MtprotoReachabilityProbe`] — pure [`Probe`] adapter constructed from
//!   upstream-captured [`DcReachabilityResult`]s. No I/O.
//! - [`MtprotoReachabilityRunner`] — async runner that issues the TCP connects
//!   and builds the probe from their results.
//!
//! ## Default DC IP set
//!
//! The IPs in [`MtprotoReachabilityRunner::default_endpoints`] are the
//! **published Telegram MTProto production IPs** documented in the official
//! Telegram API reference (<https://core.telegram.org/mtproto/mtproto-transports>)
//! and cross-referenced with the TDLib source (`td/telegram/DcId.cpp`).
//! They are stable public values; Telegram has not changed the primary IPv4
//! pool for DC1–DC5 since at least 2018. Port 443 over plain TCP (not TLS)
//! is the standard MTProto transport port.
//!
//! ```text
//! DC1  149.154.175.53  (IPv4 production)
//! DC2  149.154.167.51  (IPv4 production)
//! DC3  149.154.175.100 (IPv4 production)
//! DC4  149.154.167.91  (IPv4 production)
//! DC5  91.108.4.136    (IPv4 production)
//! ```
//!
//! The async runner depends on the crate-local `tokio` and `futures`
//! dependencies declared in this crate's `Cargo.toml`.

use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

// ─── Stable probe identifier ────────────────────────────────────────────────

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const MTPROTO_REACHABILITY_PROBE_ID: &str = "mtproto_reachability";

// ─── Shared types ───────────────────────────────────────────────────────────

/// Which IP-family the endpoint belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DcEndpointFamily {
    /// IPv4 endpoint.
    Ipv4,
    /// IPv6 endpoint.
    Ipv6,
}

/// Outcome of a single DC endpoint probe attempt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DcReachabilityStatus {
    /// TCP connect succeeded within the configured timeout.
    Reachable,
    /// The connect attempt elapsed before the kernel reported success or
    /// failure. Consistent with a firewall dropping SYNs silently.
    ConnectTimeout,
    /// The remote rejected the connection (ECONNREFUSED / RST on first SYN).
    ConnectionRefused,
    /// An established or in-progress connection was reset by the remote.
    /// Associated with TSPU-style RST injection.
    Rst,
    /// The local kernel reported ENETUNREACH — the destination is not
    /// routable from this interface.
    NetworkUnreachable,
    /// Any other error that prevented the probe from reaching a meaningful
    /// verdict (e.g. I/O setup error, address-parse failure).
    SetupError {
        /// Short description of the underlying error.
        detail: String,
    },
}

/// Per-DC result captured by the runner.
#[derive(Debug, Clone)]
pub struct DcReachabilityResult {
    /// Telegram DC number (1 through 5).
    pub dc_id: u8,
    /// Endpoint that was probed, formatted as `"ip:port"`.
    pub endpoint: String,
    /// IP family of this endpoint.
    pub family: DcEndpointFamily,
    /// Outcome of the TCP connect attempt.
    pub status: DcReachabilityStatus,
    /// Round-trip time in milliseconds, present only when `status` is
    /// [`DcReachabilityStatus::Reachable`].
    pub round_trip_ms: Option<u64>,
}

// ─── Pure probe adapter ──────────────────────────────────────────────────────

/// Pure [`Probe`] adapter for MTProto DC reachability.
///
/// Constructed from a list of [`DcReachabilityResult`]s captured upstream by
/// [`MtprotoReachabilityRunner::probe`] (or by any other source in tests).
/// [`Probe::run`] is entirely pure — no I/O, no allocation beyond verdict
/// construction.
///
/// ## Verdict mapping
///
/// Evaluated in this priority order (first match wins):
///
/// 1. **`Pass`** — at least one result has status [`DcReachabilityStatus::Reachable`].
/// 2. **`Fail { class: "mtproto-rst-injection" }`** — at least one result has
///    status [`DcReachabilityStatus::Rst`] and no result is `Reachable`.
///    Marks TSPU-style RST injection as distinct from ordinary unreachability.
/// 3. **`Fail { class: "mtproto-all-dcs-unreachable" }`** — every result is a
///    non-`Reachable`, non-`SetupError` status.
/// 4. **`Inconclusive { reason: "every probe hit a setup error" }`** — every
///    result is `SetupError`.
/// 5. **`Inconclusive { reason: "no DC results captured" }`** — the input list
///    is empty.
#[derive(Debug, Clone)]
pub struct MtprotoReachabilityProbe {
    results: Vec<DcReachabilityResult>,
}

impl MtprotoReachabilityProbe {
    /// Construct from upstream-captured DC reachability results.
    pub fn new(results: Vec<DcReachabilityResult>) -> Self {
        Self { results }
    }
}

impl Probe for MtprotoReachabilityProbe {
    fn id(&self) -> &'static str {
        MTPROTO_REACHABILITY_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Telegram
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(&self.results);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

/// Pure verdict classifier. Extracted so tests can call it directly without
/// constructing a full [`ProbeOutcome`].
fn classify_verdict(results: &[DcReachabilityResult]) -> ProbeVerdict {
    if results.is_empty() {
        return ProbeVerdict::Inconclusive { reason: "no DC results captured".to_string() };
    }

    let any_reachable = results.iter().any(|r| r.status == DcReachabilityStatus::Reachable);
    if any_reachable {
        return ProbeVerdict::Pass;
    }

    let any_rst = results.iter().any(|r| r.status == DcReachabilityStatus::Rst);
    if any_rst {
        return ProbeVerdict::Fail { class: "mtproto-rst-injection".to_string() };
    }

    let all_setup_errors = results.iter().all(|r| matches!(r.status, DcReachabilityStatus::SetupError { .. }));
    if all_setup_errors {
        return ProbeVerdict::Inconclusive { reason: "every probe hit a setup error".to_string() };
    }

    // At least one non-setup-error, non-reachable, non-rst status — treat
    // the entire MTProto path as blocked.
    ProbeVerdict::Fail { class: "mtproto-all-dcs-unreachable".to_string() }
}

// ─── Async runner ─────────────────────────────────────────────────────────

/// An endpoint entry used by the runner.
#[derive(Debug, Clone)]
pub struct DcEndpoint {
    /// Telegram DC number (1 through 5).
    pub dc_id: u8,
    /// IP address or hostname to connect to.
    pub host: String,
    /// TCP port to connect to.
    pub port: u16,
    /// IP family of this endpoint.
    pub family: DcEndpointFamily,
}

/// Async runner that issues TCP connects to each configured DC endpoint and
/// builds a [`MtprotoReachabilityProbe`] from the results.
///
/// The runner is direct-TCP only. `ctx.relay_hint` is noted for future
/// extension but does not currently alter the connect path (see the
/// `// HINT:` comment inside [`MtprotoReachabilityRunner::probe`]).
pub struct MtprotoReachabilityRunner {
    /// Per-endpoint connect timeout.
    pub timeout: Duration,
    /// List of DC endpoints to probe. Use [`Self::default_endpoints`] for the
    /// standard Telegram production IP set.
    pub endpoints: Vec<DcEndpoint>,
}

impl MtprotoReachabilityRunner {
    /// Default DC IP set. Mirrors the upstream Telegram MTProto contract;
    /// caller may pass a custom list to override (e.g. for unit tests or
    /// regional fallbacks).
    ///
    /// IPs sourced from the official Telegram API documentation and TDLib
    /// (`td/telegram/DcId.cpp`). See the module-level doc for the full
    /// provenance note.
    ///
    /// Default IPv4 endpoints:
    ///
    /// - DC1 `149.154.175.53:443`
    /// - DC2 `149.154.167.51:443`
    /// - DC3 `149.154.175.100:443`
    /// - DC4 `149.154.167.91:443`
    /// - DC5 `91.108.4.136:443`
    pub fn default_endpoints() -> Vec<DcEndpoint> {
        vec![
            DcEndpoint { dc_id: 1, host: "149.154.175.53".to_string(), port: 443, family: DcEndpointFamily::Ipv4 },
            DcEndpoint { dc_id: 2, host: "149.154.167.51".to_string(), port: 443, family: DcEndpointFamily::Ipv4 },
            DcEndpoint { dc_id: 3, host: "149.154.175.100".to_string(), port: 443, family: DcEndpointFamily::Ipv4 },
            DcEndpoint { dc_id: 4, host: "149.154.167.91".to_string(), port: 443, family: DcEndpointFamily::Ipv4 },
            DcEndpoint { dc_id: 5, host: "91.108.4.136".to_string(), port: 443, family: DcEndpointFamily::Ipv4 },
        ]
    }

    /// Probe all configured endpoints in parallel and return a
    /// [`MtprotoReachabilityProbe`] ready for [`Probe::run`].
    ///
    /// Each endpoint is probed with an independent `tokio::time::timeout`
    /// wrapping a `tokio::net::TcpStream::connect`. Probes run concurrently
    /// via `futures::future::join_all`.
    ///
    /// `ctx.relay_hint` is currently unused by the runner — the connects are
    /// always direct TCP.
    // HINT: if ctx.relay_hint.is_some(), a future extension could route
    // connects through a SOCKS5 proxy identified by the hint. For now, note
    // the hint and proceed with direct TCP.
    pub async fn probe(&self, ctx: &ProbeContext) -> MtprotoReachabilityProbe {
        // Log the relay hint for future extension.
        let _ = ctx.relay_hint.as_deref();

        let futs = self.endpoints.iter().map(|ep| probe_endpoint(ep, self.timeout));
        let results = futures::future::join_all(futs).await;
        MtprotoReachabilityProbe::new(results)
    }
}

/// Connect to a single endpoint and return a [`DcReachabilityResult`].
async fn probe_endpoint(ep: &DcEndpoint, timeout: Duration) -> DcReachabilityResult {
    let addr = format!("{}:{}", ep.host, ep.port);
    let start = Instant::now();

    let connect_result = tokio::time::timeout(timeout, tokio::net::TcpStream::connect(&addr)).await;

    let status = match connect_result {
        Ok(Ok(_stream)) => {
            // Stream drops immediately; we only need the connect latency.
            DcReachabilityStatus::Reachable
        }
        Err(_elapsed) => DcReachabilityStatus::ConnectTimeout,
        Ok(Err(e)) => map_io_error(e),
    };

    let round_trip_ms =
        if status == DcReachabilityStatus::Reachable { Some(start.elapsed().as_millis() as u64) } else { None };

    DcReachabilityResult { dc_id: ep.dc_id, endpoint: addr, family: ep.family, status, round_trip_ms }
}

/// Map a `std::io::Error` to the appropriate [`DcReachabilityStatus`].
fn map_io_error(e: std::io::Error) -> DcReachabilityStatus {
    use std::io::ErrorKind;
    match e.kind() {
        ErrorKind::ConnectionRefused => DcReachabilityStatus::ConnectionRefused,
        ErrorKind::ConnectionReset | ErrorKind::BrokenPipe => DcReachabilityStatus::Rst,
        // ENETUNREACH maps to Other on some platforms but to a specific kind
        // on others. Check the raw OS error for portability.
        _ if is_net_unreachable(&e) => DcReachabilityStatus::NetworkUnreachable,
        _ => DcReachabilityStatus::SetupError { detail: e.to_string() },
    }
}

/// Returns `true` if the error represents a network-unreachable condition.
fn is_net_unreachable(e: &std::io::Error) -> bool {
    #[cfg(unix)]
    {
        matches!(e.raw_os_error(), Some(libc::ENETUNREACH | libc::EHOSTUNREACH))
    }
    #[cfg(not(unix))]
    {
        // On non-Unix targets, fall back to string matching as a best-effort.
        e.to_string().to_lowercase().contains("network unreachable")
            || e.to_string().to_lowercase().contains("host unreachable")
    }
}

// ─── Tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── helpers ──────────────────────────────────────────────────────────

    fn make_result(dc_id: u8, status: DcReachabilityStatus) -> DcReachabilityResult {
        DcReachabilityResult {
            dc_id,
            endpoint: format!("10.0.0.{dc_id}:443"),
            family: DcEndpointFamily::Ipv4,
            status,
            round_trip_ms: None,
        }
    }

    fn run(results: Vec<DcReachabilityResult>) -> ProbeVerdict {
        MtprotoReachabilityProbe::new(results).run(&ProbeContext::empty()).verdict
    }

    // ── verdict mapping tests ────────────────────────────────────────────

    #[test]
    fn empty_results_yields_inconclusive_no_results() {
        match run(vec![]) {
            ProbeVerdict::Inconclusive { reason } => {
                assert!(reason.contains("no DC"), "reason was: {reason}");
            }
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }

    #[test]
    fn all_setup_errors_yields_inconclusive_setup_error() {
        let results = vec![
            make_result(1, DcReachabilityStatus::SetupError { detail: "parse error".into() }),
            make_result(2, DcReachabilityStatus::SetupError { detail: "parse error".into() }),
        ];
        match run(results) {
            ProbeVerdict::Inconclusive { reason } => {
                assert!(reason.contains("setup error"), "reason was: {reason}");
            }
            other => panic!("expected Inconclusive, got {other:?}"),
        }
    }

    #[test]
    fn any_reachable_yields_pass() {
        let results = vec![
            make_result(1, DcReachabilityStatus::ConnectTimeout),
            make_result(2, DcReachabilityStatus::Reachable),
            make_result(3, DcReachabilityStatus::ConnectionRefused),
        ];
        assert_eq!(run(results), ProbeVerdict::Pass);
    }

    #[test]
    fn all_unreachable_no_rst_yields_fail_all_dcs_unreachable() {
        let results = vec![
            make_result(1, DcReachabilityStatus::ConnectTimeout),
            make_result(2, DcReachabilityStatus::ConnectionRefused),
            make_result(3, DcReachabilityStatus::NetworkUnreachable),
        ];
        match run(results) {
            ProbeVerdict::Fail { class } => {
                assert_eq!(class, "mtproto-all-dcs-unreachable");
            }
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn rst_present_with_no_reachable_yields_fail_rst_injection() {
        let results = vec![
            make_result(1, DcReachabilityStatus::ConnectTimeout),
            make_result(2, DcReachabilityStatus::Rst),
            make_result(3, DcReachabilityStatus::ConnectionRefused),
        ];
        match run(results) {
            ProbeVerdict::Fail { class } => {
                assert_eq!(class, "mtproto-rst-injection");
            }
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn rst_present_with_at_least_one_reachable_still_passes() {
        let results = vec![make_result(1, DcReachabilityStatus::Rst), make_result(2, DcReachabilityStatus::Reachable)];
        assert_eq!(run(results), ProbeVerdict::Pass);
    }

    #[test]
    fn default_endpoints_returns_at_least_five_dcs() {
        let endpoints = MtprotoReachabilityRunner::default_endpoints();
        assert!(endpoints.len() >= 5, "expected >= 5 endpoints, got {}", endpoints.len());
        for dc_id in 1u8..=5 {
            assert!(endpoints.iter().any(|e| e.dc_id == dc_id), "missing DC{dc_id} in default endpoints");
        }
    }

    #[test]
    fn probe_id_and_family_match_constants() {
        let probe = MtprotoReachabilityProbe::new(vec![make_result(1, DcReachabilityStatus::Reachable)]);
        assert_eq!(probe.id(), MTPROTO_REACHABILITY_PROBE_ID);
        assert_eq!(probe.family(), ProbeTaskFamily::Telegram);
    }
}
