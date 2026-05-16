/// Behavioral parity snapshot test for all 9 connectivity runners.
///
/// Runs each runner against a deterministic no-network fixture plan (empty
/// target lists) and diffs the output against a checked-in golden snapshot.
/// Any silent drift in stage IDs, phase strings, total_steps, RunnerOutcome,
/// or event order will cause this test to fail with a clear diff.
///
/// # Regenerating the snapshot
///
/// ```
/// RIPDPI_BLESS_GOLDENS=1 cargo nextest run --manifest-path native/rust/Cargo.toml \
///     -p ripdpi-monitor-engine connectivity_runner_behavioral_parity_snapshot
/// ```
use golden_test_support::{assert_text_golden, canonicalize_json};
use ripdpi_monitor_engine::contracts::connectivity_runner_parity_snapshot;

#[test]
fn connectivity_runner_behavioral_parity_snapshot() {
    let records = connectivity_runner_parity_snapshot();

    // Exactly 9 runners must be present in canonical stage order.
    assert_eq!(records.len(), 9, "expected exactly 9 connectivity runners");

    let json = serde_json::to_string(&records).expect("serialize parity snapshot");
    let canonical = canonicalize_json(&json).expect("canonicalize snapshot json");

    assert_text_golden(
        env!("CARGO_MANIFEST_DIR"),
        "tests/fixtures/connectivity_snapshots/runner_parity.json",
        &canonical,
    );
}
