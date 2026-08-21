use std::io;

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
    pub(in crate::runtime) fn note_upstream_connect_failed(&self, addr: SocketAddr, rtt_ms: u64, kind: io::ErrorKind) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_connect_failed(addr, rtt_ms, kind);
        }
    }

    pub(in crate::runtime) fn note_candidate_upstream_connect_failed(
        &self,
        addr: SocketAddr,
        logical_target: Option<&str>,
        kind: io::ErrorKind,
    ) {
        if kind == io::ErrorKind::ConnectionRefused {
            self.candidate_refusal_trials.note_connection_refused(addr, logical_target);
        }
    }

    pub(in crate::runtime) fn candidate_refusal_counters(&self) -> super::CandidateRefusalCounters {
        self.candidate_refusal_trials.counters()
    }
    pub(in crate::runtime) fn note_upstream_socket_created(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_socket_created();
        }
    }
    pub(in crate::runtime) fn note_upstream_protect_attempted(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_protect_attempted();
        }
    }
    pub(in crate::runtime) fn note_upstream_protect_succeeded(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_protect_succeeded();
        }
    }
    pub(in crate::runtime) fn note_upstream_protect_rejected(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_protect_rejected();
        }
    }
    pub(in crate::runtime) fn note_upstream_protect_error(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_protect_error();
        }
    }
    pub(in crate::runtime) fn note_upstream_application_bytes_forwarded(&self, bytes: u64, epoch_ms: u64) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_application_bytes_forwarded(bytes, epoch_ms);
        }
    }
    pub(in crate::runtime) fn note_upstream_application_send_result(
        &self,
        result: &Result<OutboundSendOutcome, OutboundSendError>,
    ) {
        let bytes_committed = match result {
            Ok(outcome) => outcome.bytes_committed,
            Err(OutboundSendError::StrategyExecution { bytes_committed, .. }) => *bytes_committed,
            Err(OutboundSendError::Transport { .. }) => 0,
        };
        if bytes_committed > 0 {
            self.note_upstream_application_bytes_forwarded(bytes_committed as u64, now_epoch_ms());
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
