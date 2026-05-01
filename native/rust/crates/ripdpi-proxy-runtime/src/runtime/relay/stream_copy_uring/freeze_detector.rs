use std::time::Instant;

/// Freeze detector (duplicated from stream_copy to keep the uring module
/// self-contained behind the feature gate).
pub(super) struct FreezeDetector {
    window_ms: u64,
    min_bytes: u64,
    max_stalls: u32,
    window_start: Instant,
    window_bytes: u64,
    consecutive_stalls: u32,
    warm: bool,
}

impl FreezeDetector {
    pub(super) fn new(window_ms: u32, min_bytes: u32, max_stalls: u32) -> Self {
        Self {
            window_ms: u64::from(window_ms),
            min_bytes: u64::from(min_bytes),
            max_stalls,
            window_start: Instant::now(),
            window_bytes: 0,
            consecutive_stalls: 0,
            warm: false,
        }
    }

    pub(super) fn record_bytes(&mut self, n: usize) {
        self.warm = true;
        self.window_bytes += n as u64;
    }

    pub(super) fn check(&mut self, now: Instant) -> bool {
        if self.max_stalls == 0 || !self.warm {
            return false;
        }
        let elapsed = now.duration_since(self.window_start).as_millis() as u64;
        if elapsed >= self.window_ms {
            if self.window_bytes < self.min_bytes {
                self.consecutive_stalls += 1;
            } else {
                self.consecutive_stalls = 0;
            }
            self.window_start = now;
            self.window_bytes = 0;
        }
        self.consecutive_stalls >= self.max_stalls
    }
}
