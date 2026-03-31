use std::time::{Duration, Instant};

use ripdpi_config::RuntimeConfig;

pub(super) const FIRST_TLS_RECORD_ASSEMBLY_TIMEOUT: Duration = Duration::from_millis(75);
pub(super) const FIRST_TLS_RECORD_BYTES_LIMIT: usize = 16_384;

#[derive(Default)]
pub(super) struct TlsRecordBoundaryTracker {
    enabled: bool,
    disabled: bool,
    record_pos: usize,
    record_size: usize,
    header: [u8; 5],
    total_bytes: usize,
    bytes_limit: usize,
}

impl TlsRecordBoundaryTracker {
    pub(super) fn for_first_response(request: &[u8], config: &RuntimeConfig) -> Self {
        if !ripdpi_packets::is_tls_client_hello(request) || config.timeouts.partial_timeout_ms == 0 {
            return Self::default();
        }
        Self::enabled(first_response_bytes_limit(config))
    }

    fn enabled(bytes_limit: usize) -> Self {
        Self {
            enabled: true,
            disabled: false,
            record_pos: 0,
            record_size: 0,
            header: [0; 5],
            total_bytes: 0,
            bytes_limit,
        }
    }

    fn looks_like_client_hello_prefix(bytes: &[u8]) -> bool {
        match bytes {
            [0x16] | [0x16, 0x03] => true,
            [0x16, 0x03, minor, ..] => *minor <= 0x04,
            _ => false,
        }
    }

    pub(super) fn active(&self) -> bool {
        self.enabled && !self.disabled
    }

    pub(super) fn waiting_for_tls_record(&self) -> bool {
        self.active() && self.record_pos != 0 && self.record_pos != self.record_size
    }

    pub(super) fn observe(&mut self, bytes: &[u8]) {
        if !self.active() {
            return;
        }

        self.total_bytes += bytes.len();
        if self.bytes_limit != 0 && self.total_bytes > self.bytes_limit {
            self.disabled = true;
            return;
        }

        let mut pos = 0usize;
        while pos < bytes.len() {
            if self.record_pos < 5 {
                self.header[self.record_pos] = bytes[pos];
                self.record_pos += 1;
                pos += 1;
                if self.record_pos < 5 {
                    continue;
                }
                self.record_size = usize::from(u16::from_be_bytes([self.header[3], self.header[4]])) + 5;
                if !valid_tls_record_header(self.header) {
                    self.disabled = true;
                    return;
                }
            }

            if self.record_pos == self.record_size {
                self.record_pos = 0;
                self.record_size = 0;
                continue;
            }

            let remaining = self.record_size.saturating_sub(self.record_pos);
            if remaining == 0 {
                self.disabled = true;
                return;
            }
            let take = remaining.min(bytes.len() - pos);
            self.record_pos += take;
            pos += take;
        }
    }
}

fn first_response_bytes_limit(config: &RuntimeConfig) -> usize {
    match usize::try_from(config.timeouts.timeout_bytes_limit) {
        Ok(limit) if limit != 0 => limit,
        _ => FIRST_TLS_RECORD_BYTES_LIMIT,
    }
}

fn valid_tls_record_header(header: [u8; 5]) -> bool {
    let rec_type = header[0];
    (0x14..=0x18).contains(&rec_type) && header[1] == 0x03 && header[2] <= 0x04
}

pub(super) struct OutboundTlsFirstRecordAssembler {
    tracker: Option<TlsRecordBoundaryTracker>,
    buffer: Vec<u8>,
    started_at: Option<Instant>,
}

impl OutboundTlsFirstRecordAssembler {
    pub(super) fn new() -> Self {
        Self { tracker: None, buffer: Vec::new(), started_at: None }
    }

    pub(super) fn push(&mut self, chunk: &[u8], now: Instant) -> Option<Vec<u8>> {
        if self.buffer.is_empty() && self.tracker.is_none() {
            if !TlsRecordBoundaryTracker::looks_like_client_hello_prefix(chunk) {
                return Some(chunk.to_vec());
            }
            self.tracker = Some(TlsRecordBoundaryTracker::enabled(FIRST_TLS_RECORD_BYTES_LIMIT));
            self.started_at = Some(now);
        }

        self.buffer.extend_from_slice(chunk);
        let tracker = self.tracker.as_mut().expect("tls first-record tracker");
        tracker.observe(chunk);
        if !tracker.active() || (tracker.record_pos != 0 && tracker.record_pos == tracker.record_size) {
            return self.take();
        }
        None
    }

    pub(super) fn timeout(&self, now: Instant) -> Option<Duration> {
        let started_at = self.started_at?;
        self.is_buffering()
            .then(|| FIRST_TLS_RECORD_ASSEMBLY_TIMEOUT.saturating_sub(now.saturating_duration_since(started_at)))
            .map(|timeout| timeout.max(Duration::from_millis(1)))
    }

    pub(super) fn flush_on_timeout(&mut self, now: Instant) -> Option<Vec<u8>> {
        let started_at = self.started_at?;
        (self.is_buffering() && now.saturating_duration_since(started_at) >= FIRST_TLS_RECORD_ASSEMBLY_TIMEOUT)
            .then(|| self.take())
            .flatten()
    }

    pub(super) fn finish(&mut self) -> Option<Vec<u8>> {
        self.take()
    }

    pub(super) fn is_buffering(&self) -> bool {
        !self.buffer.is_empty() && self.tracker.as_ref().is_some_and(TlsRecordBoundaryTracker::waiting_for_tls_record)
    }

    fn take(&mut self) -> Option<Vec<u8>> {
        self.tracker = None;
        self.started_at = None;
        (!self.buffer.is_empty()).then(|| std::mem::take(&mut self.buffer))
    }
}
