//! TDD test file for ThroughputProbe.
//!
//! Written before the implementation exists. Each `#[test]` exercises
//! one row of the verdict matrix specified in the goal. When this file
//! first lands, none of the symbols resolve and `cargo test` fails at
//! compile time -- that is the expected first-state "red" of the TDD
//! cycle.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_probes::throughput::{
    ThroughputBaseline, ThroughputMeasurement, ThroughputProbe, THROUGHPUT_PROBE_ID,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

fn baseline_1mbps() -> ThroughputBaseline {
    // 1_000_000 bytes/sec expected, with documented degradation thresholds.
    ThroughputBaseline { expected_bps: 1_000_000, degraded_threshold_pct: 80, severe_threshold_pct: 25 }
}

#[test]
fn measured_at_or_above_degraded_threshold_yields_pass() {
    // 850_000 bytes in 1s = 85% of baseline -> above the 80% degraded floor.
    let probe =
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: 850_000, duration_ms: 1000 }, baseline_1mbps());
    let outcome = probe.run(&ProbeContext::empty());
    assert_eq!(outcome.probe_id, THROUGHPUT_PROBE_ID);
    assert_eq!(outcome.family, ProbeTaskFamily::Throughput);
    assert_eq!(outcome.verdict, ProbeVerdict::Pass);
}

#[test]
fn measured_at_full_baseline_yields_pass() {
    let probe =
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: 1_000_000, duration_ms: 1000 }, baseline_1mbps());
    assert_eq!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Pass);
}

#[test]
fn measured_between_severe_and_degraded_yields_fail_degraded() {
    // 500_000 bytes in 1s = 50% of baseline -> between 25% and 80%.
    let probe =
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: 500_000, duration_ms: 1000 }, baseline_1mbps());
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "throughput-degraded"),
        other => panic!("expected Fail{{throughput-degraded}}, got {other:?}"),
    }
}

#[test]
fn measured_below_severe_threshold_yields_fail_severely_degraded() {
    // 100_000 bytes in 1s = 10% of baseline -> below the 25% severe floor.
    let probe =
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: 100_000, duration_ms: 1000 }, baseline_1mbps());
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "throughput-severely-degraded"),
        other => panic!("expected Fail{{throughput-severely-degraded}}, got {other:?}"),
    }
}

#[test]
fn zero_bytes_with_nonzero_duration_yields_fail_stalled() {
    let probe = ThroughputProbe::new(ThroughputMeasurement { total_bytes: 0, duration_ms: 5000 }, baseline_1mbps());
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "throughput-stalled"),
        other => panic!("expected Fail{{throughput-stalled}}, got {other:?}"),
    }
}

#[test]
fn zero_duration_yields_inconclusive() {
    let probe = ThroughputProbe::new(ThroughputMeasurement { total_bytes: 1_000, duration_ms: 0 }, baseline_1mbps());
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("duration"), "reason: {reason}"),
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}

#[test]
fn boundary_just_below_degraded_threshold_yields_fail_degraded() {
    // 799_999 bytes in 1s = 79.99% of baseline -> just below 80%.
    let probe =
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: 799_999, duration_ms: 1000 }, baseline_1mbps());
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "throughput-degraded"),
        other => panic!("expected Fail{{throughput-degraded}}, got {other:?}"),
    }
}

#[test]
fn boundary_just_below_severe_threshold_yields_fail_severely_degraded() {
    // 249_999 bytes in 1s = 24.99% of baseline -> just below 25%.
    let probe =
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: 249_999, duration_ms: 1000 }, baseline_1mbps());
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "throughput-severely-degraded"),
        other => panic!("expected Fail{{throughput-severely-degraded}}, got {other:?}"),
    }
}
