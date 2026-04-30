use std::time::Instant;

pub(super) struct FreezeDetector {
    pub(super) window_ms: u64,
    pub(super) min_bytes: u64,
    pub(super) max_stalls: u32,
    pub(super) window_start: Instant,
    pub(super) window_bytes: u64,
    pub(super) consecutive_stalls: u32,
    pub(super) warm: bool,
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

    pub(super) fn is_enabled(&self) -> bool {
        self.max_stalls > 0
    }

    pub(super) fn record_bytes(&mut self, n: usize) {
        self.warm = true;
        self.window_bytes += n as u64;
    }

    pub(super) fn check(&mut self, now: Instant) -> bool {
        if !self.is_enabled() || !self.warm {
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
