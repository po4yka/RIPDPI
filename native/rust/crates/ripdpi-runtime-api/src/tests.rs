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
    AttemptCorrelationId, DesyncExecutionDisposition, DesyncExecutionEvidence, DesyncExecutionReceipt,
    DesyncExecutionTransport, DesyncOffsetMarkerBase, DesyncStrategyFamily, DesyncTlsPreludeEvidence,
    DesyncTlsPreludeKind, EmbeddedProxyControl, RuntimeTelemetrySink, clear_runtime_telemetry,
    current_runtime_telemetry, install_runtime_telemetry,
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
fn test_desync_evidence(generation: u64, token: &str, connection_ordinal: u64) -> DesyncExecutionEvidence {
    DesyncExecutionEvidence::new(
        generation,
        AttemptCorrelationId::new(token).expect("valid attempt correlation id"),
        connection_ordinal,
        DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Split),
            Some(DesyncStrategyFamily::Split),
            Some(DesyncOffsetMarkerBase::Host),
            Some(1),
            Some(12),
            1,
            3,
            3,
            2,
            1,
            64,
            false,
            None,
            None,
            None,
            None,
        )
        .expect("valid applied receipt"),
    )
    .expect("valid desync evidence")
}

#[cfg(not(feature = "loom"))]
#[test]
fn desync_execution_receipt_rejects_impossible_state() {
    let invalid = |disposition, configured, effective, writes, awaits, bytes, terminal| {
        DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            disposition,
            configured,
            effective,
            None,
            None,
            None,
            0,
            1,
            1,
            writes,
            awaits,
            bytes,
            false,
            None,
            None,
            None,
            terminal,
        )
    };
    assert!(
        invalid(
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Split),
            Some(DesyncStrategyFamily::Split),
            0,
            0,
            0,
            None,
        )
        .is_none()
    );
    assert!(invalid(DesyncExecutionDisposition::ActivationSkipped, None, None, 2, 0, 64, None).is_none());
    assert!(invalid(DesyncExecutionDisposition::PlanFailedPlainFallback, None, None, 1, 1, 64, None).is_none());
    assert!(invalid(DesyncExecutionDisposition::ExecutionFailed, None, None, 0, 0, 0, None).is_none());

    assert!(
        DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Split),
            Some(DesyncStrategyFamily::Split),
            Some(DesyncOffsetMarkerBase::Host),
            Some(1),
            Some(12),
            1,
            1,
            1,
            1,
            0,
            64,
            false,
            None,
            None,
            None,
            None,
        )
        .is_none()
    );

    assert!(
        DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Split),
            Some(DesyncStrategyFamily::Split),
            Some(DesyncOffsetMarkerBase::Host),
            Some(1),
            Some(12),
            1,
            4,
            3,
            2,
            1,
            64,
            false,
            None,
            None,
            None,
            None,
        )
        .is_none()
    );
}

#[cfg(not(feature = "loom"))]
#[test]
fn tls_record_split_receipt_requires_explicit_applied_prelude() {
    let without_prelude = DesyncExecutionReceipt::try_new(
        DesyncExecutionTransport::Tcp,
        DesyncExecutionDisposition::Applied,
        Some(DesyncStrategyFamily::TlsRecordSplit),
        Some(DesyncStrategyFamily::TlsRecordSplit),
        Some(DesyncOffsetMarkerBase::Host),
        Some(1),
        Some(12),
        1,
        3,
        3,
        2,
        1,
        64,
        false,
        None,
        None,
        None,
        None,
    );
    assert!(without_prelude.is_none());

    let with_prelude = DesyncExecutionReceipt::try_new(
        DesyncExecutionTransport::Tcp,
        DesyncExecutionDisposition::Applied,
        Some(DesyncStrategyFamily::TlsRecordSplit),
        Some(DesyncStrategyFamily::TlsRecordSplit),
        Some(DesyncOffsetMarkerBase::Host),
        Some(1),
        Some(12),
        1,
        3,
        3,
        2,
        1,
        64,
        true,
        DesyncTlsPreludeEvidence::try_new(
            1,
            1,
            Some(DesyncTlsPreludeKind::TlsRec),
            Some(DesyncOffsetMarkerBase::ExtLen),
            Some(0),
            Some(42),
        ),
        None,
        None,
        None,
    );
    assert!(with_prelude.is_some());
}

#[cfg(not(feature = "loom"))]
#[test]
fn udp_quic_receipt_is_transport_specific_and_has_no_tcp_prelude_or_awaits() {
    let receipt = DesyncExecutionReceipt::try_new(
        DesyncExecutionTransport::Udp,
        DesyncExecutionDisposition::Applied,
        Some(DesyncStrategyFamily::QuicSniSplit),
        Some(DesyncStrategyFamily::QuicSniSplit),
        None,
        None,
        None,
        1,
        4,
        4,
        2,
        0,
        1200,
        false,
        None,
        None,
        None,
        None,
    )
    .expect("valid udp receipt");
    assert_eq!(receipt.transport(), DesyncExecutionTransport::Udp);

    let forged_tcp_shape = DesyncExecutionReceipt::try_new(
        DesyncExecutionTransport::Tcp,
        DesyncExecutionDisposition::Applied,
        Some(DesyncStrategyFamily::QuicSniSplit),
        Some(DesyncStrategyFamily::QuicSniSplit),
        None,
        None,
        None,
        1,
        4,
        4,
        2,
        0,
        1200,
        false,
        None,
        None,
        None,
        None,
    );
    assert!(forged_tcp_shape.is_none());
}

#[cfg(not(feature = "loom"))]
#[test]
fn desync_connection_ordinals_are_monotonic_per_attempt() {
    let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 7);
    let first = AttemptCorrelationId::new("first").expect("valid first token");
    let second = AttemptCorrelationId::new("second").expect("valid second token");

    assert_eq!(control.next_desync_connection_ordinal(&first), Some(1));
    assert_eq!(control.next_desync_connection_ordinal(&first), Some(2));
    assert_eq!(control.next_desync_connection_ordinal(&second), Some(1));
}

#[cfg(not(feature = "loom"))]
#[test]
fn concurrent_desync_connections_receive_unique_contiguous_ordinals() {
    let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 7));
    let token = AttemptCorrelationId::new("shared-attempt").expect("valid shared token");
    let start = Arc::new(std::sync::Barrier::new(3));
    let workers = (0..2)
        .map(|_| {
            let control = control.clone();
            let token = token.clone();
            let start = start.clone();
            std::thread::spawn(move || {
                start.wait();
                control.next_desync_connection_ordinal(&token).expect("active generation")
            })
        })
        .collect::<Vec<_>>();

    start.wait();
    let mut ordinals =
        workers.into_iter().map(|worker| worker.join().expect("join ordinal worker")).collect::<Vec<_>>();
    ordinals.sort_unstable();

    assert_eq!(ordinals, vec![1, 2]);
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
fn attempt_token_rejects_empty_and_oversized_values() {
    assert!(AttemptCorrelationId::new("").is_none());
    assert!(AttemptCorrelationId::new("a".repeat(u8::MAX as usize + 1)).is_none());
    assert_eq!(AttemptCorrelationId::new("attempt-a").expect("valid id").as_opaque_str(), "attempt-a");
}

#[cfg(not(feature = "loom"))]
#[test]
fn desync_evidence_debug_redacts_attempt_token_value() {
    let evidence = test_desync_evidence(7, "attempt-secret-redacted", 1);
    let debug = format!("{evidence:?}");

    assert!(debug.contains("AttemptCorrelationId(<redacted>)"));
    assert!(!debug.contains("attempt-secret-redacted"));
}

#[cfg(not(feature = "loom"))]
#[test]
fn embedded_proxy_control_rejects_desync_evidence_from_late_generation() {
    let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 7);

    assert!(!control.record_desync_execution_evidence(test_desync_evidence(6, "late-generation", 1)));
    assert!(control.desync_execution_evidence().is_empty());
    assert!(!control.desync_execution_evidence_overflowed());
}

#[cfg(not(feature = "loom"))]
#[test]
fn allocated_desync_connection_without_receipt_marks_snapshot_incomplete() {
    let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 7);
    let token = AttemptCorrelationId::new("receipt-gap").expect("valid attempt token");
    let first = test_desync_evidence(7, "receipt-gap", 1);
    assert_eq!(control.next_desync_connection_ordinal(&token), Some(1));
    assert!(control.record_desync_execution_evidence(first.clone()));
    assert!(!control.desync_execution_evidence_overflowed());

    assert_eq!(control.next_desync_connection_ordinal(&token), Some(2));
    assert_eq!(control.desync_execution_evidence(), vec![first.clone()]);
    assert!(
        control.desync_execution_evidence_overflowed(),
        "an allocated connection without a receipt must not appear complete",
    );

    let second = test_desync_evidence(7, "receipt-gap", 2);
    assert!(control.record_desync_execution_evidence(second.clone()));
    assert_eq!(control.desync_execution_evidence(), vec![first, second]);
    assert!(!control.desync_execution_evidence_overflowed(), "recording the missing receipt closes the gap");
}

#[cfg(not(feature = "loom"))]
#[test]
fn embedded_proxy_control_bounds_desync_evidence_and_marks_overflow() {
    let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 7);

    for ordinal in 1..=32 {
        assert!(control.record_desync_execution_evidence(test_desync_evidence(7, "bounded", ordinal)));
    }
    assert!(!control.record_desync_execution_evidence(test_desync_evidence(7, "bounded", 33)));

    assert_eq!(control.desync_execution_evidence().len(), 32);
    assert!(control.desync_execution_evidence_overflowed());
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
