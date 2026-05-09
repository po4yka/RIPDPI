use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn note_retry_paced(
        &self,
        target: SocketAddr,
        group_index: usize,
        reason: &'static str,
        backoff_ms: u64,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_retry_paced(target, group_index, reason, backoff_ms);
        }
    }
    pub(in crate::runtime) fn note_route_selected(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        reason: &'static str,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_route_selected(target, group_index, host, reason);
        }
    }
    pub(in crate::runtime) fn note_failure_classified(
        &self,
        target: SocketAddr,
        failure: &RuntimeClassifiedFailure,
        host: Option<&str>,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_failure_classified(target, failure, host);
        }
    }
    pub(in crate::runtime) fn note_route_advanced(
        &self,
        target: SocketAddr,
        previous_group_index: usize,
        next_group_index: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_route_advanced(target, previous_group_index, next_group_index, trigger, host);
        }
    }
    pub(in crate::runtime) fn note_adaptive_override(
        &self,
        target: SocketAddr,
        group_index: usize,
        trigger: u32,
        failure_class: &'static str,
        host: Option<&str>,
        reason: &'static str,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_adaptive_override(target, group_index, trigger, failure_class, host, reason);
        }
    }
    pub(in crate::runtime) fn note_upstream_connected(&self, upstream_addr: SocketAddr, upstream_rtt_ms: Option<u64>) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_connected(upstream_addr, upstream_rtt_ms);
        }
    }
    pub(in crate::runtime) fn note_quic_migration_status(
        &self,
        target: SocketAddr,
        status: &'static str,
        reason: &'static str,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_quic_migration_status(target, status, reason);
        }
    }
    pub(in crate::runtime) fn note_tls_handshake_completed(&self, target: SocketAddr, elapsed_ms: u64) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_tls_handshake_completed(target, elapsed_ms);
        }
    }
}
