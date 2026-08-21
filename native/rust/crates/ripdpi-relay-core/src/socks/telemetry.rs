use std::time::Duration;

use crate::telemetry::TcpConnectObservation;

/// Upper bound for one upstream dial through a relay backend.
///
/// Generous enough for QUIC handshakes through censored networks; without it a
/// backend that never completes its handshake would pin an admission permit
/// (max 256) and an fd until runtime shutdown.
pub(crate) const RELAY_CONNECT_DEADLINE: Duration = Duration::from_secs(30);

/// Tear down a relayed TCP session after this much wire silence in both
/// directions. Detection latency is bounded by twice the window.
pub(crate) const RELAY_TCP_IDLE_TIMEOUT: Duration = Duration::from_secs(300);

/// Tear down a UDP association after this much datagram silence.
pub(crate) const RELAY_UDP_IDLE_TIMEOUT: Duration = Duration::from_secs(300);

/// Per-session relay timeout policy, injected so tests can shrink the windows.
#[derive(Clone, Copy)]
pub(crate) struct RelaySessionTimeouts {
    pub connect_deadline: Duration,
    pub tcp_idle: Duration,
    pub udp_idle: Duration,
}

impl RelaySessionTimeouts {
    pub(crate) fn production() -> Self {
        Self {
            connect_deadline: RELAY_CONNECT_DEADLINE,
            tcp_idle: RELAY_TCP_IDLE_TIMEOUT,
            udp_idle: RELAY_UDP_IDLE_TIMEOUT,
        }
    }
}

pub(crate) struct SocksSessionConfig {
    pub(crate) local_socks_host: String,
    pub(crate) backend_kind: String,
    pub(crate) confirm_good_eligible: bool,
    pub(crate) timeouts: RelaySessionTimeouts,
}

pub(crate) trait SocksTelemetry {
    fn next_attempt_id(&self) -> u64;

    fn record_target(&self, target: String);

    fn record_handshake_error(&self, error: String);

    fn record_xudp_association_opened(&self) {}

    fn record_xudp_association_closed(&self, _reason: &'static str) {}

    fn record_xudp_uplink(&self, _bytes: usize, _queue_high_water_mark: usize) {}

    fn record_xudp_downlink(&self, _bytes: usize) {}

    fn record_xudp_open_failure(&self) {}

    fn record_xudp_write_failure(&self, _timed_out: bool) {}

    fn record_xudp_read_failure(&self, _timed_out: bool) {}

    /// Fire a quality observation for an upstream TCP connect attempt.
    ///
    /// Default impl is a no-op so existing implementors remain unchanged
    /// until they opt in. `RelayRuntime` overrides this to route into
    /// `RuntimeState::emit_connect_observation`.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    fn emit_connect_observation(&self, _obs: TcpConnectObservation) {}

    fn record_confirm_good_passive_stall(
        &self,
        _target: &str,
        _application_bytes_sent: u64,
        _application_response_bytes: u64,
        _profile_catalog_validated: bool,
    ) {
    }
}
