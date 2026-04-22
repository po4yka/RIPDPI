use std::time::{Duration, Instant};

use ripdpi_config::RuntimeConfig;

use crate::runtime_policy::is_tls_client_hello_payload;

pub(super) const FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT: Duration = Duration::from_millis(75);
pub(super) const FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT: usize = 16_384;

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
        if !is_tls_client_hello_payload(request) || config.timeouts.partial_timeout_ms == 0 {
            return Self::default();
        }
        Self::enabled(first_response_bytes_limit(config))
    }

    fn enabled(bytes_limit: usize) -> Self {
        Self { enabled: true, bytes_limit, ..Self::default() }
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
        _ => FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT,
    }
}

fn valid_tls_record_header(header: [u8; 5]) -> bool {
    let rec_type = header[0];
    (0x14..=0x18).contains(&rec_type) && header[1] == 0x03 && header[2] <= 0x04
}

struct TlsClientHelloBoundaryTracker {
    enabled: bool,
    disabled: bool,
    total_bytes: usize,
    bytes_limit: usize,
    record_header: [u8; 5],
    record_header_pos: usize,
    record_payload_remaining: usize,
    handshake_header: [u8; 4],
    handshake_header_pos: usize,
    handshake_bytes_seen: usize,
    handshake_total: Option<usize>,
}

impl TlsClientHelloBoundaryTracker {
    fn enabled(bytes_limit: usize) -> Self {
        Self {
            enabled: true,
            bytes_limit,
            disabled: false,
            total_bytes: 0,
            record_header: [0; 5],
            record_header_pos: 0,
            record_payload_remaining: 0,
            handshake_header: [0; 4],
            handshake_header_pos: 0,
            handshake_bytes_seen: 0,
            handshake_total: None,
        }
    }

    fn active(&self) -> bool {
        self.enabled && !self.disabled && !self.client_hello_complete()
    }

    fn waiting_for_client_hello(&self) -> bool {
        self.active() && self.total_bytes != 0
    }

    fn client_hello_complete(&self) -> bool {
        self.handshake_total.is_some_and(|total| self.handshake_bytes_seen >= total)
    }

    fn observe(&mut self, bytes: &[u8]) {
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
            if self.record_header_pos < 5 {
                self.record_header[self.record_header_pos] = bytes[pos];
                self.record_header_pos += 1;
                pos += 1;
                if self.record_header_pos < 5 {
                    continue;
                }
                if !valid_tls_record_header(self.record_header) {
                    self.disabled = true;
                    return;
                }
                self.record_payload_remaining =
                    usize::from(u16::from_be_bytes([self.record_header[3], self.record_header[4]]));
                if self.record_payload_remaining == 0 {
                    self.disabled = true;
                    return;
                }
                if self.handshake_total.is_some() && self.record_header[0] != 0x16 {
                    self.disabled = true;
                    return;
                }
            }

            let take = self.record_payload_remaining.min(bytes.len() - pos);
            if take == 0 {
                self.disabled = true;
                return;
            }
            self.observe_handshake_bytes(&bytes[pos..pos + take]);
            if !self.active() {
                return;
            }
            self.record_payload_remaining -= take;
            pos += take;
            if self.record_payload_remaining == 0 {
                self.record_header_pos = 0;
            }
        }
    }

    fn observe_handshake_bytes(&mut self, bytes: &[u8]) {
        let mut pos = 0usize;

        while pos < bytes.len() && self.handshake_header_pos < 4 {
            self.handshake_header[self.handshake_header_pos] = bytes[pos];
            self.handshake_header_pos += 1;
            self.handshake_bytes_seen += 1;
            pos += 1;
        }

        if self.handshake_header_pos < 4 {
            return;
        }

        if self.handshake_total.is_none() {
            if self.handshake_header[0] != 0x01 {
                self.disabled = true;
                return;
            }
            let handshake_len = ((self.handshake_header[1] as usize) << 16)
                | ((self.handshake_header[2] as usize) << 8)
                | (self.handshake_header[3] as usize);
            self.handshake_total = Some(handshake_len.saturating_add(4));
        }

        let Some(total) = self.handshake_total else {
            return;
        };
        let remaining = total.saturating_sub(self.handshake_bytes_seen);
        let take = remaining.min(bytes.len().saturating_sub(pos));
        self.handshake_bytes_seen += take;
    }
}

pub(super) struct OutboundTlsClientHelloAssembler {
    tracker: Option<TlsClientHelloBoundaryTracker>,
    buffer: Vec<u8>,
    started_at: Option<Instant>,
}

impl OutboundTlsClientHelloAssembler {
    pub(super) fn new() -> Self {
        Self { tracker: None, buffer: Vec::new(), started_at: None }
    }

    pub(super) fn push(&mut self, chunk: &[u8], now: Instant) -> Option<Vec<u8>> {
        if self.buffer.is_empty() && self.tracker.is_none() {
            if !TlsRecordBoundaryTracker::looks_like_client_hello_prefix(chunk) {
                return Some(chunk.to_vec());
            }
            self.tracker = Some(TlsClientHelloBoundaryTracker::enabled(FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT));
            self.started_at = Some(now);
        }

        self.buffer.extend_from_slice(chunk);
        let tracker = self.tracker.as_mut().expect("tls client hello tracker");
        tracker.observe(chunk);
        if !tracker.active() {
            return self.take();
        }
        None
    }

    pub(super) fn timeout(&self, now: Instant) -> Option<Duration> {
        let started_at = self.started_at?;
        self.is_buffering()
            .then(|| FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT.saturating_sub(now.saturating_duration_since(started_at)))
            .map(|timeout| timeout.max(Duration::from_millis(1)))
    }

    pub(super) fn flush_on_timeout(&mut self, now: Instant) -> Option<Vec<u8>> {
        let started_at = self.started_at?;
        (self.is_buffering() && now.saturating_duration_since(started_at) >= FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT)
            .then(|| self.take())
            .flatten()
    }

    pub(super) fn finish(&mut self) -> Option<Vec<u8>> {
        self.take()
    }

    pub(super) fn is_buffering(&self) -> bool {
        !self.buffer.is_empty()
            && self.tracker.as_ref().is_some_and(TlsClientHelloBoundaryTracker::waiting_for_client_hello)
    }

    fn take(&mut self) -> Option<Vec<u8>> {
        self.tracker = None;
        self.started_at = None;
        (!self.buffer.is_empty()).then(|| std::mem::take(&mut self.buffer))
    }
}
