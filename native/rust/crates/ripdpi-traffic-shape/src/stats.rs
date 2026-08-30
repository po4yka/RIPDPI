use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};

/// A point-in-time view of traffic-shaping overhead.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct TrafficShapeStatsSnapshot {
    /// Real application bytes placed into outgoing frames.
    pub transmitted_real_bytes: u64,
    /// Framing and padding bytes placed into outgoing frames.
    pub transmitted_padded_bytes: u64,
    /// Real application bytes recovered from incoming frames.
    pub received_real_bytes: u64,
    /// Framing and padding bytes removed from incoming frames.
    pub received_padded_bytes: u64,
    /// Outgoing frames containing no real application bytes.
    pub transmitted_dummy_frames: u64,
}

/// Lock-free aggregate counters for one shaped stream.
#[derive(Debug, Default, Clone)]
pub struct TrafficShapeStats {
    counters: Arc<TrafficShapeCounters>,
}

#[derive(Debug, Default)]
struct TrafficShapeCounters {
    transmitted_real_bytes: AtomicU64,
    transmitted_padded_bytes: AtomicU64,
    received_real_bytes: AtomicU64,
    received_padded_bytes: AtomicU64,
    transmitted_dummy_frames: AtomicU64,
}

impl TrafficShapeStatsSnapshot {
    /// Total real bytes observed in both directions.
    #[must_use]
    pub const fn real_bytes(self) -> u64 {
        self.transmitted_real_bytes.saturating_add(self.received_real_bytes)
    }

    /// Total framing and padding bytes observed in both directions.
    #[must_use]
    pub const fn padded_bytes(self) -> u64 {
        self.transmitted_padded_bytes.saturating_add(self.received_padded_bytes)
    }
}

impl TrafficShapeStats {
    /// Reads the independent relaxed counters into one observational snapshot.
    #[must_use]
    pub fn snapshot(&self) -> TrafficShapeStatsSnapshot {
        TrafficShapeStatsSnapshot {
            transmitted_real_bytes: self.counters.transmitted_real_bytes.load(Ordering::Relaxed),
            transmitted_padded_bytes: self.counters.transmitted_padded_bytes.load(Ordering::Relaxed),
            received_real_bytes: self.counters.received_real_bytes.load(Ordering::Relaxed),
            received_padded_bytes: self.counters.received_padded_bytes.load(Ordering::Relaxed),
            transmitted_dummy_frames: self.counters.transmitted_dummy_frames.load(Ordering::Relaxed),
        }
    }

    pub(crate) fn record_transmitted(&self, real_bytes: usize, frame_bytes: usize) {
        self.counters.transmitted_real_bytes.fetch_add(real_bytes as u64, Ordering::Relaxed);
        self.counters
            .transmitted_padded_bytes
            .fetch_add(frame_bytes.saturating_sub(real_bytes) as u64, Ordering::Relaxed);
        if real_bytes == 0 {
            self.counters.transmitted_dummy_frames.fetch_add(1, Ordering::Relaxed);
        }
    }

    pub(crate) fn record_received(&self, real_bytes: usize, frame_bytes: usize) {
        self.counters.received_real_bytes.fetch_add(real_bytes as u64, Ordering::Relaxed);
        self.counters.received_padded_bytes.fetch_add(frame_bytes.saturating_sub(real_bytes) as u64, Ordering::Relaxed);
    }
}
