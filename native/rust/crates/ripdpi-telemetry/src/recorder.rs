use std::collections::BTreeMap;
use std::sync::atomic::Ordering;
use std::sync::{Arc, OnceLock, RwLock};

use metrics::atomics::AtomicU64;
use metrics::{Counter, Gauge, Histogram, Key, KeyName, Metadata, Recorder, SharedString, Unit};
use serde::{Deserialize, Serialize};

use crate::{LatencyHistogram, LatencyPercentiles};

/// Maximum number of distinct metric keys. Prevents unbounded label
/// cardinality from consuming mobile memory.
const MAX_METRIC_KEYS: usize = 256;

/// In-memory metrics recorder for Android/CLI environments.
///
/// Stores counters, gauges and histograms in bounded collections.
/// Thread-safe: counters/gauges use Arc<AtomicU64> for stable addresses,
/// histograms reuse the existing `LatencyHistogram` wrapper.
///
/// Install once at process startup via [`install()`]. Poll via
/// [`snapshot()`] from JNI or CLI shutdown.
enum MetricKind {
    Counter,
    Gauge,
    Histogram,
}

pub struct InMemoryRecorder {
    counters: RwLock<Vec<(String, Arc<AtomicU64>)>>,
    gauges: RwLock<Vec<(String, Arc<AtomicU64>)>>,
    histograms: RwLock<Vec<(String, LatencyHistogram)>>,
}

impl InMemoryRecorder {
    fn new() -> Self {
        Self { counters: RwLock::new(Vec::new()), gauges: RwLock::new(Vec::new()), histograms: RwLock::new(Vec::new()) }
    }

    /// Returns total number of registered metric keys across all collections.
    ///
    /// **Caller must NOT hold any write lock on `counters`, `gauges`, or
    /// `histograms`**, otherwise this will deadlock on the same-thread
    /// read lock acquisition.
    ///
    /// Use `total_keys_excluding` when called from within a write-lock
    /// scope on one of the collections.
    #[cfg(test)]
    fn total_keys(&self) -> usize {
        let c = self.counters.read().map(|v| v.len()).unwrap_or(0);
        let g = self.gauges.read().map(|v| v.len()).unwrap_or(0);
        let h = self.histograms.read().map(|v| v.len()).unwrap_or(0);
        c + g + h
    }

    /// Returns total keys using `known_len` for one collection (whose write
    /// lock the caller already holds) and read-locking the other two.
    fn total_keys_excluding(&self, which: MetricKind, known_len: usize) -> usize {
        match which {
            MetricKind::Counter => {
                let g = self.gauges.read().map(|v| v.len()).unwrap_or(0);
                let h = self.histograms.read().map(|v| v.len()).unwrap_or(0);
                known_len + g + h
            }
            MetricKind::Gauge => {
                let c = self.counters.read().map(|v| v.len()).unwrap_or(0);
                let h = self.histograms.read().map(|v| v.len()).unwrap_or(0);
                c + known_len + h
            }
            MetricKind::Histogram => {
                let c = self.counters.read().map(|v| v.len()).unwrap_or(0);
                let g = self.gauges.read().map(|v| v.len()).unwrap_or(0);
                c + g + known_len
            }
        }
    }

    fn key_name(key: &Key) -> String {
        let name = key.name();
        let labels = key.labels();
        let mut result = name.to_string();
        for label in labels {
            result.push('{');
            result.push_str(label.key());
            result.push('=');
            result.push_str(label.value());
            result.push('}');
        }
        result
    }
}

/// Serializable snapshot of all recorded metrics at a point in time.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecorderSnapshot {
    pub counters: BTreeMap<String, u64>,
    pub gauges: BTreeMap<String, u64>,
    pub histograms: BTreeMap<String, LatencyPercentiles>,
    pub captured_at: u64,
}

/// Returns a snapshot of all recorded metrics.
///
/// Safe to call from any thread. Returns `None` if the recorder has not
/// been installed.
pub fn snapshot() -> Option<RecorderSnapshot> {
    let rec = RECORDER.get()?;
    let captured_at =
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);

    let counters = rec.counters.read().ok()?.iter().map(|(k, v)| (k.clone(), v.load(Ordering::Relaxed))).collect();

    let gauges = rec.gauges.read().ok()?.iter().map(|(k, v)| (k.clone(), v.load(Ordering::Relaxed))).collect();

    let histograms =
        rec.histograms.read().ok()?.iter().filter_map(|(k, h)| h.snapshot().map(|s| (k.clone(), s))).collect();

    Some(RecorderSnapshot { counters, gauges, histograms, captured_at })
}

/// Resets all histogram data. Call on session stop to avoid stale history
/// bleeding across session boundaries.
pub fn reset_histograms() {
    if let Some(rec) = RECORDER.get() {
        if let Ok(histograms) = rec.histograms.read() {
            for (_, h) in histograms.iter() {
                h.reset();
            }
        }
    }
}

static RECORDER: OnceLock<InMemoryRecorder> = OnceLock::new();

/// Installs the global in-memory metrics recorder.
///
/// Safe to call multiple times: only the first call takes effect.
/// Subsequent calls are silently ignored.
pub fn install() {
    RECORDER.get_or_init(InMemoryRecorder::new);
    // set_global_recorder returns Err if already set -- that is fine.
    let _ = metrics::set_global_recorder(RecorderProxy);
}

/// Zero-size proxy that delegates to the `OnceLock`-stored recorder.
struct RecorderProxy;

impl Recorder for RecorderProxy {
    fn describe_counter(&self, _key: KeyName, _unit: Option<Unit>, _description: SharedString) {}
    fn describe_gauge(&self, _key: KeyName, _unit: Option<Unit>, _description: SharedString) {}
    fn describe_histogram(&self, _key: KeyName, _unit: Option<Unit>, _description: SharedString) {}

    fn register_counter(&self, key: &Key, _metadata: &Metadata<'_>) -> Counter {
        let Some(rec) = RECORDER.get() else {
            return Counter::noop();
        };
        let name = InMemoryRecorder::key_name(key);

        // Fast path: check if already registered.
        if let Ok(counters) = rec.counters.read() {
            for (k, v) in counters.iter() {
                if *k == name {
                    return Counter::from_arc(Arc::clone(v));
                }
            }
        }

        // Slow path: register under write lock.
        if let Ok(mut counters) = rec.counters.write() {
            // Double-check after acquiring write lock.
            for (k, v) in counters.iter() {
                if *k == name {
                    return Counter::from_arc(Arc::clone(v));
                }
            }
            if rec.total_keys_excluding(MetricKind::Counter, counters.len()) >= MAX_METRIC_KEYS {
                return Counter::noop();
            }
            let v = Arc::new(AtomicU64::new(0));
            counters.push((name, Arc::clone(&v)));
            return Counter::from_arc(v);
        }

        Counter::noop()
    }

    fn register_gauge(&self, key: &Key, _metadata: &Metadata<'_>) -> Gauge {
        let Some(rec) = RECORDER.get() else {
            return Gauge::noop();
        };
        let name = InMemoryRecorder::key_name(key);

        if let Ok(gauges) = rec.gauges.read() {
            for (k, v) in gauges.iter() {
                if *k == name {
                    return Gauge::from_arc(Arc::clone(v));
                }
            }
        }

        if let Ok(mut gauges) = rec.gauges.write() {
            for (k, v) in gauges.iter() {
                if *k == name {
                    return Gauge::from_arc(Arc::clone(v));
                }
            }
            if rec.total_keys_excluding(MetricKind::Gauge, gauges.len()) >= MAX_METRIC_KEYS {
                return Gauge::noop();
            }
            let v = Arc::new(AtomicU64::new(0));
            gauges.push((name, Arc::clone(&v)));
            return Gauge::from_arc(v);
        }

        Gauge::noop()
    }

    fn register_histogram(&self, key: &Key, _metadata: &Metadata<'_>) -> Histogram {
        let Some(rec) = RECORDER.get() else {
            return Histogram::noop();
        };
        let name = InMemoryRecorder::key_name(key);

        if let Ok(histograms) = rec.histograms.read() {
            for (k, h) in histograms.iter() {
                if *k == name {
                    return Histogram::from_arc(Arc::new(HistogramHandle(h.clone())));
                }
            }
        }

        if let Ok(mut histograms) = rec.histograms.write() {
            for (k, h) in histograms.iter() {
                if *k == name {
                    return Histogram::from_arc(Arc::new(HistogramHandle(h.clone())));
                }
            }
            if rec.total_keys_excluding(MetricKind::Histogram, histograms.len()) >= MAX_METRIC_KEYS {
                return Histogram::noop();
            }
            let h = LatencyHistogram::new();
            histograms.push((name, h.clone()));
            return Histogram::from_arc(Arc::new(HistogramHandle(h)));
        }

        Histogram::noop()
    }
}

// --- Histogram handle (seconds -> milliseconds bridge) ---

struct HistogramHandle(LatencyHistogram);

impl metrics::HistogramFn for HistogramHandle {
    fn record(&self, value: f64) {
        // metrics convention: durations in seconds.
        // LatencyHistogram stores milliseconds.
        let ms = (value * 1000.0) as u64;
        self.0.record(ms);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let _ = snapshot();
    }

    #[test]
    fn recorder_enforces_key_limit() {
        let rec = InMemoryRecorder::new();
        {
            let mut counters = rec.counters.write().unwrap();
            for i in 0..MAX_METRIC_KEYS {
                counters.push((format!("c_{i}"), Arc::new(AtomicU64::new(0))));
            }
        }
        assert_eq!(rec.total_keys(), MAX_METRIC_KEYS);
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
        install();
        install(); // second call must not panic
    }

    #[test]
    fn counter_increment_via_recorder() {
        install();
        metrics::counter!("test_counter_inc").increment(1);
        metrics::counter!("test_counter_inc").increment(2);
        if let Some(snap) = snapshot() {
            if let Some(&val) = snap.counters.get("test_counter_inc") {
                assert!(val >= 3, "expected >= 3, got {val}");
            }
        }
    }

    #[test]
    fn gauge_set_via_recorder() {
        install();
        metrics::gauge!("test_gauge_set").set(42.0);
        if let Some(snap) = snapshot() {
            if let Some(&val) = snap.gauges.get("test_gauge_set") {
                assert_eq!(val, 42.0_f64.to_bits());
            }
        }
    }

    #[test]
    fn histogram_record_via_recorder() {
        install();
        metrics::histogram!("test_hist_rec").record(0.050);
        if let Some(snap) = snapshot() {
            if let Some(perc) = snap.histograms.get("test_hist_rec") {
                assert!(perc.p50 >= 45 && perc.p50 <= 55, "p50={}", perc.p50);
            }
        }
    }

    #[test]
    fn reset_histograms_clears_data() {
        install();
        metrics::histogram!("test_reset_h").record(0.100);
        reset_histograms();
        if let Some(snap) = snapshot() {
            assert!(!snap.histograms.contains_key("test_reset_h"), "histogram should be empty after reset");
        }
    }

    #[test]
    fn total_keys_excluding_returns_correct_count() {
        let rec = InMemoryRecorder::new();
        rec.counters.write().unwrap().push(("c1".into(), Arc::new(AtomicU64::new(0))));
        rec.counters.write().unwrap().push(("c2".into(), Arc::new(AtomicU64::new(0))));
        rec.gauges.write().unwrap().push(("g1".into(), Arc::new(AtomicU64::new(0))));
        rec.histograms.write().unwrap().push(("h1".into(), LatencyHistogram::new()));

        assert_eq!(rec.total_keys(), 4);
        // When holding the counters write lock (len=2), exclude counters from read:
        assert_eq!(rec.total_keys_excluding(MetricKind::Counter, 2), 4);
        assert_eq!(rec.total_keys_excluding(MetricKind::Gauge, 1), 4);
        assert_eq!(rec.total_keys_excluding(MetricKind::Histogram, 1), 4);
    }

    #[test]
    fn register_histogram_does_not_deadlock() {
        // Regression test: register_histogram previously called total_keys()
        // while holding a write lock on histograms, causing a same-thread
        // RwLock deadlock. This test would hang (and be killed by the test
        // runner timeout) if the deadlock regresses.
        let rec = InMemoryRecorder::new();
        RECORDER.get_or_init(|| rec);
        install();

        // This call deadlocked before the fix — it must return within ms.
        metrics::histogram!("deadlock_regression_test").record(1.0);

        if let Some(snap) = snapshot() {
            assert!(
                snap.histograms.contains_key("deadlock_regression_test"),
                "histogram should be registered without deadlocking"
            );
        }
    }

    #[test]
    fn register_counter_does_not_deadlock() {
        install();
        metrics::counter!("counter_deadlock_regression").increment(1);
        if let Some(snap) = snapshot() {
            assert!(snap.counters.contains_key("counter_deadlock_regression"));
        }
    }

    #[test]
    fn register_gauge_does_not_deadlock() {
        install();
        metrics::gauge!("gauge_deadlock_regression").set(1.0);
        if let Some(snap) = snapshot() {
            assert!(snap.gauges.contains_key("gauge_deadlock_regression"));
        }
    }

    #[test]
    fn recorder_snapshot_serde_round_trip() {
        let mut counters = BTreeMap::new();
        counters.insert("requests".to_string(), 42);
        let mut gauges = BTreeMap::new();
        gauges.insert("active_conns".to_string(), 5);
        let mut histograms = BTreeMap::new();
        histograms
            .insert("latency".to_string(), LatencyPercentiles { p50: 10, p95: 50, p99: 99, min: 1, max: 200, count: 100 });

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
        install();
        metrics::counter!("shared_counter_test").increment(5);
        metrics::counter!("shared_counter_test").increment(3);
        if let Some(snap) = snapshot() {
            if let Some(&val) = snap.counters.get("shared_counter_test") {
                assert!(val >= 8, "expected >= 8, got {val}");
            }
        }
    }

    #[test]
    fn snapshot_captured_at_is_nonzero() {
        install();
        if let Some(snap) = snapshot() {
            assert!(snap.captured_at > 0, "captured_at should be a valid timestamp");
        }
    }

    #[test]
    fn default_recorder_snapshot_is_empty() {
        let snap = RecorderSnapshot::default();
        assert!(snap.counters.is_empty());
        assert!(snap.gauges.is_empty());
        assert!(snap.histograms.is_empty());
        assert_eq!(snap.captured_at, 0);
    }
}
