mod sync;

use std::io;
use std::net::SocketAddr;
#[cfg(not(feature = "loom"))]
use std::sync::OnceLock;

use arc_swap::ArcSwap;

use crate::sync::{Arc, AtomicBool, Ordering};

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::{NetworkSnapshot, ProxyRuntimeContext};

pub(crate) mod adaptive_fake_ttl;
pub(crate) mod adaptive_tuning;
pub mod platform;
pub mod process;
pub(crate) mod retry_stealth;
pub mod runtime;
pub mod runtime_policy;
pub(crate) mod strategy_evolver;
pub mod ws_bootstrap;

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
    /// measuring the round-trip for the ClientHello → ServerHello exchange.
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
}

#[derive(Clone)]
pub struct EmbeddedProxyControl {
    shutdown: Arc<AtomicBool>,
    /// Uses `std::sync::Arc` explicitly: the telemetry sink is not exercised by
    /// loom tests and must stay compatible with downstream crates that always
    /// use `std::sync::Arc` (ripdpi-android, ripdpi-cli test harnesses).
    telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    /// Live OS network state snapshot, pushed from Kotlin on each NetworkCallback event.
    /// Uses `ArcSwap` for lock-free reads on the per-connection hot path.
    /// Uses `std::sync::Arc` explicitly: arc-swap is not loom-compatible,
    /// and no loom tests exercise this field.
    network_snapshot: std::sync::Arc<ArcSwap<Option<NetworkSnapshot>>>,
}

impl std::fmt::Debug for EmbeddedProxyControl {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("EmbeddedProxyControl")
            .field("shutdown_requested", &self.shutdown_requested())
            .field("has_telemetry_sink", &self.telemetry.is_some())
            .field("has_runtime_context", &self.runtime_context.is_some())
            .finish()
    }
}

impl Default for EmbeddedProxyControl {
    fn default() -> Self {
        Self::new(None)
    }
}

impl EmbeddedProxyControl {
    pub fn new(telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>) -> Self {
        Self::new_with_context(telemetry, None)
    }

    pub fn new_with_context(
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        Self {
            shutdown: Arc::new(AtomicBool::new(false)),
            telemetry,
            runtime_context,
            network_snapshot: std::sync::Arc::new(ArcSwap::from_pointee(None)),
        }
    }

    pub fn request_shutdown(&self) {
        self.shutdown.store(true, Ordering::Release);
    }

    pub fn reset_shutdown(&self) {
        self.shutdown.store(false, Ordering::Release);
    }

    pub fn shutdown_requested(&self) -> bool {
        self.shutdown.load(Ordering::Acquire)
    }

    pub fn telemetry_sink(&self) -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
        self.telemetry.clone()
    }

    pub fn runtime_context(&self) -> Option<ProxyRuntimeContext> {
        self.runtime_context.clone()
    }

    /// Push a fresh OS network state snapshot. Safe to call from any thread while the proxy runs.
    pub fn update_network_snapshot(&self, snapshot: NetworkSnapshot) {
        self.network_snapshot.store(std::sync::Arc::new(Some(snapshot)));
    }

    /// Read the most recently pushed OS network state snapshot, if any.
    pub fn current_network_snapshot(&self) -> Option<NetworkSnapshot> {
        (**self.network_snapshot.load()).clone()
    }
}

// OnceLock is not modeled by loom, so the global static and its accessor are
// compiled only on the production path. Loom tests exercise the underlying
// Mutex<Option<Arc<...>>> pattern directly via local instances.
#[cfg(not(feature = "loom"))]
static TELEMETRY_SINK: OnceLock<std::sync::Mutex<Option<std::sync::Arc<dyn RuntimeTelemetrySink>>>> = OnceLock::new();

#[cfg(not(feature = "loom"))]
fn telemetry_slot() -> &'static std::sync::Mutex<Option<std::sync::Arc<dyn RuntimeTelemetrySink>>> {
    TELEMETRY_SINK.get_or_init(|| std::sync::Mutex::new(None))
}

#[cfg(not(feature = "loom"))]
pub fn install_runtime_telemetry(sink: std::sync::Arc<dyn RuntimeTelemetrySink>) {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = Some(sink);
    }
}

#[cfg(not(feature = "loom"))]
pub fn clear_runtime_telemetry() {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = None;
    }
}

#[cfg(not(feature = "loom"))]
pub(crate) fn current_runtime_telemetry() -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
    telemetry_slot().lock().ok().and_then(|slot| slot.clone())
}

// Under loom the OnceLock-based global is compiled out. Callers that use
// current_runtime_telemetry in production code paths get None, which is the
// correct behaviour for tests that exercise concurrency primitives only.
#[cfg(feature = "loom")]
pub(crate) fn current_runtime_telemetry() -> Option<std::sync::Arc<dyn RuntimeTelemetrySink>> {
    None
}

#[cfg(feature = "loom")]
pub fn install_runtime_telemetry(_sink: std::sync::Arc<dyn RuntimeTelemetrySink>) {}

#[cfg(feature = "loom")]
pub fn clear_runtime_telemetry() {}

#[cfg(test)]
mod tests {
    #[cfg(not(feature = "loom"))]
    use super::*;
    #[cfg(not(feature = "loom"))]
    use crate::sync::AtomicUsize;

    #[cfg(not(feature = "loom"))]
    #[allow(dead_code)]
    struct CountingSink {
        accepted: AtomicUsize,
    }

    #[cfg(not(feature = "loom"))]
    impl CountingSink {
        fn new() -> Self {
            Self { accepted: AtomicUsize::new(0) }
        }
    }

    #[cfg(not(feature = "loom"))]
    impl RuntimeTelemetrySink for CountingSink {
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

        fn on_listener_stopped(&self) {}

        fn on_client_accepted(&self) {
            self.accepted.fetch_add(1, Ordering::Relaxed);
        }

        fn on_client_finished(&self) {}

        fn on_client_error(&self, _error: &io::Error) {}

        fn on_route_selected(
            &self,
            _target: SocketAddr,
            _group_index: usize,
            _host: Option<&str>,
            _phase: &'static str,
        ) {
        }

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

        fn on_retry_paced(&self, _target: SocketAddr, _group_index: usize, _reason: &'static str, _backoff_ms: u64) {}

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
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn install_runtime_telemetry_exposes_current_sink_until_cleared() {
        clear_runtime_telemetry();
        let first = Arc::new(CountingSink::new());
        install_runtime_telemetry(first.clone());

        let current = current_runtime_telemetry().expect("installed sink");
        current.on_client_accepted();
        assert_eq!(first.accepted.load(Ordering::Relaxed), 1);

        clear_runtime_telemetry();
        assert!(current_runtime_telemetry().is_none());
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn installing_new_runtime_telemetry_replaces_previous_sink() {
        clear_runtime_telemetry();
        let first = Arc::new(CountingSink::new());
        let second = Arc::new(CountingSink::new());

        install_runtime_telemetry(first.clone());
        install_runtime_telemetry(second.clone());

        let current = current_runtime_telemetry().expect("replacement sink");
        current.on_client_accepted();
        assert_eq!(first.accepted.load(Ordering::Relaxed), 0);
        assert_eq!(second.accepted.load(Ordering::Relaxed), 1);

        clear_runtime_telemetry();
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn embedded_proxy_controls_keep_shutdown_state_isolated() {
        let first = EmbeddedProxyControl::default();
        let second = EmbeddedProxyControl::default();

        first.request_shutdown();

        assert!(first.shutdown_requested());
        assert!(!second.shutdown_requested());

        first.reset_shutdown();
        assert!(!first.shutdown_requested());
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn embedded_proxy_control_preserves_its_own_telemetry_sink() {
        let sink = Arc::new(CountingSink::new());
        let control = EmbeddedProxyControl::new(Some(sink.clone()));

        let current = control.telemetry_sink().expect("telemetry sink");
        current.on_client_accepted();

        assert_eq!(sink.accepted.load(Ordering::Relaxed), 1);
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn network_snapshot_starts_empty_and_accepts_update() {
        use ripdpi_proxy_config::NetworkSnapshot;
        let control = EmbeddedProxyControl::default();
        assert!(control.current_network_snapshot().is_none());

        let snap = NetworkSnapshot { transport: "wifi".to_string(), validated: true, ..NetworkSnapshot::default() };
        control.update_network_snapshot(snap.clone());

        let current = control.current_network_snapshot().expect("snapshot after update");
        assert_eq!(current.transport, "wifi");
        assert!(current.validated);
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn cloned_proxy_controls_share_snapshot_slot() {
        use ripdpi_proxy_config::NetworkSnapshot;
        let original = EmbeddedProxyControl::default();
        let cloned = original.clone();

        let snap = NetworkSnapshot { transport: "cellular".to_string(), metered: true, ..NetworkSnapshot::default() };
        original.update_network_snapshot(snap);

        let from_clone = cloned.current_network_snapshot().expect("snapshot visible via clone");
        assert_eq!(from_clone.transport, "cellular");
        assert!(from_clone.metered);
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn network_snapshot_update_replaces_previous_value() {
        use ripdpi_proxy_config::NetworkSnapshot;
        let control = EmbeddedProxyControl::default();

        control
            .update_network_snapshot(NetworkSnapshot { transport: "wifi".to_string(), ..NetworkSnapshot::default() });
        control.update_network_snapshot(NetworkSnapshot {
            transport: "cellular".to_string(),
            metered: true,
            ..NetworkSnapshot::default()
        });

        let current = control.current_network_snapshot().expect("latest snapshot");
        assert_eq!(current.transport, "cellular");
        assert!(current.metered);
    }

    #[cfg(not(feature = "loom"))]
    #[test]
    fn network_snapshot_concurrent_reads_never_block_writer() {
        use ripdpi_proxy_config::NetworkSnapshot;
        use std::sync::Barrier;

        let control = Arc::new(EmbeddedProxyControl::default());
        let barrier = Arc::new(Barrier::new(3));
        let iterations = 1_000;

        // Writer thread: rapidly updates snapshot
        let writer_control = control.clone();
        let writer_barrier = barrier.clone();
        let writer = std::thread::spawn(move || {
            writer_barrier.wait();
            for i in 0..iterations {
                writer_control.update_network_snapshot(NetworkSnapshot {
                    transport: format!("net-{i}"),
                    ..NetworkSnapshot::default()
                });
            }
        });

        // Reader thread 1: polls snapshot in a tight loop
        let reader1_control = control.clone();
        let reader1_barrier = barrier.clone();
        let reader1 = std::thread::spawn(move || {
            reader1_barrier.wait();
            let mut reads = 0u64;
            for _ in 0..iterations {
                // Should never panic or return corrupted data
                let _ = reader1_control.current_network_snapshot();
                reads += 1;
            }
            reads
        });

        // Reader thread 2: polls snapshot concurrently
        let reader2_control = control.clone();
        let reader2_barrier = barrier.clone();
        let reader2 = std::thread::spawn(move || {
            reader2_barrier.wait();
            let mut reads = 0u64;
            for _ in 0..iterations {
                let _ = reader2_control.current_network_snapshot();
                reads += 1;
            }
            reads
        });

        writer.join().expect("writer panicked");
        let r1 = reader1.join().expect("reader1 panicked");
        let r2 = reader2.join().expect("reader2 panicked");
        assert_eq!(r1, iterations as u64);
        assert_eq!(r2, iterations as u64);

        // Final snapshot should reflect the last write
        let final_snap = control.current_network_snapshot().expect("final snapshot");
        assert_eq!(final_snap.transport, format!("net-{}", iterations - 1));
    }
}
