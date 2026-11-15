//! TCP connect evidence for selected Telegram DC endpoints.
//!
//! The offline probe does not establish MTProto application reachability. The
//! standalone direct-I/O runner is not dispatched by the monitor engine.

use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

use futures::StreamExt;
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
    /// The connect attempt elapsed before the kernel reported success or failure.
    ConnectTimeout,
    /// The local socket API reported a refused connection.
    ConnectionRefused,
    /// The local socket API reported a reset during connect.
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
    /// Actual connected peer, or configured host and port on failure.
    pub endpoint: String,
    /// Actual peer family on success, or configured family on failure.
    pub family: DcEndpointFamily,
    /// Outcome of the TCP connect attempt.
    pub status: DcReachabilityStatus,
    /// Elapsed time to connect in milliseconds, including any name lookup.
    pub connect_elapsed_ms: Option<u64>,
}

// ─── Pure probe adapter ──────────────────────────────────────────────────────

/// Pure [`Probe`] adapter for MTProto DC reachability.
///
/// Constructed from a list of [`DcReachabilityResult`]s captured upstream by
/// [`MtprotoReachabilityRunner::probe`] (or by any other source in tests).
/// [`Probe::run`] is entirely pure — no I/O, no allocation beyond verdict
/// construction.
///
/// A pass means at least one attempted TCP connection succeeded. With no
/// successes, incomplete evidence is inconclusive. A reset in a completed
/// sample describes a socket error and leaves its cause unknown.
#[derive(Debug, Clone)]
pub struct MtprotoReachabilityProbe {
    results: Vec<DcReachabilityResult>,
}

impl MtprotoReachabilityProbe {
    /// Construct from upstream-captured DC reachability results.
    pub fn new(results: Vec<DcReachabilityResult>) -> Self {
        Self { results }
    }

    /// Return the original per-endpoint evidence, including unavailable results.
    pub fn results(&self) -> &[DcReachabilityResult] {
        &self.results
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

    if results.iter().any(|r| matches!(r.status, DcReachabilityStatus::SetupError { .. })) {
        return ProbeVerdict::Inconclusive {
            reason: "TCP connect coverage is incomplete due to a setup error".to_string(),
        };
    }

    let class = if results.iter().any(|r| r.status == DcReachabilityStatus::Rst) {
        "mtproto-dc-tcp-connect-reset-observed"
    } else {
        "mtproto-dc-tcp-connect-failed"
    };
    ProbeVerdict::Fail { class: class.to_string() }
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
/// The runner is direct-TCP only. Active relay requests produce setup errors.
///
/// # ⚠️ Dormant — protect landmine (DIAG-3)
///
/// NOT dispatched by `ripdpi-monitor-engine` / `ripdpi-diagnostics-runner`.
/// Its connect is **Direct + UNPROTECTED** (no `VpnService.protect`). Do **not**
/// wire it into the runner registry without first routing the connect through
/// the protect-aware `ripdpi-diagnostics-runner` seam
/// (`connect_transport_observed`), or it reintroduces a DIAG-1/DIAG-2-class
/// TUN-recursion loop when the VPN is up. See
/// `.claude/rules/vpnservice-protect-invariant.md`.
#[doc(hidden)]
pub struct MtprotoReachabilityRunner {
    /// Per-endpoint connect timeout.
    pub timeout: Duration,
    /// List of DC endpoints to probe. Use [`Self::default_endpoints`] for the
    /// standard Telegram production IP set.
    pub endpoints: Vec<DcEndpoint>,
}

impl MtprotoReachabilityRunner {
    /// One primary IPv4 address per production DC on port 443.
    ///
    /// This subset follows TDLib's pinned bootstrap table at
    /// <https://github.com/tdlib/td/blob/691cb6a775d25d79ef1aea4e9fdd334f12a607be/td/telegram/net/ConnectionCreator.cpp>.
    pub fn default_endpoints() -> Vec<DcEndpoint> {
        ["149.154.175.50", "149.154.167.51", "149.154.175.100", "149.154.167.91", "149.154.171.5"]
            .into_iter()
            .zip(1..=5)
            .map(|(host, dc_id)| DcEndpoint { dc_id, host: host.into(), port: 443, family: DcEndpointFamily::Ipv4 })
            .collect()
    }

    /// Probe the configured endpoints with at most four concurrent connects.
    ///
    /// Each attempt has its own timeout. A request with an active relay hint
    /// is outside this standalone runner's direct-path scope.
    ///
    /// # Cancel safety:
    /// cancel-safe: dropping this future drops its connect futures and releases their sockets.
    pub async fn probe(&self, ctx: &ProbeContext) -> MtprotoReachabilityProbe {
        if ctx.relay_hint.is_some() {
            let results = self
                .endpoints
                .iter()
                .map(|ep| DcReachabilityResult {
                    dc_id: ep.dc_id,
                    endpoint: endpoint_label(ep),
                    family: ep.family,
                    status: DcReachabilityStatus::SetupError {
                        detail: "relay path is unavailable in this TCP probe".into(),
                    },
                    connect_elapsed_ms: None,
                })
                .collect();
            return MtprotoReachabilityProbe::new(results);
        }

        let results = futures::stream::iter(&self.endpoints)
            .map(|ep| probe_endpoint(ep, self.timeout))
            .buffered(4)
            .collect()
            .await;
        MtprotoReachabilityProbe::new(results)
    }
}

fn endpoint_ip(ep: &DcEndpoint) -> Option<IpAddr> {
    let host = ep.host.strip_prefix('[').and_then(|host| host.strip_suffix(']')).unwrap_or(&ep.host);
    host.parse().ok()
}

fn endpoint_label(ep: &DcEndpoint) -> String {
    endpoint_ip(ep).map_or_else(|| format!("{}:{}", ep.host, ep.port), |ip| SocketAddr::new(ip, ep.port).to_string())
}

/// Connect to a single endpoint and return a [`DcReachabilityResult`].
///
/// # Cancel safety:
/// cancel-safe: dropping this future releases the TCP socket if a connect is pending.
async fn probe_endpoint(ep: &DcEndpoint, timeout: Duration) -> DcReachabilityResult {
    let mut result = DcReachabilityResult {
        dc_id: ep.dc_id,
        endpoint: endpoint_label(ep),
        family: ep.family,
        status: DcReachabilityStatus::ConnectTimeout,
        connect_elapsed_ms: None,
    };
    let start = Instant::now();
    let connect = async {
        match endpoint_ip(ep) {
            Some(ip) => tokio::net::TcpStream::connect(SocketAddr::new(ip, ep.port)).await,
            None => tokio::net::TcpStream::connect((ep.host.as_str(), ep.port)).await,
        }
    };
    match tokio::time::timeout(timeout, connect).await {
        Ok(Ok(stream)) => {
            result.status = DcReachabilityStatus::Reachable;
            result.connect_elapsed_ms = Some(start.elapsed().as_millis() as u64);
            if let Ok(peer) = stream.peer_addr() {
                result.endpoint = peer.to_string();
                result.family = if peer.is_ipv4() { DcEndpointFamily::Ipv4 } else { DcEndpointFamily::Ipv6 };
            }
        }
        Ok(Err(error)) => result.status = map_io_error(error),
        Err(_) => {}
    }
    result
}

/// Map a `std::io::Error` to the appropriate [`DcReachabilityStatus`].
fn map_io_error(e: std::io::Error) -> DcReachabilityStatus {
    use std::io::ErrorKind;
    match e.kind() {
        ErrorKind::ConnectionRefused => DcReachabilityStatus::ConnectionRefused,
        ErrorKind::ConnectionReset => DcReachabilityStatus::Rst,
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
            connect_elapsed_ms: None,
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
    fn complete_failed_attempts_retain_connect_failure() {
        let results = vec![
            make_result(1, DcReachabilityStatus::ConnectTimeout),
            make_result(2, DcReachabilityStatus::ConnectionRefused),
            make_result(3, DcReachabilityStatus::NetworkUnreachable),
        ];
        match run(results) {
            ProbeVerdict::Fail { class } => {
                assert_eq!(class, "mtproto-dc-tcp-connect-failed");
            }
            other => panic!("expected Fail, got {other:?}"),
        }
    }

    #[test]
    fn reset_names_the_observation_and_leaves_the_cause_unknown() {
        let results = vec![
            make_result(1, DcReachabilityStatus::ConnectTimeout),
            make_result(2, DcReachabilityStatus::Rst),
            make_result(3, DcReachabilityStatus::ConnectionRefused),
        ];
        match run(results) {
            ProbeVerdict::Fail { class } => {
                assert_eq!(class, "mtproto-dc-tcp-connect-reset-observed");
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
    fn mixed_connect_failure_and_setup_error_is_inconclusive() {
        let results = vec![
            make_result(1, DcReachabilityStatus::Rst),
            make_result(2, DcReachabilityStatus::SetupError { detail: "unresolved".into() }),
        ];
        let probe = MtprotoReachabilityProbe::new(results);
        assert!(matches!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Inconclusive { .. }));
        assert_eq!(probe.results()[0].status, DcReachabilityStatus::Rst);
        assert_eq!(probe.results().len(), 2);
    }

    #[test]
    fn default_endpoints_use_the_pinned_tdlib_primary_ipv4_addresses() {
        let endpoints = MtprotoReachabilityRunner::default_endpoints();
        let actual: Vec<_> = endpoints.iter().map(|ep| (ep.dc_id, endpoint_label(ep))).collect();
        let expected = [
            (1, "149.154.175.50:443"),
            (2, "149.154.167.51:443"),
            (3, "149.154.175.100:443"),
            (4, "149.154.167.91:443"),
            (5, "149.154.171.5:443"),
        ]
        .map(|(id, endpoint)| (id, endpoint.to_string()));
        assert_eq!(actual, expected);
    }

    #[test]
    fn ipv6_tcp_probe_preserves_the_peer_and_sends_no_application_data() {
        use std::io::Read;

        let listener = std::net::TcpListener::bind((std::net::Ipv6Addr::LOCALHOST, 0)).unwrap();
        let peer = listener.local_addr().unwrap();
        let runner = MtprotoReachabilityRunner {
            timeout: Duration::from_secs(1),
            endpoints: vec![DcEndpoint {
                dc_id: 1,
                host: "::1".into(),
                port: peer.port(),
                family: DcEndpointFamily::Ipv6,
            }],
        };
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let probe = runtime.block_on(runner.probe(&ProbeContext::empty()));
        assert_eq!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Pass);
        assert_eq!(probe.results()[0].endpoint, peer.to_string());
        assert_eq!(probe.results()[0].family, DcEndpointFamily::Ipv6);
        let (mut stream, _) = listener.accept().unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
        assert_eq!(stream.read(&mut [0]).unwrap(), 0);
    }

    #[test]
    fn probe_id_and_family_match_constants() {
        let probe = MtprotoReachabilityProbe::new(vec![make_result(1, DcReachabilityStatus::Reachable)]);
        assert_eq!(probe.id(), MTPROTO_REACHABILITY_PROBE_ID);
        assert_eq!(probe.family(), ProbeTaskFamily::Telegram);
    }
}
