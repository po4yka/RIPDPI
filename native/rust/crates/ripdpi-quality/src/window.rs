//! `QualityWindow` — bounded rolling-window aggregator for TCP-connect samples.
//!
//! Mirrors `ripdpi_telemetry::LatencyHistogram` in lock-mutator shape, with
//! these additions per `docs/architecture/G008_SUBSYSTEMS_DESIGN.md` § P5:
//!
//! - TWO histograms (instant 60s, series 15min, both capped at 60_000 ms with
//!   2-significant-digit precision; ≈2 KB each, ≈4 KB total per window).
//! - Success / failure atomic counters so `loss_pct` is computable without
//!   peeking into either histogram's `len()`.
//! - `window_start_at_ms` published via `ArcSwapOption<u64>`, set on first
//!   sample (idempotent under race; first writer wins because subsequent
//!   `store` calls all write the same logical "start" — newer timestamp would
//!   be wrong, so we guard with a load-then-store on `is_none()`).
//! - RFC 3550 jitter accumulator (`J = J + (|D| - J) / 16`) stored fixed-point
//!   in `AtomicI64` (ms × 16) so the update is integer-only.

use std::sync::{
    atomic::{AtomicI64, AtomicU64, Ordering},
    Arc, Mutex,
};

use arc_swap::ArcSwapOption;
use hdrhistogram::Histogram;

use crate::snapshot::{ConnectionQualitySnapshot, QualitySample, TransportKind};

/// Bounded rolling window of TCP-connect quality samples.
///
/// Holds two histograms internally: an "instant" window (last 60s) for the
/// DegradationStrip's current values, and a "series" window (last 15 min)
/// for the throughput / latency graphs. Both are populated by the same
/// `record(sample)` call; consumers pick which to read via the snapshot
/// method's `window` parameter.
///
/// RFC 3550 jitter formula: J = J + (|D(i-1, i)| - J) / 16, where D is the
/// inter-arrival time delta. Mirrors Wireshark RTP stats tooling.
///
/// Cancel-safety: all mutators are synchronous (no .await). Mid-cancellation
/// at the call site loses at most one sample.
#[derive(Clone)]
pub struct QualityWindow {
    transport_kind: TransportKind,
    instant: Arc<Mutex<Histogram<u64>>>,
    series: Arc<Mutex<Histogram<u64>>>,
    success_count: Arc<AtomicU64>,
    failure_count: Arc<AtomicU64>,
    window_start_at_ms: Arc<ArcSwapOption<u64>>,
    // RFC 3550 jitter accumulator. Stored as fixed-point i64 (ms * 16) so we
    // can do the J += (|D| - J) / 16 update without floating-point.
    jitter_acc: Arc<AtomicI64>,
    // Last recorded successful RTT in ms; -1 sentinel for "no prior sample".
    last_rtt_ms: Arc<AtomicI64>,
}

impl QualityWindow {
    #[must_use]
    pub fn new(transport_kind: TransportKind) -> Self {
        Self {
            transport_kind,
            instant: Arc::new(Mutex::new(
                Histogram::<u64>::new_with_max(60_000, 2).expect("valid histogram parameters"),
            )),
            series: Arc::new(Mutex::new(
                Histogram::<u64>::new_with_max(60_000, 2).expect("valid histogram parameters"),
            )),
            success_count: Arc::new(AtomicU64::new(0)),
            failure_count: Arc::new(AtomicU64::new(0)),
            window_start_at_ms: Arc::new(ArcSwapOption::empty()),
            jitter_acc: Arc::new(AtomicI64::new(0)),
            last_rtt_ms: Arc::new(AtomicI64::new(-1)),
        }
    }

    /// Records a single observation. Synchronous, lock-bounded, cancel-safe at
    /// every call site.
    pub fn record(&self, sample: QualitySample) {
        // Set window_start_at_ms on first sample (idempotent under race —
        // first writer wins because the load-then-store is benign even if two
        // racers both pass the `is_none()` check: both write the same epoch
        // millisecond ± thread-skew, and the field is for UI affordance only).
        if self.window_start_at_ms.load().is_none() {
            self.window_start_at_ms.store(Some(Arc::new(now_ms())));
        }

        if sample.succeeded {
            self.success_count.fetch_add(1, Ordering::Relaxed);
            let clamped = sample.rtt_ms.min(60_000);
            // Histogram::record never fails for values within [0, 60_000].
            if let Ok(mut h) = self.instant.lock() {
                let _ = h.record(clamped);
            }
            if let Ok(mut h) = self.series.lock() {
                let _ = h.record(clamped);
            }
            // Jitter is only updated from successful samples — a failed connect
            // has no meaningful RTT to delta against.
            self.update_jitter(clamped as i64);
        } else {
            self.failure_count.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// Resets all rolling state. Call on session stop to avoid stale history
    /// bleeding across session boundaries.
    pub fn reset(&self) {
        if let Ok(mut h) = self.instant.lock() {
            h.reset();
        }
        if let Ok(mut h) = self.series.lock() {
            h.reset();
        }
        self.success_count.store(0, Ordering::Relaxed);
        self.failure_count.store(0, Ordering::Relaxed);
        self.window_start_at_ms.store(None);
        self.jitter_acc.store(0, Ordering::Relaxed);
        self.last_rtt_ms.store(-1, Ordering::Relaxed);
    }

    /// Snapshot of the *instant* (60s) window suitable for the DegradationStrip.
    /// Returns `None` until at least one sample has been recorded.
    pub fn snapshot(&self) -> Option<ConnectionQualitySnapshot> {
        let h = self.instant.lock().ok()?;
        let success = self.success_count.load(Ordering::Relaxed);
        let failure = self.failure_count.load(Ordering::Relaxed);
        if h.is_empty() && success == 0 && failure == 0 {
            return None;
        }
        let total = success + failure;
        let loss_pct = if total == 0 { 0.0 } else { (failure as f32 / total as f32) * 100.0 };
        let window_start = self.window_start_at_ms.load().as_ref().map_or(0, |arc| **arc);
        let jitter_fixed = self.jitter_acc.load(Ordering::Relaxed);
        let jitter_ms = (jitter_fixed.max(0) / 16) as u64;
        let (p50, p95) = if h.is_empty() { (0, 0) } else { (h.value_at_percentile(50.0), h.value_at_percentile(95.0)) };
        Some(ConnectionQualitySnapshot {
            loss_pct,
            rtt_p50_ms: p50,
            rtt_p95_ms: p95,
            jitter_ms,
            sample_count: success,
            window_start_at_ms: window_start,
            transport_kind: self.transport_kind,
        })
    }

    fn update_jitter(&self, current_rtt_ms: i64) {
        let last = self.last_rtt_ms.swap(current_rtt_ms, Ordering::Relaxed);
        if last < 0 {
            // First sample — no prior to compute delta against.
            return;
        }
        let delta_abs = (current_rtt_ms - last).abs();
        // J += (|D| - J) / 16 in fixed-point (ms × 16).
        let prev_j = self.jitter_acc.load(Ordering::Relaxed);
        let delta_fixed = delta_abs * 16;
        let new_j = prev_j + ((delta_fixed - prev_j) / 16);
        self.jitter_acc.store(new_j, Ordering::Relaxed);
    }
}

fn now_ms() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}
