mod assembler;
mod client_hello;

use std::time::{Duration, Instant};

pub(super) use assembler::TlsRecordBoundaryTracker;

pub(super) const FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT: Duration = Duration::from_millis(75);
pub(super) const FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT: usize = 16_384;

use client_hello::TlsClientHelloBoundaryTracker;

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
