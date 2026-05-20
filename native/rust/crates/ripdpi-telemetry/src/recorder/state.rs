use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, RwLock};

use metrics::atomics::AtomicU64;
use metrics::Key;

use crate::{LatencyHistogram, LatencyPercentiles};

/// Maximum number of distinct metric keys. Prevents unbounded label
/// cardinality from consuming mobile memory.
pub(crate) const MAX_METRIC_KEYS: usize = 256;

/// In-memory metrics recorder for Android/CLI environments.
///
/// Stores counters, gauges and histograms in bounded collections.
/// Thread-safe: counters/gauges use Arc<AtomicU64> for stable addresses,
/// histograms reuse the existing `LatencyHistogram` wrapper.
///
/// Install once at process startup via [`install()`](crate::recorder::install). Poll via
/// [`snapshot()`](crate::recorder::snapshot) from JNI or CLI shutdown.
pub struct InMemoryRecorder {
    counters: RwLock<Vec<(String, Arc<AtomicU64>)>>,
    gauges: RwLock<Vec<(String, Arc<AtomicU64>)>>,
    histograms: RwLock<Vec<(String, LatencyHistogram)>>,
    key_count: Mutex<usize>,
}

#[cfg(test)]
pub(crate) enum MetricKind {
    Counter,
    Gauge,
    Histogram,
}

impl InMemoryRecorder {
    pub(crate) fn new() -> Self {
        Self {
            counters: RwLock::new(Vec::new()),
            gauges: RwLock::new(Vec::new()),
            histograms: RwLock::new(Vec::new()),
            key_count: Mutex::new(0),
        }
    }

    pub(crate) fn key_name(key: &Key) -> String {
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

    pub(crate) fn register_counter(&self, name: String) -> Option<Arc<AtomicU64>> {
        if let Ok(counters) = self.counters.read() {
            for (key, value) in counters.iter() {
                if *key == name {
                    return Some(Arc::clone(value));
                }
            }
        }

        let mut counters = self.counters.write().ok()?;
        for (key, value) in counters.iter() {
            if *key == name {
                return Some(Arc::clone(value));
            }
        }

        self.claim_key_slot()?;
        let value = Arc::new(AtomicU64::new(0));
        counters.push((name, Arc::clone(&value)));
        Some(value)
    }

    pub(crate) fn register_gauge(&self, name: String) -> Option<Arc<AtomicU64>> {
        if let Ok(gauges) = self.gauges.read() {
            for (key, value) in gauges.iter() {
                if *key == name {
                    return Some(Arc::clone(value));
                }
            }
        }

        let mut gauges = self.gauges.write().ok()?;
        for (key, value) in gauges.iter() {
            if *key == name {
                return Some(Arc::clone(value));
            }
        }

        self.claim_key_slot()?;
        let value = Arc::new(AtomicU64::new(0));
        gauges.push((name, Arc::clone(&value)));
        Some(value)
    }

    pub(crate) fn register_histogram(&self, name: String) -> Option<LatencyHistogram> {
        if let Ok(histograms) = self.histograms.read() {
            for (key, histogram) in histograms.iter() {
                if *key == name {
                    return Some(histogram.clone());
                }
            }
        }

        let mut histograms = self.histograms.write().ok()?;
        for (key, histogram) in histograms.iter() {
            if *key == name {
                return Some(histogram.clone());
            }
        }

        self.claim_key_slot()?;
        let histogram = LatencyHistogram::new();
        histograms.push((name, histogram.clone()));
        Some(histogram)
    }

    pub(crate) fn counter_values(&self) -> Option<Vec<(String, u64)>> {
        Some(
            self.counters
                .read()
                .ok()?
                .iter()
                .map(|(key, value)| (key.clone(), value.load(Ordering::Relaxed)))
                .collect(),
        )
    }

    pub(crate) fn gauge_values(&self) -> Option<Vec<(String, u64)>> {
        Some(self.gauges.read().ok()?.iter().map(|(key, value)| (key.clone(), value.load(Ordering::Relaxed))).collect())
    }

    pub(crate) fn histogram_values(&self) -> Option<Vec<(String, LatencyPercentiles)>> {
        Some(
            self.histograms
                .read()
                .ok()?
                .iter()
                .filter_map(|(key, histogram)| histogram.snapshot().map(|snapshot| (key.clone(), snapshot)))
                .collect(),
        )
    }

    pub(crate) fn reset_histograms(&self) {
        if let Ok(histograms) = self.histograms.read() {
            for (_, histogram) in histograms.iter() {
                histogram.reset();
            }
        }
    }

    /// Returns total number of registered metric keys across all collections.
    #[cfg(test)]
    pub(crate) fn total_keys(&self) -> usize {
        self.key_count.lock().map_or(0, |count| *count)
    }

    /// Returns total keys while the caller holds one collection write lock.
    ///
    /// `which` and `known_len` are retained to exercise the original
    /// deadlock-sensitive call shape in tests without taking more collection
    /// locks during key accounting.
    #[cfg(test)]
    pub(crate) fn total_keys_excluding(&self, which: MetricKind, known_len: usize) -> usize {
        let _ = which;
        let _ = known_len;
        self.total_keys()
    }

    fn claim_key_slot(&self) -> Option<()> {
        let mut count = self.key_count.lock().ok()?;
        if *count >= MAX_METRIC_KEYS {
            return None;
        }

        *count += 1;
        Some(())
    }
}
