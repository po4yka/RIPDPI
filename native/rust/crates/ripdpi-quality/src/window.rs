//! Bounded, time-bucketed connection-quality windows.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use hdrhistogram::Histogram;

use crate::snapshot::{ConnectionQualitySnapshot, QualitySample, TransportKind};

const INSTANT_WINDOW_MS: u64 = 60_000;
const INSTANT_BUCKET_MS: u64 = 1_000;
const SERIES_WINDOW_MS: u64 = 15 * 60_000;
const SERIES_BUCKET_MS: u64 = 15_000;
const MAX_RTT_MS: u64 = 60_000;
const JITTER_DECAY: f64 = 15.0 / 16.0;

pub(crate) trait Clock: Send + Sync {
    fn monotonic_ms(&self) -> u64;
    fn unix_ms(&self) -> u64;
}

struct SystemClock {
    started: Instant,
}

impl SystemClock {
    fn new() -> Self {
        Self { started: Instant::now() }
    }
}

impl Clock for SystemClock {
    fn monotonic_ms(&self) -> u64 {
        self.started.elapsed().as_millis() as u64
    }

    fn unix_ms(&self) -> u64 {
        SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |duration| duration.as_millis() as u64)
    }
}

#[derive(Clone)]
pub struct QualityWindow {
    transport_kind: TransportKind,
    state: Arc<Mutex<QualityState>>,
    clock: Arc<dyn Clock>,
}

struct QualityState {
    instant: BucketWindow,
    series: BucketWindow,
}

impl QualityState {
    fn new() -> Self {
        Self {
            instant: BucketWindow::new(INSTANT_WINDOW_MS, INSTANT_BUCKET_MS),
            series: BucketWindow::new(SERIES_WINDOW_MS, SERIES_BUCKET_MS),
        }
    }
}

struct BucketWindow {
    duration_ms: u64,
    bucket_width_ms: u64,
    max_buckets: usize,
    buckets: VecDeque<QualityBucket>,
}

impl BucketWindow {
    fn new(duration_ms: u64, bucket_width_ms: u64) -> Self {
        let max_buckets = duration_ms.div_ceil(bucket_width_ms) as usize + 1;
        Self { duration_ms, bucket_width_ms, max_buckets, buckets: VecDeque::with_capacity(max_buckets) }
    }

    fn record(&mut self, monotonic_ms: u64, unix_ms: u64, sample: Option<QualitySample>, loss_pct: Option<f32>) {
        self.expire(monotonic_ms);
        let bucket_start_ms = monotonic_ms - (monotonic_ms % self.bucket_width_ms);
        if self.buckets.back().is_none_or(|bucket| bucket.start_monotonic_ms != bucket_start_ms) {
            self.buckets.push_back(QualityBucket::new(bucket_start_ms, unix_ms, monotonic_ms));
        }
        while self.buckets.len() > self.max_buckets {
            self.buckets.pop_front();
        }
        let bucket = self.buckets.back_mut().expect("record creates a quality bucket");
        bucket.last_observation_monotonic_ms = monotonic_ms;
        if let Some(sample) = sample {
            bucket.record_sample(sample);
        }
        if let Some(loss_pct) = loss_pct {
            bucket.record_loss(loss_pct);
        }
    }

    fn expire(&mut self, monotonic_ms: u64) {
        while self
            .buckets
            .front()
            .is_some_and(|bucket| monotonic_ms.saturating_sub(bucket.last_observation_monotonic_ms) >= self.duration_ms)
        {
            self.buckets.pop_front();
        }
    }

    fn snapshot(&mut self, monotonic_ms: u64, transport_kind: TransportKind) -> Option<ConnectionQualitySnapshot> {
        self.expire(monotonic_ms);
        if self.buckets.is_empty() {
            return None;
        }

        let mut histogram = new_histogram();
        let mut success = 0u64;
        let mut failure = 0u64;
        let mut loss_sum_milli = 0i64;
        let mut loss_count = 0u64;
        let mut jitter = 0.0f64;
        let mut previous_rtt: Option<u64> = None;
        for bucket in &self.buckets {
            let _ = histogram.add(&bucket.histogram);
            success = success.saturating_add(bucket.success_count);
            failure = failure.saturating_add(bucket.failure_count);
            loss_sum_milli = loss_sum_milli.saturating_add(bucket.loss_sum_milli);
            loss_count = loss_count.saturating_add(bucket.loss_count);
            if let (Some(previous), Some(first)) = (previous_rtt, bucket.first_rtt_ms) {
                jitter = JITTER_DECAY.mul_add(jitter, previous.abs_diff(first) as f64 / 16.0);
            }
            jitter = bucket.jitter_multiplier.mul_add(jitter, bucket.jitter_addend);
            if bucket.last_rtt_ms.is_some() {
                previous_rtt = bucket.last_rtt_ms;
            }
        }

        let total = success.saturating_add(failure);
        let connect_failure_loss = if total == 0 { 0.0 } else { (failure as f32 / total as f32) * 100.0 };
        let retransmit_loss =
            if loss_count == 0 { 0.0 } else { (loss_sum_milli.max(0) as f64 / loss_count as f64 / 1000.0) as f32 };
        let (p50, p95) = if histogram.is_empty() {
            (0, 0)
        } else {
            (histogram.value_at_percentile(50.0), histogram.value_at_percentile(95.0))
        };

        Some(ConnectionQualitySnapshot {
            loss_pct: connect_failure_loss.max(retransmit_loss).clamp(0.0, 100.0),
            rtt_p50_ms: p50,
            rtt_p95_ms: p95,
            jitter_ms: jitter.max(0.0) as u64,
            sample_count: success,
            window_start_at_ms: self.buckets.front().map_or(0, |bucket| bucket.first_observation_unix_ms),
            transport_kind,
        })
    }
}

struct QualityBucket {
    start_monotonic_ms: u64,
    first_observation_unix_ms: u64,
    last_observation_monotonic_ms: u64,
    histogram: Histogram<u64>,
    success_count: u64,
    failure_count: u64,
    loss_sum_milli: i64,
    loss_count: u64,
    first_rtt_ms: Option<u64>,
    last_rtt_ms: Option<u64>,
    jitter_multiplier: f64,
    jitter_addend: f64,
}

impl QualityBucket {
    fn new(start_monotonic_ms: u64, first_observation_unix_ms: u64, monotonic_ms: u64) -> Self {
        Self {
            start_monotonic_ms,
            first_observation_unix_ms,
            last_observation_monotonic_ms: monotonic_ms,
            histogram: new_histogram(),
            success_count: 0,
            failure_count: 0,
            loss_sum_milli: 0,
            loss_count: 0,
            first_rtt_ms: None,
            last_rtt_ms: None,
            jitter_multiplier: 1.0,
            jitter_addend: 0.0,
        }
    }

    fn record_sample(&mut self, sample: QualitySample) {
        if sample.succeeded {
            let rtt_ms = sample.rtt_ms.min(MAX_RTT_MS);
            self.success_count = self.success_count.saturating_add(1);
            let _ = self.histogram.record(rtt_ms);
            if self.first_rtt_ms.is_none() {
                self.first_rtt_ms = Some(rtt_ms);
            }
            if let Some(previous) = self.last_rtt_ms {
                self.jitter_multiplier *= JITTER_DECAY;
                self.jitter_addend = JITTER_DECAY.mul_add(self.jitter_addend, previous.abs_diff(rtt_ms) as f64 / 16.0);
            }
            self.last_rtt_ms = Some(rtt_ms);
        } else {
            self.failure_count = self.failure_count.saturating_add(1);
        }
        if sample.loss_pct > 0.0 {
            self.record_loss(sample.loss_pct);
        }
    }

    fn record_loss(&mut self, loss_pct: f32) {
        let clamped = if loss_pct.is_finite() { loss_pct.clamp(0.0, 100.0) } else { 0.0 };
        self.loss_sum_milli = self.loss_sum_milli.saturating_add((clamped * 1000.0) as i64);
        self.loss_count = self.loss_count.saturating_add(1);
    }
}

impl QualityWindow {
    #[must_use]
    pub fn new(transport_kind: TransportKind) -> Self {
        Self::with_clock(transport_kind, Arc::new(SystemClock::new()))
    }

    pub(crate) fn with_clock(transport_kind: TransportKind, clock: Arc<dyn Clock>) -> Self {
        Self { transport_kind, state: Arc::new(Mutex::new(QualityState::new())), clock }
    }

    pub fn record(&self, sample: QualitySample) {
        let monotonic_ms = self.clock.monotonic_ms();
        let unix_ms = self.clock.unix_ms();
        if let Ok(mut state) = self.state.lock() {
            state.instant.record(monotonic_ms, unix_ms, Some(sample), None);
            state.series.record(monotonic_ms, unix_ms, Some(sample), None);
        }
    }

    pub fn record_loss(&self, loss_pct: f32) {
        let monotonic_ms = self.clock.monotonic_ms();
        let unix_ms = self.clock.unix_ms();
        if let Ok(mut state) = self.state.lock() {
            state.instant.record(monotonic_ms, unix_ms, None, Some(loss_pct));
            state.series.record(monotonic_ms, unix_ms, None, Some(loss_pct));
        }
    }

    pub fn reset(&self) {
        if let Ok(mut state) = self.state.lock() {
            *state = QualityState::new();
        }
    }

    /// Snapshot of observations from the last 60 seconds.
    pub fn snapshot(&self) -> Option<ConnectionQualitySnapshot> {
        let monotonic_ms = self.clock.monotonic_ms();
        self.state.lock().ok()?.instant.snapshot(monotonic_ms, self.transport_kind)
    }

    /// Snapshot of observations from the last 15 minutes.
    pub fn snapshot_series(&self) -> Option<ConnectionQualitySnapshot> {
        let monotonic_ms = self.clock.monotonic_ms();
        self.state.lock().ok()?.series.snapshot(monotonic_ms, self.transport_kind)
    }
}

fn new_histogram() -> Histogram<u64> {
    Histogram::new_with_max(MAX_RTT_MS, 2).expect("valid quality histogram parameters")
}
