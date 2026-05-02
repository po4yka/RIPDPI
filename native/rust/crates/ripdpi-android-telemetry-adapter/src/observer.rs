use std::sync::atomic::Ordering;
use std::sync::Arc;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime_api::RuntimeTelemetrySink;

use super::state::ProxyTelemetryState;

pub struct ProxyTelemetryObserver {
    pub state: Arc<ProxyTelemetryState>,
}

impl RuntimeTelemetrySink for ProxyTelemetryObserver {
    fn on_client_slot_exhausted(&self) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.state.slot_exhaustions.fetch_add(1, Ordering::Relaxed);
        self.state.push_event("proxy", "warn", "client rejected: at capacity".to_string());
    }

    fn on_listener_started(&self, bind_addr: std::net::SocketAddr, max_clients: usize, group_count: usize) {
        self.state.mark_running(bind_addr.to_string(), max_clients, group_count);
    }

    fn on_listener_stopped(&self) {
        self.state.mark_stopped();
    }

    fn on_client_accepted(&self) {
        self.state.on_client_accepted();
    }

    fn on_client_finished(&self) {
        self.state.on_client_finished();
    }

    fn on_client_error(&self, error: &std::io::Error) {
        self.state.on_client_io_error(error);
    }

    fn on_route_selected(
        &self,
        target: std::net::SocketAddr,
        group_index: usize,
        host: Option<&str>,
        phase: &'static str,
    ) {
        self.state.on_route_selected(target.to_string(), group_index, host.map(ToOwned::to_owned), phase);
    }

    fn on_failure_classified(&self, target: std::net::SocketAddr, failure: &ClassifiedFailure, host: Option<&str>) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.state.total_errors.fetch_add(1, Ordering::Relaxed);
        if failure.action.as_str() == "retry_with_matching_group" {
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            self.state.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        self.state.on_failure_classified(target.to_string(), failure, host.map(ToOwned::to_owned));
    }

    fn on_upstream_connected(&self, upstream_addr: std::net::SocketAddr, rtt_ms: Option<u64>) {
        self.state.on_upstream_connected(upstream_addr.to_string(), rtt_ms);
    }

    fn on_tls_handshake_completed(&self, _target: std::net::SocketAddr, latency_ms: u64) {
        self.state.on_tls_handshake_completed(latency_ms);
    }

    fn on_route_advanced(
        &self,
        target: std::net::SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        self.state.on_route_advanced(target.to_string(), from_group, to_group, trigger, host.map(ToOwned::to_owned));
    }

    fn on_adaptive_override(
        &self,
        target: std::net::SocketAddr,
        group_index: usize,
        trigger_mask: u32,
        failure_class: &'static str,
        host: Option<&str>,
        reason: &'static str,
    ) {
        self.state.on_adaptive_override(
            target.to_string(),
            group_index,
            trigger_mask,
            failure_class,
            host.map(ToOwned::to_owned),
            reason,
        );
    }

    fn on_retry_paced(&self, target: std::net::SocketAddr, group_index: usize, reason: &'static str, backoff_ms: u64) {
        self.state.on_retry_paced(target.to_string(), group_index, reason, backoff_ms);
    }

    fn on_host_autolearn_state(
        &self,
        enabled: bool,
        learned_host_count: usize,
        penalized_host_count: usize,
        blocked_host_count: usize,
        last_block_signal: Option<&str>,
        last_block_provider: Option<&str>,
    ) {
        self.state.set_autolearn_state(
            enabled,
            learned_host_count,
            penalized_host_count,
            blocked_host_count,
            last_block_signal,
            last_block_provider,
        );
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        self.state.on_autolearn_event(action, host.map(ToOwned::to_owned), group_index);
    }

    fn on_telegram_dc_detected(&self, target: std::net::SocketAddr, dc: u8) {
        self.state.on_telegram_dc_detected(target.to_string(), dc);
    }

    fn on_ws_tunnel_escalation(&self, target: std::net::SocketAddr, dc: u8, success: bool) {
        self.state.on_ws_tunnel_escalation(target.to_string(), dc, success);
    }

    fn on_quic_migration_status(&self, target: std::net::SocketAddr, status: &'static str, reason: &'static str) {
        self.state.on_quic_migration_status(target.to_string(), status, reason);
    }

    fn on_morph_hint_applied(&self, target: std::net::SocketAddr, policy_id: &str, family: &str) {
        self.state.on_morph_hint_applied(target.to_string(), policy_id, family);
    }

    fn on_morph_rollback(&self, target: std::net::SocketAddr, policy_id: &str, reason: &str) {
        self.state.on_morph_rollback(target.to_string(), policy_id, reason);
    }

    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        self.state.on_direct_path_learning_signal(authority, ip_set_digest, event, strategy_family);
    }
}
