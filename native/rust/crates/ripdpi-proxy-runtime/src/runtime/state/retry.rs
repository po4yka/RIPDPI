use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn note_retry_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: RuntimeTransportProtocol,
    ) -> io::Result<()> {
        RetryPacingPort::note_retry_success(&self.services, target, group_index, host, payload, transport)
    }
    pub(in crate::runtime) fn note_retry_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: RuntimeTransportProtocol,
        now_ms: u64,
    ) -> io::Result<()> {
        RetryPacingPort::note_retry_failure(&self.services, target, group_index, host, payload, transport, now_ms)
    }
    pub(in crate::runtime) fn build_retry_penalties(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: RuntimeTransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RuntimeRetrySelectionPenalty>> {
        RetryPacingPort::build_retry_penalties(&self.services, target, host, payload, transport, now_ms)
    }
    pub(in crate::runtime) fn apply_retry_pacing(
        &self,
        target: SocketAddr,
        route: &RuntimeConnectionRoute,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
    ) -> io::Result<()> {
        let telemetry = self.telemetry.clone();
        RetryPacingPort::apply_retry_pacing(
            &self.services,
            target,
            route.group_index,
            host,
            payload,
            now_ms,
            &|target, group_index, reason, backoff_ms| {
                if let Some(tel) = &telemetry {
                    tel.on_retry_paced(target, group_index, reason, backoff_ms);
                }
            },
        )
    }
    pub(in crate::runtime) fn max_route_retries(&self) -> usize {
        self.route_retry_settings.max_route_retries
    }
    /// One retry without TCP Fast Open is allowed only when the failed socket used it.
    /// RFC 7413 section 4.1.3.1: no SYN-ACK is the main negative response, so a silent
    /// drop after a TFO attempt retries too.
    pub(in crate::runtime) fn first_write_failure_retries_syn_data_without_tfo(
        socket_used_tfo: bool,
        failure: &RuntimeClassifiedFailure,
        already_retried: bool,
    ) -> bool {
        !already_retried
            && socket_used_tfo
            && failure.action != RuntimeFailureAction::SurfaceOnly
            && tfo_retry_class(failure)
    }
    pub(in crate::runtime) fn connect_failure_retries_without_tfo(
        tcp_fast_open_enabled: bool,
        failure: &RuntimeClassifiedFailure,
    ) -> bool {
        tcp_fast_open_enabled && tfo_retry_class(failure)
    }
}

fn tfo_retry_class(failure: &RuntimeClassifiedFailure) -> bool {
    matches!(
        failure.class,
        RuntimeFailureClass::ConnectFailure | RuntimeFailureClass::TcpReset | RuntimeFailureClass::SilentDrop
    )
}
