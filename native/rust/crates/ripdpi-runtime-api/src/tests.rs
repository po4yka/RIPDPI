#[cfg(not(feature = "loom"))]
use std::io;
#[cfg(not(feature = "loom"))]
use std::net::SocketAddr;

#[cfg(not(feature = "loom"))]
use ripdpi_failure_classifier::ClassifiedFailure;

#[cfg(not(feature = "loom"))]
use crate::sync::{Arc, AtomicUsize, Ordering};
#[cfg(not(feature = "loom"))]
use crate::{
    EmbeddedProxyControl, RuntimeTelemetrySink, clear_runtime_telemetry, current_runtime_telemetry,
    install_runtime_telemetry,
};

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

    control.update_network_snapshot(NetworkSnapshot { transport: "wifi".to_string(), ..NetworkSnapshot::default() });
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

    let reader1_control = control.clone();
    let reader1_barrier = barrier.clone();
    let reader1 = std::thread::spawn(move || {
        reader1_barrier.wait();
        let mut reads = 0u64;
        for _ in 0..iterations {
            let _ = reader1_control.current_network_snapshot();
            reads += 1;
        }
        reads
    });

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

    let final_snap = control.current_network_snapshot().expect("final snapshot");
    assert_eq!(final_snap.transport, format!("net-{}", iterations - 1));
}
