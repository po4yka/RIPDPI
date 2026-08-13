use crate::telemetry::TcpConnectObservation;

pub(crate) struct SocksSessionConfig {
    pub(crate) local_socks_host: String,
    pub(crate) backend_kind: String,
    pub(crate) confirm_good_eligible: bool,
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
