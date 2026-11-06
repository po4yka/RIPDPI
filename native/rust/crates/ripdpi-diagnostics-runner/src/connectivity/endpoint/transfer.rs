use std::io::{ErrorKind, Read};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use crate::types::{TransferMeasurement, TransferSample};
use ripdpi_diagnostics_http::http::{HttpBodyFraming, HttpResponseDecoder, HttpResponseEvent, parse_http_response};

const MAX_HEADER_BYTES: usize = 16 * 1024;
const SAMPLE_INTERVAL_MS: u64 = 250;
const MAX_SAMPLES: usize = 64;

pub(super) struct TransferTracker<'a> {
    pub(super) measurement: TransferMeasurement,
    pub(super) started: Instant,
    // Transient classifier prefix. It never enters TransferMeasurement or evidence.
    pub(super) body_prefix: Vec<u8>,
    last_publish_ms: u64,
    progress: &'a mut dyn FnMut(&TransferMeasurement),
}

impl<'a> TransferTracker<'a> {
    pub(super) fn new(run_index: usize, run_count: usize, progress: &'a mut dyn FnMut(&TransferMeasurement)) -> Self {
        let measurement = TransferMeasurement {
            run_index,
            run_count,
            received_body_byte_count: 0,
            expected_body_byte_count: None,
            elapsed_ms: 0,
            first_body_byte_ms: None,
            last_body_progress_ms: None,
            termination_reason: None,
            response_complete: false,
            window_complete: false,
            samples: vec![TransferSample { elapsed_ms: 0, body_byte_count: 0 }],
        };
        progress(&measurement);
        Self { measurement, started: Instant::now(), last_publish_ms: 0, progress, body_prefix: Vec::new() }
    }

    fn elapsed_ms(&self) -> u64 {
        self.started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64
    }

    fn sample(&mut self) {
        // Halve interior points while preserving the first and latest observations.
        if self.measurement.samples.len() >= MAX_SAMPLES {
            let last = self.measurement.samples.len() - 1;
            self.measurement.samples = self
                .measurement
                .samples
                .iter()
                .enumerate()
                .filter(|(index, _)| *index == 0 || *index == last || index % 2 == 0)
                .map(|(_, sample)| sample.clone())
                .collect();
        }
        let sample = TransferSample {
            elapsed_ms: self.measurement.elapsed_ms,
            body_byte_count: self.measurement.received_body_byte_count,
        };
        if self.measurement.samples.last() != Some(&sample) {
            self.measurement.samples.push(sample);
        }
    }

    fn publish(&mut self, force: bool) {
        self.measurement.elapsed_ms = self.elapsed_ms();
        if force || self.measurement.elapsed_ms.saturating_sub(self.last_publish_ms) >= SAMPLE_INTERVAL_MS {
            self.sample();
            (self.progress)(&self.measurement);
            self.last_publish_ms = self.measurement.elapsed_ms;
        }
    }

    fn payload_received(&mut self, bytes: &[u8]) {
        // The existing classifier exempts bodies longer than 8192 bytes.
        let take = bytes.len().min(8193usize.saturating_sub(self.body_prefix.len()));
        self.body_prefix.extend_from_slice(&bytes[..take]);
        self.received(bytes.len());
    }

    fn received(&mut self, count: usize) {
        if count > 0 {
            let now = self.elapsed_ms();
            self.measurement.received_body_byte_count += count as u64;
            self.measurement.first_body_byte_ms.get_or_insert(now);
            self.measurement.last_body_progress_ms = Some(now);
            self.publish(false);
        }
    }

    pub(super) fn finish(&mut self, reason: &str, window: usize) {
        self.measurement.termination_reason = Some(reason.to_string());
        self.measurement.response_complete =
            matches!(reason, "content_length_complete" | "chunked_complete" | "eof_complete");
        self.measurement.window_complete = self.measurement.received_body_byte_count >= window as u64;
        self.publish(true);
    }
}

pub(super) fn interruption(cancel: &AtomicBool, deadline: Instant) -> Option<&'static str> {
    if cancel.load(Ordering::Acquire) {
        Some("cancelled")
    } else if Instant::now() >= deadline {
        Some("deadline")
    } else {
        None
    }
}

pub(super) struct TransferReader<'a, R> {
    stream: &'a mut R,
    cancel: &'a AtomicBool,
    deadline: Instant,
    idle_timeout: Duration,
    last_activity: Instant,
    buffer: [u8; 8192],
    position: usize,
    filled: usize,
}

impl<'a, R: Read> TransferReader<'a, R> {
    pub(super) fn new(stream: &'a mut R, cancel: &'a AtomicBool, deadline: Instant, idle_timeout: Duration) -> Self {
        Self {
            stream,
            cancel,
            deadline,
            idle_timeout,
            last_activity: Instant::now(),
            buffer: [0; 8192],
            position: 0,
            filled: 0,
        }
    }

    fn fill(&mut self, tracker: &mut TransferTracker<'_>) -> Result<bool, &'static str> {
        loop {
            if let Some(reason) = interruption(self.cancel, self.deadline) {
                return Err(reason);
            }
            if self.last_activity.elapsed() >= self.idle_timeout {
                return Err("idle_timeout");
            }
            if self.position < self.filled {
                return Ok(true);
            }
            match self.stream.read(&mut self.buffer) {
                Ok(0) => return Ok(false),
                Ok(count) => {
                    self.position = 0;
                    self.filled = count;
                    return Ok(true);
                }
                Err(error)
                    if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut | ErrorKind::Interrupted) =>
                {
                    tracker.publish(false);
                }
                Err(error)
                    if matches!(
                        error.kind(),
                        ErrorKind::ConnectionReset | ErrorKind::ConnectionAborted | ErrorKind::BrokenPipe
                    ) =>
                {
                    return Err("reset");
                }
                Err(error) if error.kind() == ErrorKind::UnexpectedEof => return Err("early_eof"),
                Err(_) => return Err("read_error"),
            }
        }
    }

    fn byte(&mut self, tracker: &mut TransferTracker<'_>) -> Result<u8, &'static str> {
        if !self.fill(tracker)? {
            return Err("early_eof");
        }
        let byte = self.buffer[self.position];
        self.position += 1;
        Ok(byte)
    }

    pub(super) fn headers(&mut self, tracker: &mut TransferTracker<'_>) -> Result<Vec<u8>, &'static str> {
        let mut headers = Vec::new();
        while headers.len() < MAX_HEADER_BYTES {
            headers.push(self.byte(tracker)?);
            self.last_activity = Instant::now();
            if headers.ends_with(b"\r\n\r\n") {
                parse_http_response(&headers, Vec::new()).map_err(|_| "invalid_framing")?;
                return Ok(headers);
            }
        }
        Err("invalid_framing")
    }

    pub(super) fn body(
        &mut self,
        tracker: &mut TransferTracker<'_>,
        headers: &[u8],
        window: usize,
    ) -> Result<&'static str, &'static str> {
        let mut decoder = HttpResponseDecoder::new("GET", MAX_HEADER_BYTES)?;
        let mut head = headers.to_vec();
        if !head.ends_with(b"\r\n\r\n") {
            head.extend_from_slice(b"\r\n");
        }
        let mut head = head.as_slice();
        let mut framing = HttpBodyFraming::Eof;
        self.last_activity = Instant::now();
        loop {
            match decoder
                .next_event()
                .map_err(|reason| if reason == "response_truncated" { "early_eof" } else { reason })?
            {
                HttpResponseEvent::NeedInput => {
                    if !head.is_empty() {
                        let take = head.len().min(decoder.input_capacity());
                        decoder.feed(&head[..take])?;
                        head = &head[take..];
                    } else if self.fill(tracker)? {
                        let take = (self.filled - self.position).min(decoder.input_capacity());
                        decoder.feed(&self.buffer[self.position..self.position + take])?;
                        self.position += take;
                    } else {
                        decoder.finish_input();
                    }
                }
                HttpResponseEvent::Head(_, kind) => {
                    framing = kind;
                    if let HttpBodyFraming::Length(length) = framing {
                        tracker.measurement.expected_body_byte_count = Some(length);
                    }
                }
                HttpResponseEvent::Data(bytes) => {
                    let remaining = window.saturating_sub(tracker.measurement.received_body_byte_count as usize);
                    let take = bytes.len().min(remaining);
                    tracker.payload_received(&bytes[..take]);
                    self.last_activity = Instant::now();
                    if tracker.measurement.received_body_byte_count >= window as u64 {
                        return Ok(if matches!(framing, HttpBodyFraming::Length(length) if length <= window as u64) {
                            "content_length_complete"
                        } else {
                            "window_limit"
                        });
                    }
                }
                HttpResponseEvent::Complete => {
                    return Ok(match framing {
                        HttpBodyFraming::Length(_) => "content_length_complete",
                        HttpBodyFraming::Chunked => "chunked_complete",
                        HttpBodyFraming::Eof => "eof_complete",
                    });
                }
            }
        }
    }
}

#[cfg(test)]
mod tests;
