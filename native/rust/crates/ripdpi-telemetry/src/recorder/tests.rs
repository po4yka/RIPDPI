use std::collections::BTreeMap;
use std::sync::Mutex;

use metrics::Key;

use super::*;
use crate::LatencyHistogram;
use crate::recorder::histogram_handle::HistogramHandle;
use crate::recorder::state::{MAX_METRIC_KEYS, MetricKind};

static GLOBAL_RECORDER_TEST_LOCK: Mutex<()> = Mutex::new(());

fn with_global_recorder_test(test: impl FnOnce()) {
    let _guard = GLOBAL_RECORDER_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    test();
}

#[test]
fn key_name_without_labels() {
    let key = Key::from_name("my_counter");
    assert_eq!(InMemoryRecorder::key_name(&key), "my_counter");
}

#[test]
fn key_name_with_labels() {
    let key = Key::from_parts("my_counter", &[("env", "prod"), ("host", "a")]);
    assert_eq!(InMemoryRecorder::key_name(&key), "my_counter{env=prod}{host=a}");
}

#[test]
fn snapshot_does_not_panic_without_install() {
    with_global_recorder_test(|| {
        let _ = snapshot();
    });
}

#[test]
fn recorder_enforces_key_limit() {
    let rec = InMemoryRecorder::new();
    for i in 0..MAX_METRIC_KEYS {
        let counter = format!("c_{i}");
        assert!(rec.register_counter(counter).is_some());
    }

    assert_eq!(rec.total_keys(), MAX_METRIC_KEYS);
    assert!(rec.register_counter("over_limit".to_string()).is_none());
}

#[test]
fn histogram_handle_converts_seconds_to_ms() {
    let h = LatencyHistogram::new();
    let handle = HistogramHandle(h.clone());
    metrics::HistogramFn::record(&handle, 0.150); // 150ms
    let snap = h.snapshot().expect("should have data");
    assert!(snap.p50 >= 140 && snap.p50 <= 160, "p50={}", snap.p50);
}

#[test]
fn install_is_idempotent() {
    with_global_recorder_test(|| {
        install();
        install(); // second call must not panic
    });
}

#[test]
fn counter_increment_via_recorder() {
    with_global_recorder_test(|| {
        install();
        metrics::counter!("test_counter_inc").increment(1);
        metrics::counter!("test_counter_inc").increment(2);
        if let Some(snap) = snapshot()
            && let Some(&val) = snap.counters.get("test_counter_inc")
        {
            assert!(val >= 3, "expected >= 3, got {val}");
        }
    });
}

#[test]
fn gauge_set_via_recorder() {
    with_global_recorder_test(|| {
        install();
        metrics::gauge!("test_gauge_set").set(42.0);
        if let Some(snap) = snapshot()
            && let Some(&val) = snap.gauges.get("test_gauge_set")
        {
            assert_eq!(val, 42.0_f64.to_bits());
        }
    });
}

#[test]
fn histogram_record_via_recorder() {
    with_global_recorder_test(|| {
        install();
        metrics::histogram!("test_hist_rec").record(0.050);
        if let Some(snap) = snapshot()
            && let Some(perc) = snap.histograms.get("test_hist_rec")
        {
            assert!(perc.p50 >= 45 && perc.p50 <= 55, "p50={}", perc.p50);
        }
    });
}

#[test]
fn reset_histograms_clears_data() {
    with_global_recorder_test(|| {
        install();
        metrics::histogram!("test_reset_h").record(0.100);
        reset_histograms();
        if let Some(snap) = snapshot() {
            assert!(!snap.histograms.contains_key("test_reset_h"), "histogram should be empty after reset");
        }
    });
}

#[test]
fn total_keys_excluding_returns_correct_count() {
    let rec = InMemoryRecorder::new();
    assert!(rec.register_counter("c1".into()).is_some());
    assert!(rec.register_counter("c2".into()).is_some());
    assert!(rec.register_gauge("g1".into()).is_some());
    assert!(rec.register_histogram("h1".into()).is_some());

    assert_eq!(rec.total_keys(), 4);
    // When holding the counters write lock (len=2), exclude counters from read:
    assert_eq!(rec.total_keys_excluding(MetricKind::Counter, 2), 4);
    assert_eq!(rec.total_keys_excluding(MetricKind::Gauge, 1), 4);
    assert_eq!(rec.total_keys_excluding(MetricKind::Histogram, 1), 4);
}

#[test]
fn register_histogram_does_not_deadlock() {
    with_global_recorder_test(|| {
        // Regression test: register_histogram previously called total_keys()
        // while holding a write lock on histograms, causing a same-thread
        // RwLock deadlock. This test would hang (and be killed by the test
        // runner timeout) if the deadlock regresses.
        install();

        // This call deadlocked before the fix -- it must return within ms.
        metrics::histogram!("deadlock_regression_test").record(1.0);

        if let Some(snap) = snapshot() {
            assert!(
                snap.histograms.contains_key("deadlock_regression_test"),
                "histogram should be registered without deadlocking"
            );
        }
    });
}

#[test]
fn register_counter_does_not_deadlock() {
    with_global_recorder_test(|| {
        install();
        metrics::counter!("counter_deadlock_regression").increment(1);
        if let Some(snap) = snapshot() {
            assert!(snap.counters.contains_key("counter_deadlock_regression"));
        }
    });
}

#[test]
fn register_gauge_does_not_deadlock() {
    with_global_recorder_test(|| {
        install();
        metrics::gauge!("gauge_deadlock_regression").set(1.0);
        if let Some(snap) = snapshot() {
            assert!(snap.gauges.contains_key("gauge_deadlock_regression"));
        }
    });
}

#[test]
fn recorder_snapshot_serde_round_trip() {
    let mut counters = BTreeMap::new();
    counters.insert("requests".to_string(), 42);
    let mut gauges = BTreeMap::new();
    gauges.insert("active_conns".to_string(), 5);
    let mut histograms = BTreeMap::new();
    histograms.insert(
        "latency".to_string(),
        crate::LatencyPercentiles { p50: 10, p95: 50, p99: 99, min: 1, max: 200, count: 100 },
    );

    let snap = RecorderSnapshot { counters, gauges, histograms, captured_at: 1700000000000 };
    let json = serde_json::to_string(&snap).expect("serialize");
    let deserialized: RecorderSnapshot = serde_json::from_str(&json).expect("deserialize");

    assert_eq!(deserialized.counters.get("requests"), Some(&42));
    assert_eq!(deserialized.gauges.get("active_conns"), Some(&5));
    assert!(deserialized.histograms.contains_key("latency"));
    assert_eq!(deserialized.captured_at, 1700000000000);
}

#[test]
fn recorder_snapshot_uses_camel_case() {
    let snap = RecorderSnapshot::default();
    let json = serde_json::to_string(&snap).expect("serialize");
    let value: serde_json::Value = serde_json::from_str(&json).expect("parse");
    assert!(value.get("capturedAt").is_some(), "should use camelCase for captured_at");
}

#[test]
fn key_name_with_empty_label_value() {
    let key = Key::from_parts("metric", &[("env", "")]);
    assert_eq!(InMemoryRecorder::key_name(&key), "metric{env=}");
}

#[test]
fn histogram_handle_zero_seconds() {
    let h = LatencyHistogram::new();
    let handle = HistogramHandle(h.clone());
    metrics::HistogramFn::record(&handle, 0.0);
    let snap = h.snapshot().expect("should have data");
    assert_eq!(snap.p50, 0);
    assert_eq!(snap.count, 1);
}

#[test]
fn histogram_handle_large_seconds_value() {
    let h = LatencyHistogram::new();
    let handle = HistogramHandle(h.clone());
    // 120 seconds = 120_000 ms, exceeds 60_000 max -- should be clamped
    metrics::HistogramFn::record(&handle, 120.0);
    let snap = h.snapshot().expect("should have data");
    assert!(snap.max < 65_000, "should be clamped, got max={}", snap.max);
}

#[test]
fn counter_same_key_returns_shared_atomic() {
    with_global_recorder_test(|| {
        install();
        metrics::counter!("shared_counter_test").increment(5);
        metrics::counter!("shared_counter_test").increment(3);
        if let Some(snap) = snapshot()
            && let Some(&val) = snap.counters.get("shared_counter_test")
        {
            assert!(val >= 8, "expected >= 8, got {val}");
        }
    });
}

#[test]
fn snapshot_captured_at_is_nonzero() {
    with_global_recorder_test(|| {
        install();
        if let Some(snap) = snapshot() {
            assert!(snap.captured_at > 0, "captured_at should be a valid timestamp");
        }
    });
}

#[test]
fn default_recorder_snapshot_is_empty() {
    let snap = RecorderSnapshot::default();
    assert!(snap.counters.is_empty());
    assert!(snap.gauges.is_empty());
    assert!(snap.histograms.is_empty());
    assert_eq!(snap.captured_at, 0);
}
