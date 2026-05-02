pub(crate) struct SocksSessionConfig {
    pub(crate) local_socks_host: String,
    pub(crate) backend_kind: String,
}

pub(crate) trait SocksTelemetry {
    fn record_target(&self, target: String);

    fn record_handshake_error(&self, error: String);
}
