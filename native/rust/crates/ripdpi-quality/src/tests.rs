use std::{thread, time::Duration};

use proptest::prelude::*;

use crate::{ConnectionQualitySnapshot, QualitySample, QualityWindow, TransportKind};

#[test]
fn record_one_sample_then_snapshot() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    w.record(QualitySample { rtt_ms: 42, succeeded: true, loss_pct: 0.0 });
    let s = w.snapshot().expect("snapshot after record");
    assert_eq!(s.sample_count, 1);
    assert!((s.loss_pct - 0.0).abs() < f32::EPSILON);
    // 2-significant-digit precision: p50 of {42} should be exactly 42 (well
    // within HdrHistogram bucket resolution at this magnitude).
    assert_eq!(s.rtt_p50_ms, 42);
    assert_eq!(s.rtt_p95_ms, 42);
}

#[test]
fn mixed_success_failure_loss_pct() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    for _ in 0..8 {
        w.record(QualitySample { rtt_ms: 100, succeeded: true, loss_pct: 0.0 });
    }
    for _ in 0..2 {
        w.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    }
    let s = w.snapshot().expect("snapshot");
    assert!((s.loss_pct - 20.0).abs() < 0.01, "loss_pct={} expected ~20.0", s.loss_pct);
    assert_eq!(s.sample_count, 8);
}

#[test]
fn jitter_zero_for_constant_rtt() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    for _ in 0..10 {
        w.record(QualitySample { rtt_ms: 50, succeeded: true, loss_pct: 0.0 });
    }
    let s = w.snapshot().expect("snapshot");
    assert_eq!(s.jitter_ms, 0, "constant RTT → jitter should be 0");
}

#[test]
fn jitter_positive_for_varying_rtt() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    for i in 0..20 {
        let rtt = if i % 2 == 0 { 10 } else { 50 };
        w.record(QualitySample { rtt_ms: rtt, succeeded: true, loss_pct: 0.0 });
    }
    let s = w.snapshot().expect("snapshot");
    assert!(s.jitter_ms > 0, "alternating 10/50ms should yield positive jitter, got {}", s.jitter_ms);
}

#[test]
fn reset_clears_state() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    w.record(QualitySample { rtt_ms: 100, succeeded: true, loss_pct: 0.0 });
    w.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    assert!(w.snapshot().is_some());
    w.reset();
    assert!(w.snapshot().is_none(), "snapshot after reset should be None");
}

#[test]
fn serialise_snapshot_matches_camel_case() {
    let w = QualityWindow::new(TransportKind::UdpRelay);
    w.record(QualitySample { rtt_ms: 20, succeeded: true, loss_pct: 0.0 });
    w.record(QualitySample { rtt_ms: 30, succeeded: true, loss_pct: 0.0 });
    w.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    let s = w.snapshot().expect("snapshot");
    let json = serde_json::to_string(&s).expect("serialise");
    for key in ["lossPct", "rttP50Ms", "rttP95Ms", "jitterMs", "sampleCount", "windowStartAtMs", "transportKind"] {
        assert!(json.contains(key), "expected key {key} in serialised snapshot: {json}");
    }
    // TransportKind serialises in snake_case → "udp_relay".
    assert!(json.contains("\"udp_relay\""), "expected snake_case transport_kind in {json}");
}

#[test]
fn window_start_at_ms_set_on_first_sample_only() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    w.record(QualitySample { rtt_ms: 10, succeeded: true, loss_pct: 0.0 });
    let first_start = w.snapshot().expect("snapshot after first record").window_start_at_ms;
    thread::sleep(Duration::from_millis(15));
    w.record(QualitySample { rtt_ms: 20, succeeded: true, loss_pct: 0.0 });
    let second_start = w.snapshot().expect("snapshot after second record").window_start_at_ms;
    assert_eq!(first_start, second_start, "window_start_at_ms must be pinned by the first sample");
}

#[test]
fn empty_window_snapshot_is_none() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    assert!(w.snapshot().is_none());
}

#[test]
fn failure_only_window_still_reports() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    w.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    w.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    let s = w.snapshot().expect("snapshot should be Some — failures bumped the counter");
    assert_eq!(s.sample_count, 0, "sample_count tracks successes only");
    assert!((s.loss_pct - 100.0).abs() < f32::EPSILON, "loss_pct={} expected 100.0", s.loss_pct);
}

#[test]
fn clone_shares_underlying_state() {
    let w1 = QualityWindow::new(TransportKind::TcpProxy);
    let w2 = w1.clone();
    w1.record(QualitySample { rtt_ms: 5, succeeded: true, loss_pct: 0.0 });
    let s = w2.snapshot().expect("clone observes write");
    assert_eq!(s.sample_count, 1);
}

#[test]
fn rtt_clamped_to_60_000_ms() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    w.record(QualitySample { rtt_ms: 999_999, succeeded: true, loss_pct: 0.0 });
    let s = w.snapshot().expect("snapshot");
    // 2-sig-digit precision may quantise slightly above 60_000; well under
    // the unclamped 999_999 input.
    assert!(s.rtt_p50_ms < 65_000, "rtt_p50_ms={} should be near 60_000, not 999_999", s.rtt_p50_ms);
}

// ── New retransmit-loss tests ────────────────────────────────────────────────

#[test]
fn record_loss_only_populates_snapshot_without_succeeded_or_failed() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    w.record_loss(5.0);
    let s = w.snapshot().expect("snapshot present after record_loss");
    assert_eq!(s.sample_count, 0, "record_loss must not touch histogram");
    assert!((s.loss_pct - 5.0).abs() < 0.01, "loss_pct={} expected ~5.0", s.loss_pct);
}

#[test]
fn record_with_loss_pct_on_quality_sample_accumulates_loss() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    w.record(QualitySample { rtt_ms: 20, succeeded: true, loss_pct: 10.0 });
    w.record(QualitySample { rtt_ms: 30, succeeded: true, loss_pct: 20.0 });
    let s = w.snapshot().expect("snapshot");
    // Mean retransmit loss = 15.0; connect-failure loss = 0.0; max = 15.0.
    assert!((s.loss_pct - 15.0).abs() < 0.1, "loss_pct={} expected ~15.0", s.loss_pct);
}

#[test]
fn final_loss_pct_is_max_of_connect_failure_and_retransmit_loss() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    // 1 success, 1 failure → connect-failure loss = 50%.
    w.record(QualitySample { rtt_ms: 20, succeeded: true, loss_pct: 0.0 });
    w.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    // Retransmit loss = 10% (below connect-failure loss).
    w.record_loss(10.0);
    let s = w.snapshot().expect("snapshot");
    // max(50.0, 10.0) = 50.0
    assert!((s.loss_pct - 50.0).abs() < 0.1, "loss_pct={} expected ~50.0", s.loss_pct);

    // Now push retransmit loss above connect-failure loss.
    let w2 = QualityWindow::new(TransportKind::TcpTunnel);
    w2.record(QualitySample { rtt_ms: 20, succeeded: true, loss_pct: 0.0 });
    w2.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });
    w2.record_loss(80.0);
    let s2 = w2.snapshot().expect("snapshot");
    // connect-failure = 50%, retransmit = 80% → max = 80%.
    assert!((s2.loss_pct - 80.0).abs() < 0.1, "loss_pct={} expected ~80.0", s2.loss_pct);
}

#[test]
fn record_loss_clamps_out_of_range_inputs() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    w.record_loss(200.0); // above 100
    w.record_loss(-5.0); // below 0; clamped to 0 by accumulate_loss
    w.record_loss(f32::NAN); // NaN → treated as 0
                             // All three become 100, 0, 0 → mean = 33.33, clamped ≤ 100.
    let s = w.snapshot().expect("snapshot");
    assert!(s.loss_pct >= 0.0 && s.loss_pct <= 100.0, "loss_pct={} out of range", s.loss_pct);
}

#[test]
fn reset_clears_retransmit_loss_state() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    w.record_loss(50.0);
    assert!(w.snapshot().is_some());
    w.reset();
    assert!(w.snapshot().is_none(), "snapshot after reset must be None even with prior record_loss");
}

proptest! {
    /// Property: loss_pct is always in [0.0, 100.0] regardless of input ordering.
    #[test]
    fn loss_pct_within_bounds(
        flags in proptest::collection::vec(any::<bool>(), 1..200),
    ) {
        let w = QualityWindow::new(TransportKind::TcpProxy);
        for ok in &flags {
            w.record(QualitySample { rtt_ms: 100, succeeded: *ok, loss_pct: 0.0 });
        }
        let snap: ConnectionQualitySnapshot = w.snapshot().expect("non-empty");
        prop_assert!(snap.loss_pct >= 0.0 && snap.loss_pct <= 100.0,
            "loss_pct={} out of bounds", snap.loss_pct);
        let total = flags.len() as u64;
        let failures = flags.iter().filter(|x| !**x).count() as u64;
        let expected = (failures as f32 / total as f32) * 100.0;
        prop_assert!((snap.loss_pct - expected).abs() < 0.5,
            "loss_pct={} expected ~{}", snap.loss_pct, expected);
    }

    /// Property: jitter_ms is monotonic-non-negative.
    #[test]
    fn jitter_is_non_negative(
        rtts in proptest::collection::vec(0u64..1000u64, 2..100),
    ) {
        let w = QualityWindow::new(TransportKind::TcpProxy);
        for rtt in rtts {
            w.record(QualitySample { rtt_ms: rtt, succeeded: true, loss_pct: 0.0 });
        }
        let snap = w.snapshot().expect("non-empty");
        // jitter_ms is u64 so >= 0 by construction; this also exercises that
        // the fixed-point arithmetic in update_jitter doesn't underflow.
        prop_assert!(snap.jitter_ms < 100_000);
    }
}
