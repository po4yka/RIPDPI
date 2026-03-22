use std::sync::{Arc, Mutex};

use hdrhistogram::Histogram;
use serde::Serialize;

/// Thread-safe latency histogram wrapper.
///
/// Records values in milliseconds. Precision: 2 significant digits.
/// Max trackable: 60 000ms (60 seconds). Memory per instance: ~2KB.
///
/// Internally uses `Arc<Mutex<Histogram>>` so it can be cheaply cloned and
/// shared between producers (e.g. a DNS observer closure installed on `Stats`)
/// and consumers (e.g. the telemetry snapshot path).
#[derive(Clone)]
pub struct LatencyHistogram {
    inner: Arc<Mutex<Histogram<u64>>>,
}

impl LatencyHistogram {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(Histogram::<u64>::new_with_max(60_000, 2).expect("valid histogram parameters"))),
        }
    }

    /// Records a latency value in milliseconds. Values exceeding 60 000ms are clamped.
    pub fn record(&self, value_ms: u64) {
        let clamped = value_ms.min(60_000);
        if let Ok(mut h) = self.inner.lock() {
            // record never fails for values within [0, max_trackable_value]
            let _ = h.record(clamped);
        }
    }

    /// Returns percentile statistics, or `None` if no values have been recorded yet.
    pub fn snapshot(&self) -> Option<LatencyPercentiles> {
        let h = self.inner.lock().ok()?;
        if h.is_empty() {
            return None;
        }
        Some(LatencyPercentiles {
            p50: h.value_at_percentile(50.0),
            p95: h.value_at_percentile(95.0),
            p99: h.value_at_percentile(99.0),
            min: h.min(),
            max: h.max(),
            count: h.len(),
        })
    }

    /// Clears all recorded values. Call on session stop to avoid stale history
    /// bleeding across session boundaries.
    pub fn reset(&self) {
        if let Ok(mut h) = self.inner.lock() {
            h.reset();
        }
    }
}

impl Default for LatencyHistogram {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LatencyPercentiles {
    pub p50: u64,
    pub p95: u64,
    pub p99: u64,
    pub min: u64,
    pub max: u64,
    pub count: u64,
}

/// Latency distribution snapshots for each tracked metric.
///
/// Fields are `None` when no samples have been recorded for that metric yet
/// (e.g. no upstream connections established). `skip_serializing_if` ensures
/// the JSON output stays compact and backward-compatible.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LatencyDistributions {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dns_resolution: Option<LatencyPercentiles>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tcp_connect: Option<LatencyPercentiles>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tls_handshake: Option<LatencyPercentiles>,
}

impl LatencyDistributions {
    /// Returns `None` if all fields are `None` (no data recorded for any metric),
    /// so callers can use `skip_serializing_if = "Option::is_none"` on the parent field.
    pub fn into_option(self) -> Option<Self> {
        if self.dns_resolution.is_none() && self.tcp_connect.is_none() && self.tls_handshake.is_none() {
            None
        } else {
            Some(self)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_histogram_returns_none() {
        let h = LatencyHistogram::new();
        assert!(h.snapshot().is_none());
    }

    #[test]
    fn single_value_produces_equal_percentiles() {
        let h = LatencyHistogram::new();
        h.record(100);
        let s = h.snapshot().expect("snapshot after record");
        assert_eq!(s.count, 1);
        assert_eq!(s.p50, s.p95);
        assert_eq!(s.p95, s.p99);
        assert_eq!(s.min, s.max);
    }

    #[test]
    fn values_over_max_are_clamped() {
        let h = LatencyHistogram::new();
        h.record(999_999);
        let s = h.snapshot().expect("snapshot");
        // Values over 60 000ms are clamped before recording. The reported max
        // may be slightly above 60 000 due to HdrHistogram bucket quantization
        // (2 significant digits), but must be well below the unclamped input.
        assert!(s.max < 65_000, "clamped max {max} should be near 60 000, not 999 999", max = s.max);
    }

    #[test]
    fn reset_clears_histogram() {
        let h = LatencyHistogram::new();
        h.record(50);
        assert!(h.snapshot().is_some());
        h.reset();
        assert!(h.snapshot().is_none());
    }

    #[test]
    fn clone_shares_underlying_storage() {
        let h1 = LatencyHistogram::new();
        let h2 = h1.clone();
        h1.record(42);
        let s = h2.snapshot().expect("snapshot from clone");
        assert_eq!(s.count, 1);
    }

    #[test]
    fn percentile_ordering() {
        let h = LatencyHistogram::new();
        for v in [1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 500, 1000, 2000, 5000] {
            h.record(v);
        }
        let s = h.snapshot().expect("snapshot");
        assert!(s.min <= s.p50);
        assert!(s.p50 <= s.p95);
        assert!(s.p95 <= s.p99);
        assert!(s.p99 <= s.max);
        assert_eq!(s.count, 15);
    }

    #[test]
    fn distributions_into_option_is_none_when_all_fields_empty() {
        let d = LatencyDistributions::default();
        assert!(d.into_option().is_none());
    }

    #[test]
    fn distributions_into_option_is_some_with_any_field() {
        let d = LatencyDistributions {
            dns_resolution: Some(LatencyPercentiles { p50: 1, p95: 2, p99: 3, min: 0, max: 5, count: 10 }),
            ..Default::default()
        };
        assert!(d.into_option().is_some());
    }
}
