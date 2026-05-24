use std::{thread, time::Duration};

use proptest::prelude::*;

use crate::{ConnectionQualitySnapshot, QualitySample, QualityWindow, TransportKind};

#[test]
fn record_one_sample_then_snapshot() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    w.record(QualitySample { rtt_ms: 42, succeeded: true });
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
        w.record(QualitySample { rtt_ms: 100, succeeded: true });
    }
    for _ in 0..2 {
        w.record(QualitySample { rtt_ms: 0, succeeded: false });
    }
    let s = w.snapshot().expect("snapshot");
    assert!((s.loss_pct - 20.0).abs() < 0.01, "loss_pct={} expected ~20.0", s.loss_pct);
    assert_eq!(s.sample_count, 8);
}

#[test]
fn jitter_zero_for_constant_rtt() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    for _ in 0..10 {
        w.record(QualitySample { rtt_ms: 50, succeeded: true });
    }
    let s = w.snapshot().expect("snapshot");
    assert_eq!(s.jitter_ms, 0, "constant RTT → jitter should be 0");
}

#[test]
fn jitter_positive_for_varying_rtt() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    for i in 0..20 {
        let rtt = if i % 2 == 0 { 10 } else { 50 };
        w.record(QualitySample { rtt_ms: rtt, succeeded: true });
    }
    let s = w.snapshot().expect("snapshot");
    assert!(s.jitter_ms > 0, "alternating 10/50ms should yield positive jitter, got {}", s.jitter_ms);
}

#[test]
fn reset_clears_state() {
    let w = QualityWindow::new(TransportKind::TcpTunnel);
    w.record(QualitySample { rtt_ms: 100, succeeded: true });
    w.record(QualitySample { rtt_ms: 0, succeeded: false });
    assert!(w.snapshot().is_some());
    w.reset();
    assert!(w.snapshot().is_none(), "snapshot after reset should be None");
}

#[test]
fn serialise_snapshot_matches_camel_case() {
    let w = QualityWindow::new(TransportKind::UdpRelay);
    w.record(QualitySample { rtt_ms: 20, succeeded: true });
    w.record(QualitySample { rtt_ms: 30, succeeded: true });
    w.record(QualitySample { rtt_ms: 0, succeeded: false });
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
    w.record(QualitySample { rtt_ms: 10, succeeded: true });
    let first_start = w.snapshot().expect("snapshot after first record").window_start_at_ms;
    thread::sleep(Duration::from_millis(15));
    w.record(QualitySample { rtt_ms: 20, succeeded: true });
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
    w.record(QualitySample { rtt_ms: 0, succeeded: false });
    w.record(QualitySample { rtt_ms: 0, succeeded: false });
    let s = w.snapshot().expect("snapshot should be Some — failures bumped the counter");
    assert_eq!(s.sample_count, 0, "sample_count tracks successes only");
    assert!((s.loss_pct - 100.0).abs() < f32::EPSILON, "loss_pct={} expected 100.0", s.loss_pct);
}

#[test]
fn clone_shares_underlying_state() {
    let w1 = QualityWindow::new(TransportKind::TcpProxy);
    let w2 = w1.clone();
    w1.record(QualitySample { rtt_ms: 5, succeeded: true });
    let s = w2.snapshot().expect("clone observes write");
    assert_eq!(s.sample_count, 1);
}

#[test]
fn rtt_clamped_to_60_000_ms() {
    let w = QualityWindow::new(TransportKind::TcpProxy);
    w.record(QualitySample { rtt_ms: 999_999, succeeded: true });
    let s = w.snapshot().expect("snapshot");
    // 2-sig-digit precision may quantise slightly above 60_000; well under
    // the unclamped 999_999 input.
    assert!(s.rtt_p50_ms < 65_000, "rtt_p50_ms={} should be near 60_000, not 999_999", s.rtt_p50_ms);
}

proptest! {
    /// Property: loss_pct is always in [0.0, 100.0] regardless of input ordering.
    #[test]
    fn loss_pct_within_bounds(
        flags in proptest::collection::vec(any::<bool>(), 1..200),
    ) {
        let w = QualityWindow::new(TransportKind::TcpProxy);
        for ok in &flags {
            w.record(QualitySample { rtt_ms: 100, succeeded: *ok });
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
            w.record(QualitySample { rtt_ms: rtt, succeeded: true });
        }
        let snap = w.snapshot().expect("non-empty");
        // jitter_ms is u64 so >= 0 by construction; this also exercises that
        // the fixed-point arithmetic in update_jitter doesn't underflow.
        prop_assert!(snap.jitter_ms < 100_000);
    }
}
