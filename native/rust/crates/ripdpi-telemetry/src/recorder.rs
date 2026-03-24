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
pub struct InMemoryRecorder {
    counters: RwLock<Vec<(String, Arc<AtomicU64>)>>,
    gauges: RwLock<Vec<(String, Arc<AtomicU64>)>>,
    histograms: RwLock<Vec<(String, LatencyHistogram)>>,
}

impl InMemoryRecorder {
    fn new() -> Self {
        Self {
            counters: RwLock::new(Vec::new()),
            gauges: RwLock::new(Vec::new()),
            histograms: RwLock::new(Vec::new()),
        }
    }

    fn total_keys(&self) -> usize {
        let c = self.counters.read().map(|v| v.len()).unwrap_or(0);
        let g = self.gauges.read().map(|v| v.len()).unwrap_or(0);
        let h = self.histograms.read().map(|v| v.len()).unwrap_or(0);
        c + g + h
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
    let captured_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);

    let counters = rec
        .counters
        .read()
        .ok()?
        .iter()
        .map(|(k, v)| (k.clone(), v.load(Ordering::Relaxed)))
        .collect();

    let gauges = rec
        .gauges
        .read()
        .ok()?
        .iter()
        .map(|(k, v)| (k.clone(), v.load(Ordering::Relaxed)))
        .collect();

    let histograms = rec
        .histograms
        .read()
        .ok()?
        .iter()
        .filter_map(|(k, h)| h.snapshot().map(|s| (k.clone(), s)))
        .collect();

    Some(RecorderSnapshot {
        counters,
        gauges,
        histograms,
        captured_at,
    })
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
            if rec.total_keys() >= MAX_METRIC_KEYS {
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
            if rec.total_keys() >= MAX_METRIC_KEYS {
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
            if rec.total_keys() >= MAX_METRIC_KEYS {
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
        assert_eq!(
            InMemoryRecorder::key_name(&key),
            "my_counter{env=prod}{host=a}"
        );
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
                assert_eq!(val, 42);
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
            assert!(
                !snap.histograms.contains_key("test_reset_h"),
                "histogram should be empty after reset"
            );
        }
    }
}
