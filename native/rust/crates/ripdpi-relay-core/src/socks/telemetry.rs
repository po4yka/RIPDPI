use crate::telemetry::TcpConnectObservation;

pub(crate) struct SocksSessionConfig {
    pub(crate) local_socks_host: String,
    pub(crate) backend_kind: String,
    pub(crate) confirm_good_eligible: bool,
}

pub(crate) trait SocksTelemetry {
    fn record_target(&self, target: String);

    fn record_handshake_error(&self, error: String);

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
