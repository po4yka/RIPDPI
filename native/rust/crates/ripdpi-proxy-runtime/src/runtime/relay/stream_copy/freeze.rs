use std::time::{Duration, Instant};

pub(super) struct FreezeDetector {
    pub(super) window_ms: u64,
    pub(super) min_bytes: u64,
    pub(super) max_stalls: u32,
    pub(super) window_start: Instant,
    pub(super) window_bytes: u64,
    pub(super) consecutive_stalls: u32,
    pub(super) warm: bool,
    record: TlsRecordProgress,
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
            record: TlsRecordProgress::default(),
        }
    }

    pub(super) fn is_enabled(&self) -> bool {
        self.max_stalls > 0
    }

    /// The read timeout that lets `check` run once per window even when no byte arrives.
    pub(super) fn window(&self) -> Option<Duration> {
        (self.is_enabled() && self.window_ms > 0).then(|| Duration::from_millis(self.window_ms))
    }

    pub(super) fn record_bytes(&mut self, chunk: &[u8]) {
        self.warm = true;
        self.window_bytes += chunk.len() as u64;
        self.record.observe(chunk);
    }

    pub(super) fn check(&mut self, now: Instant) -> bool {
        if !self.is_enabled() || !self.warm {
            return false;
        }
        let elapsed = now.duration_since(self.window_start).as_millis() as u64;
        if elapsed >= self.window_ms {
            // Only a stall inside a TLS record is a freeze: an idle keepalive or an
            // upload-only connection rests at a record boundary.
            if self.window_bytes < self.min_bytes && self.record.mid_record() {
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

/// Tracks the inbound position relative to TLS record boundaries.
#[derive(Default)]
struct TlsRecordProgress {
    header: [u8; 5],
    header_len: usize,
    body_left: usize,
    not_tls: bool,
}

impl TlsRecordProgress {
    fn observe(&mut self, mut bytes: &[u8]) {
        while !self.not_tls && !bytes.is_empty() {
            if self.body_left > 0 {
                let take = self.body_left.min(bytes.len());
                self.body_left -= take;
                bytes = &bytes[take..];
                continue;
            }
            self.header[self.header_len] = bytes[0];
            self.header_len += 1;
            bytes = &bytes[1..];
            if self.header_len == self.header.len() {
                self.header_len = 0;
                // Record types 20..=23 (RFC 8446 section 5.1) with a 0x03 major version.
                self.not_tls = !(0x14..=0x17).contains(&self.header[0]) || self.header[1] != 0x03;
                self.body_left = usize::from(u16::from_be_bytes([self.header[3], self.header[4]]));
            }
        }
    }

    fn mid_record(&self) -> bool {
        !self.not_tls && (self.header_len > 0 || self.body_left > 0)
    }
}
