use crate::telemetry::TcpConnectObservation;

pub(crate) struct SocksSessionConfig {
    pub(crate) local_socks_host: String,
    pub(crate) backend_kind: String,
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
}
