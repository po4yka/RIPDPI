use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::ClassifiedFailure;

pub trait RuntimeTelemetrySink: Send + Sync {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize);

    fn on_listener_stopped(&self);

    fn on_client_accepted(&self);

    fn on_client_finished(&self);

    fn on_client_error(&self, error: &io::Error);

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str);

    fn on_failure_classified(&self, target: SocketAddr, failure: &ClassifiedFailure, host: Option<&str>);

    fn on_client_slot_exhausted(&self) {}

    fn on_upstream_connected(&self, _upstream_addr: SocketAddr, _rtt_ms: Option<u64>) {}

    /// Called when the first upstream response is received for a TLS connection,
    /// measuring the round-trip for the ClientHello -> ServerHello exchange.
    /// Only called when the first outbound request starts with a TLS record byte (0x16).
    fn on_tls_handshake_completed(&self, _target: SocketAddr, _latency_ms: u64) {}

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    );

    fn on_adaptive_override(
        &self,
        _target: SocketAddr,
        _group_index: usize,
        _trigger_mask: u32,
        _failure_class: &'static str,
        _host: Option<&str>,
        _reason: &'static str,
    ) {
    }

    fn on_retry_paced(&self, _target: SocketAddr, _group_index: usize, _reason: &'static str, _backoff_ms: u64) {}

    fn on_morph_hint_applied(&self, _target: SocketAddr, _policy_id: &str, _family: &str) {}

    fn on_morph_rollback(&self, _target: SocketAddr, _policy_id: &str, _reason: &str) {}

    fn on_host_autolearn_state(
        &self,
        enabled: bool,
        learned_host_count: usize,
        penalized_host_count: usize,
        blocked_host_count: usize,
        last_block_signal: Option<&str>,
        last_block_provider: Option<&str>,
    );

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>);

    /// Called when a connection target is identified as a known Telegram DC.
    /// Fired for all Telegram IP connections, regardless of WS tunnel config.
    fn on_telegram_dc_detected(&self, _target: SocketAddr, _dc: u8) {}

    /// Called when the runtime escalates from desync to WS tunnel (fallback mode).
    fn on_ws_tunnel_escalation(&self, _target: SocketAddr, _dc: u8, _success: bool) {}

    fn on_quic_migration_status(&self, _target: SocketAddr, _status: &'static str, _reason: &'static str) {}

    fn on_direct_path_learning_signal(
        &self,
        _authority: &str,
        _ip_set_digest: &str,
        _event: &'static str,
        _strategy_family: Option<&str>,
    ) {
    }
}
