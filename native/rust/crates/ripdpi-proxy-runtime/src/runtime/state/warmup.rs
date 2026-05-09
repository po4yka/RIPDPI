use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn warmup_probe_scheduler_enabled(&self) -> bool {
        self.warmup_probe_settings.scheduler_enabled
    }
    pub(in crate::runtime) fn warmup_probe_response_buffer_size(&self) -> usize {
        self.warmup_probe_settings.response_buffer_size
    }
    pub(in crate::runtime) fn resolve_warmup_probe_host(&self, host: &str) -> io::Result<SocketAddr> {
        runtime_resolve_host_via_encrypted_dns(
            host,
            self.runtime_context.as_ref(),
            self.warmup_probe_settings.protect_path.as_deref(),
            self.warmup_probe_settings.ipv6_enabled,
        )
    }
    pub(in crate::runtime) fn classify_warmup_send_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_warmup_send_error(source)
    }
    pub(in crate::runtime) fn classify_warmup_first_response_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_warmup_first_response_error(source)
    }
    pub(in crate::runtime) fn classify_warmup_closed_before_response() -> RuntimeClassifiedFailure {
        runtime_classify_warmup_closed_before_response()
    }
    pub(in crate::runtime) fn classify_probe_connect_error(source: &io::Error) -> RuntimeProbeResult {
        runtime_classify_probe_connect_error(source)
    }
    pub(in crate::runtime) fn classify_probe_write_error(source: &io::Error) -> RuntimeProbeResult {
        runtime_classify_probe_write_error(source)
    }
    pub(in crate::runtime) fn classify_probe_read_error(source: &io::Error) -> RuntimeProbeResult {
        runtime_classify_probe_read_error(source)
    }
    pub(in crate::runtime) fn classify_probe_tls_response(
        header: [u8; 5],
        handshake_type: Option<u8>,
    ) -> RuntimeProbeResult {
        runtime_classify_probe_tls_response(header, handshake_type)
    }
}
