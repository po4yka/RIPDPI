//! [`QualityWindowSink`] — producer-side feeder for the process-wide TCP-proxy
//! connection-quality window.
//!
//! This sink turns `ripdpi-proxy-runtime` connect success and failure events
//! into [`QualitySample`]s recorded into a [`QualityWindow`].
//!
//! Sized scope: this slice only wires the producer side. The window
//! snapshot is not yet projected into the proxy `NativeRuntimeSnapshot` —
//! the consumer side is a follow-up slice analogous to the
//! `connectionQuality` field already present in the warp/relay adapters
//! (where the field is `skip_serializing_if = Option::is_none` so an
//! un-installed window remains a no-op on the wire).
//!
//! Why this crate hosts the sink: `ripdpi-android-proxy-adapter` already
//! owns the `ripdpi-proxy-runtime` startup site
//! ([`crate::lifecycle_start`]) and is the only android adapter that
//! depends on `ripdpi-proxy-runtime`. Hosting it in `ripdpi-relay-core` /
//! `ripdpi-warp-core` would push those crates past their
//! `DEPENDENCY_HUB_LIMITS` ceiling and violate their dependency boundary.
//!
//! Composition: [`CompositeProxyTelemetrySink`] wraps the pre-existing
//! [`ProxyTelemetryObserver`] and the new [`QualityWindowSink`], forwarding
//! every `RuntimeTelemetrySink` callback to both. `EmbeddedProxyControl`
//! only accepts one sink, so composition lives at the call-site rather
//! than the API.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use std::sync::LazyLock;

use ripdpi_android_telemetry_adapter::ProxyTelemetryObserver;
use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_quality::{QualitySample, QualityWindow, TransportKind};
use ripdpi_runtime_api::RuntimeTelemetrySink;
use ripdpi_runtime_decision_ports::snapshots::RuntimeDecisionSnapshot;

/// Process-wide TCP-proxy connection-quality window.
///
/// One window per process is intentional: the rolling histograms aggregate
/// across every proxy session for the DegradationStrip's "instant 60s" /
/// "series 15min" views. Per-session windows would not survive the session
/// boundary and would defeat the rolling aggregation.
static QUALITY_WINDOW: LazyLock<Arc<QualityWindow>> =
    LazyLock::new(|| Arc::new(QualityWindow::new(TransportKind::TcpProxy)));

/// Returns a clone of the process-wide TCP-proxy `QualityWindow` handle.
///
/// Exposed for future telemetry-snapshot wiring (consumer side); not used
/// by callers today.
#[allow(dead_code)]
pub(crate) fn quality_window() -> Arc<QualityWindow> {
    QUALITY_WINDOW.clone()
}

pub(crate) fn serialize_proxy_telemetry<T: serde::Serialize>(snapshot: &T) -> serde_json::Result<String> {
    let mut value = serde_json::to_value(snapshot)?;
    if let Some(quality) = quality_window().snapshot() {
        value["connectionQuality"] = serde_json::to_value(quality)?;
    }
    serde_json::to_string(&value)
}

/// `RuntimeTelemetrySink` that converts upstream-connect events from
/// `ripdpi-proxy-runtime` into [`QualitySample`]s in the process-wide
/// [`QualityWindow`].
///
/// Only two hooks are overridden; every other callback inherits the trait's
/// empty default body so this sink is composable with a primary observer
/// without double-recording unrelated events.
///
/// # Cancel safety
///
/// Both overridden methods are synchronous and contain no `.await`.
/// [`QualityWindow::record`] is itself lock-bounded and synchronous, so a
/// cancellation at any caller site loses at most one sample.
pub(crate) struct QualityWindowSink {
    window: Arc<QualityWindow>,
}

impl QualityWindowSink {
    pub(crate) fn new() -> Self {
        Self { window: QUALITY_WINDOW.clone() }
    }

    /// Test-only constructor that takes an explicit window handle so
    /// parallel tests do not pollute the shared process-wide static.
    #[cfg(test)]
    pub(crate) fn with_window(window: Arc<QualityWindow>) -> Self {
        Self { window }
    }
}

impl RuntimeTelemetrySink for QualityWindowSink {
    fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

    fn on_listener_stopped(&self) {}

    fn on_client_accepted(&self) {}

    fn on_client_finished(&self) {}

    fn on_client_error(&self, _error: &io::Error) {}

    fn on_route_selected(&self, _target: SocketAddr, _group_index: usize, _host: Option<&str>, _phase: &'static str) {}

    fn on_failure_classified(&self, _target: SocketAddr, _failure: &ClassifiedFailure, _host: Option<&str>) {}

    fn on_route_advanced(
        &self,
        _target: SocketAddr,
        _from_group: usize,
        _to_group: usize,
        _trigger: u32,
        _host: Option<&str>,
    ) {
    }

    fn on_host_autolearn_state(
        &self,
        _enabled: bool,
        _learned_host_count: usize,
        _penalized_host_count: usize,
        _blocked_host_count: usize,
        _last_block_signal: Option<&str>,
        _last_block_provider: Option<&str>,
    ) {
    }

    fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}

    fn on_upstream_connected(&self, _upstream_addr: SocketAddr, rtt_ms: Option<u64>) {
        // Success path: only record when the producer measured an RTT.
        // `rtt_ms == None` means the connect path completed without a
        // wall-clock measurement (e.g. cached/synthetic success) — recording
        // those would skew the histogram and the RFC 3550 jitter accumulator.
        if let Some(rtt_ms) = rtt_ms {
            self.window.record(QualitySample { rtt_ms, succeeded: true, loss_pct: 0.0 });
        }
    }

    fn on_upstream_connect_failed(&self, _addr: SocketAddr, _rtt_ms: u64, _kind: io::ErrorKind) {
        // Failure path: increments the loss counter; rtt is undefined for
        // failed connects and is intentionally NOT fed into the histogram.
        self.window.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    }
}

/// Composite `RuntimeTelemetrySink` that forwards every callback to two
/// inner sinks: the primary [`ProxyTelemetryObserver`] (which drives the
/// proxy `NativeRuntimeSnapshot`) and the [`QualityWindowSink`] (which
/// feeds [`QualityWindow`]).
///
/// `EmbeddedProxyControl::new_with_context` only accepts a single
/// `Option<Arc<dyn RuntimeTelemetrySink>>`, so composition at the call
/// site is the minimum-diff way to add a second observer without touching
/// `ripdpi-runtime-api` or `ripdpi-proxy-runtime`.
pub(crate) struct CompositeProxyTelemetrySink {
    primary: ProxyTelemetryObserver,
    quality: QualityWindowSink,
}

impl CompositeProxyTelemetrySink {
    pub(crate) fn new(primary: ProxyTelemetryObserver) -> Self {
        Self { primary, quality: QualityWindowSink::new() }
    }

    /// Test-only constructor that injects a private `QualityWindow` so
    /// tests do not race on the shared process-wide static.
    #[cfg(test)]
    pub(crate) fn with_quality_window(primary: ProxyTelemetryObserver, window: Arc<QualityWindow>) -> Self {
        Self { primary, quality: QualityWindowSink::with_window(window) }
    }
}

impl RuntimeTelemetrySink for CompositeProxyTelemetrySink {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize) {
        self.primary.on_listener_started(bind_addr, max_clients, group_count);
        self.quality.on_listener_started(bind_addr, max_clients, group_count);
    }

    fn on_listener_stopped(&self) {
        self.primary.on_listener_stopped();
        self.quality.on_listener_stopped();
    }

    fn on_client_accepted(&self) {
        self.primary.on_client_accepted();
        self.quality.on_client_accepted();
    }

    fn on_client_finished(&self) {
        self.primary.on_client_finished();
        self.quality.on_client_finished();
    }

    fn on_client_error(&self, error: &io::Error) {
        self.primary.on_client_error(error);
        self.quality.on_client_error(error);
    }

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str) {
        self.primary.on_route_selected(target, group_index, host, phase);
        self.quality.on_route_selected(target, group_index, host, phase);
    }

    fn on_failure_classified(&self, target: SocketAddr, failure: &ClassifiedFailure, host: Option<&str>) {
        self.primary.on_failure_classified(target, failure, host);
        self.quality.on_failure_classified(target, failure, host);
    }

    fn on_client_slot_exhausted(&self) {
        self.primary.on_client_slot_exhausted();
        self.quality.on_client_slot_exhausted();
    }

    fn on_upstream_connected(&self, upstream_addr: SocketAddr, rtt_ms: Option<u64>) {
        self.primary.on_upstream_connected(upstream_addr, rtt_ms);
        self.quality.on_upstream_connected(upstream_addr, rtt_ms);
    }

    fn on_upstream_connect_failed(&self, addr: SocketAddr, rtt_ms: u64, kind: io::ErrorKind) {
        self.primary.on_upstream_connect_failed(addr, rtt_ms, kind);
        self.quality.on_upstream_connect_failed(addr, rtt_ms, kind);
    }

    fn on_upstream_socket_created(&self) {
        self.primary.on_upstream_socket_created();
        self.quality.on_upstream_socket_created();
    }

    fn on_upstream_protect_attempted(&self) {
        self.primary.on_upstream_protect_attempted();
        self.quality.on_upstream_protect_attempted();
    }

    fn on_upstream_protect_succeeded(&self) {
        self.primary.on_upstream_protect_succeeded();
        self.quality.on_upstream_protect_succeeded();
    }

    fn on_upstream_protect_rejected(&self) {
        self.primary.on_upstream_protect_rejected();
        self.quality.on_upstream_protect_rejected();
    }

    fn on_upstream_protect_error(&self) {
        self.primary.on_upstream_protect_error();
        self.quality.on_upstream_protect_error();
    }

    fn on_upstream_application_bytes_forwarded(&self, bytes: u64, epoch_ms: u64) {
        self.primary.on_upstream_application_bytes_forwarded(bytes, epoch_ms);
        self.quality.on_upstream_application_bytes_forwarded(bytes, epoch_ms);
    }

    fn on_tls_handshake_completed(&self, target: SocketAddr, latency_ms: u64) {
        self.primary.on_tls_handshake_completed(target, latency_ms);
        self.quality.on_tls_handshake_completed(target, latency_ms);
    }

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        self.primary.on_route_advanced(target, from_group, to_group, trigger, host);
        self.quality.on_route_advanced(target, from_group, to_group, trigger, host);
    }

    fn on_adaptive_override(
        &self,
        target: SocketAddr,
        group_index: usize,
        trigger_mask: u32,
        failure_class: &'static str,
        host: Option<&str>,
        reason: &'static str,
    ) {
        self.primary.on_adaptive_override(target, group_index, trigger_mask, failure_class, host, reason);
        self.quality.on_adaptive_override(target, group_index, trigger_mask, failure_class, host, reason);
    }

    fn on_retry_paced(&self, target: SocketAddr, group_index: usize, reason: &'static str, backoff_ms: u64) {
        self.primary.on_retry_paced(target, group_index, reason, backoff_ms);
        self.quality.on_retry_paced(target, group_index, reason, backoff_ms);
    }

    fn on_morph_hint_applied(&self, target: SocketAddr, policy_id: &str, family: &str) {
        self.primary.on_morph_hint_applied(target, policy_id, family);
        self.quality.on_morph_hint_applied(target, policy_id, family);
    }

    fn on_morph_rollback(&self, target: SocketAddr, policy_id: &str, reason: &str) {
        self.primary.on_morph_rollback(target, policy_id, reason);
        self.quality.on_morph_rollback(target, policy_id, reason);
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
        self.primary.on_host_autolearn_state(
            enabled,
            learned_host_count,
            penalized_host_count,
            blocked_host_count,
            last_block_signal,
            last_block_provider,
        );
        self.quality.on_host_autolearn_state(
            enabled,
            learned_host_count,
            penalized_host_count,
            blocked_host_count,
            last_block_signal,
            last_block_provider,
        );
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        self.primary.on_host_autolearn_event(action, host, group_index);
        self.quality.on_host_autolearn_event(action, host, group_index);
    }

    fn on_telegram_dc_detected(&self, target: SocketAddr, dc: u8) {
        self.primary.on_telegram_dc_detected(target, dc);
        self.quality.on_telegram_dc_detected(target, dc);
    }

    fn on_ws_tunnel_escalation(&self, target: SocketAddr, dc: u8, success: bool) {
        self.primary.on_ws_tunnel_escalation(target, dc, success);
        self.quality.on_ws_tunnel_escalation(target, dc, success);
    }

    fn on_ws_tunnel_fake_sni_active(&self, target: SocketAddr, dc: u8) {
        self.primary.on_ws_tunnel_fake_sni_active(target, dc);
        self.quality.on_ws_tunnel_fake_sni_active(target, dc);
    }

    fn on_quic_migration_status(&self, target: SocketAddr, status: &'static str, reason: &'static str) {
        self.primary.on_quic_migration_status(target, status, reason);
        self.quality.on_quic_migration_status(target, status, reason);
    }

    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        self.primary.on_direct_path_learning_signal(authority, ip_set_digest, event, strategy_family);
        self.quality.on_direct_path_learning_signal(authority, ip_set_digest, event, strategy_family);
    }

    fn on_runtime_decision_snapshot(&self, snapshot: &RuntimeDecisionSnapshot) {
        self.primary.on_runtime_decision_snapshot(snapshot);
        self.quality.on_runtime_decision_snapshot(snapshot);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_android_telemetry_adapter::ProxyTelemetryState;
    use serde_json::Value;
    use std::net::{IpAddr, Ipv4Addr};

    fn test_observer() -> ProxyTelemetryObserver {
        ProxyTelemetryObserver { state: Arc::new(ProxyTelemetryState::new(None)) }
    }

    // Each test owns its own `QualityWindow` so cargo's default parallel
    // test runner does not race them on the process-wide static. The
    // production wiring still uses `QualityWindowSink::new()` which binds
    // to `QUALITY_WINDOW`.

    #[test]
    fn quality_window_sink_records_successful_connect_with_rtt() {
        let window = Arc::new(QualityWindow::new(TransportKind::TcpProxy));
        let sink = QualityWindowSink::with_window(window.clone());

        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443);
        sink.on_upstream_connected(addr, Some(42));

        let snapshot = window.snapshot().expect("snapshot present after record");
        assert_eq!(snapshot.sample_count, 1, "successful connect with rtt counts as one sample");
        assert!(snapshot.rtt_p50_ms > 0, "p50 should reflect the recorded sample");
        assert!((snapshot.loss_pct - 0.0).abs() < f32::EPSILON, "no failures recorded yet");
    }

    #[test]
    fn quality_window_sink_skips_successful_connect_without_rtt() {
        let window = Arc::new(QualityWindow::new(TransportKind::TcpProxy));
        let sink = QualityWindowSink::with_window(window.clone());

        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443);
        sink.on_upstream_connected(addr, None);

        // No sample recorded -> snapshot remains None.
        assert!(window.snapshot().is_none(), "rtt-less success must not produce a sample");
    }

    #[test]
    fn quality_window_sink_records_failed_connect_as_loss() {
        let window = Arc::new(QualityWindow::new(TransportKind::TcpProxy));
        let sink = QualityWindowSink::with_window(window.clone());

        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443);
        sink.on_upstream_connect_failed(addr, 1_500, io::ErrorKind::ConnectionRefused);

        let snapshot = window.snapshot().expect("snapshot present after failure");
        assert_eq!(snapshot.sample_count, 0, "failed connect must not enter the histogram");
        assert!((snapshot.loss_pct - 100.0).abs() < f32::EPSILON, "single failure is 100% loss");
    }

    #[test]
    fn composite_sink_forwards_connect_events_to_quality_window() {
        let window = Arc::new(QualityWindow::new(TransportKind::TcpProxy));
        let composite = CompositeProxyTelemetrySink::with_quality_window(test_observer(), window.clone());

        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443);
        composite.on_upstream_connected(addr, Some(17));
        composite.on_upstream_connect_failed(addr, 500, io::ErrorKind::TimedOut);

        let snapshot = window.snapshot().expect("snapshot present after composite");
        assert_eq!(snapshot.sample_count, 1, "one successful rtt sample landed in the histogram");
        assert!(snapshot.loss_pct > 0.0, "one failure recorded as loss");
    }

    #[test]
    fn quality_window_accessor_returns_global_static() {
        // Sanity guard for the `quality_window()` accessor: production
        // call sites must reach the same handle as `QualityWindowSink::new()`.
        let a = quality_window();
        let b = quality_window();
        assert!(Arc::ptr_eq(&a, &b), "quality_window() must return the shared static handle");
    }

    #[test]
    fn proxy_telemetry_serialization_projects_quality_window() {
        quality_window().reset();
        quality_window().record(QualitySample { rtt_ms: 42, succeeded: true, loss_pct: 0.0 });

        let json = serialize_proxy_telemetry(&serde_json::json!({"source": "proxy"})).expect("serialize telemetry");
        let value: Value = serde_json::from_str(&json).expect("valid json");

        assert_eq!(value["connectionQuality"]["transportKind"], "tcp_proxy");
        assert_eq!(value["connectionQuality"]["sampleCount"], 1);
        assert_eq!(value["connectionQuality"]["rttP50Ms"], 42);
        quality_window().reset();
    }
}
