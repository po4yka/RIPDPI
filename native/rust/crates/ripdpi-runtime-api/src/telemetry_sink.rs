//! Contract port — **stable** public API.
//!
//! [`RuntimeTelemetrySink`] is implemented outside this crate (by the CLI, the
//! Android telemetry adapter, and test harnesses) and consumed by the runtime
//! through a trait object, so the runtime never depends on a concrete
//! telemetry backend. The `ClassifiedFailure` parameter is reused from
//! `ripdpi-failure-classifier` by design — see the crate-level docs.
//!
//! Every method past the required core has a default empty body, so adding a
//! new observation point here is a non-breaking change for existing sinks.

use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime_decision_ports::snapshots::RuntimeDecisionSnapshot;

/// Per-event telemetry port for a running proxy.
///
/// Required methods cover the listener/client/route lifecycle; the
/// default-bodied methods are optional observation points an implementer may
/// override. See the module docs for the stability contract.
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

    /// Called once per runtime construction with the
    /// [`RuntimeDecisionSnapshot`] the `RuntimeDecisionEngine` produced
    /// from the current config / context / network-scope inputs. Sinks may
    /// project it into structured telemetry, dump for debug, or ignore.
    /// Default body is empty so adding this hook is non-breaking — existing
    /// sinks continue to compile and emit no decision-snapshot event.
    ///
    /// No `schemaVersion` is bumped: the snapshot is the runtime's
    /// per-feature view of `ProxyRuntimeContext` (whose wire shape is
    /// owned by `ripdpi-proxy-config`) and carries no schema-versioned
    /// fields of its own.
    fn on_runtime_decision_snapshot(&self, _snapshot: &RuntimeDecisionSnapshot) {}
}
