use std::io::{ErrorKind, Read};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use crate::types::{TransferMeasurement, TransferSample};

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

    fn line(
        &mut self,
        tracker: &mut TransferTracker<'_>,
        budget: &mut usize,
        header: bool,
    ) -> Result<Vec<u8>, &'static str> {
        let mut bytes = Vec::new();
        loop {
            if *budget == 0 {
                return Err("invalid_framing");
            }
            *budget -= 1;
            let byte = self.byte(tracker)?;
            bytes.push(byte);
            if header {
                self.last_activity = Instant::now();
            }
            if bytes.ends_with(b"\r\n") {
                bytes.truncate(bytes.len() - 2);
                return Ok(bytes);
            }
            if byte == b'\n' {
                return Err("invalid_framing");
            }
        }
    }

    pub(super) fn headers(&mut self, tracker: &mut TransferTracker<'_>) -> Result<Vec<u8>, &'static str> {
        let mut headers = Vec::new();
        let mut budget = MAX_HEADER_BYTES;
        loop {
            let line = self.line(tracker, &mut budget, true)?;
            if line.is_empty() {
                return Ok(headers);
            }
            headers.extend_from_slice(&line);
            headers.extend_from_slice(b"\r\n");
        }
    }

    fn payload(&mut self, tracker: &mut TransferTracker<'_>, count: u64) -> Result<(), &'static str> {
        let mut remaining = count;
        while remaining > 0 {
            if !self.fill(tracker)? {
                return Err("early_eof");
            }
            let consumed = remaining.min((self.filled - self.position) as u64) as usize;
            self.position += consumed;
            remaining -= consumed as u64;
            self.last_activity = Instant::now();
            tracker.payload_received(&self.buffer[self.position - consumed..self.position]);
        }
        Ok(())
    }

    pub(super) fn body(
        &mut self,
        tracker: &mut TransferTracker<'_>,
        headers: &[u8],
        window: usize,
    ) -> Result<&'static str, &'static str> {
        let framing = framing(headers)?;
        self.last_activity = Instant::now();
        match framing {
            Framing::Length(length) => {
                tracker.measurement.expected_body_byte_count = Some(length);
                self.payload(tracker, length.min(window as u64))?;
                Ok(if length <= window as u64 { "content_length_complete" } else { "window_limit" })
            }
            Framing::Chunked => loop {
                let mut budget = MAX_HEADER_BYTES;
                let line = self.line(tracker, &mut budget, false)?;
                let size = line.split(|byte| *byte == b';').next().unwrap_or_default();
                if size.is_empty() || !size.iter().all(u8::is_ascii_hexdigit) {
                    return Err("invalid_framing");
                }
                let size = std::str::from_utf8(size)
                    .ok()
                    .and_then(|size| u64::from_str_radix(size, 16).ok())
                    .ok_or("invalid_framing")?;
                if size == 0 {
                    loop {
                        let trailer = self.line(tracker, &mut budget, false)?;
                        if trailer.is_empty() {
                            return Ok("chunked_complete");
                        }
                        if !trailer.contains(&b':') {
                            return Err("invalid_framing");
                        }
                    }
                }
                let remaining = (window as u64).saturating_sub(tracker.measurement.received_body_byte_count);
                self.payload(tracker, size.min(remaining))?;
                if size >= remaining {
                    return Ok("window_limit");
                }
                if self.byte(tracker)? != b'\r' || self.byte(tracker)? != b'\n' {
                    return Err("invalid_framing");
                }
            },
            Framing::Eof => {
                while tracker.measurement.received_body_byte_count < window as u64 {
                    if !self.fill(tracker)? {
                        return Ok("eof_complete");
                    }
                    let remaining = (window as u64) - tracker.measurement.received_body_byte_count;
                    self.payload(tracker, remaining.min((self.filled - self.position) as u64))?;
                }
                Ok("window_limit")
            }
        }
    }
}

enum Framing {
    Length(u64),
    Chunked,
    Eof,
}

fn framing(headers: &[u8]) -> Result<Framing, &'static str> {
    let text = std::str::from_utf8(headers).map_err(|_| "invalid_framing")?;
    let mut length = None;
    let mut transfer = None;
    for line in text.split("\r\n").skip(1).filter(|line| !line.is_empty()) {
        let (name, value) = line.split_once(':').ok_or("invalid_framing")?;
        if name.is_empty() || name.bytes().any(|byte| byte <= b' ' || byte >= 127) {
            return Err("invalid_framing");
        }
        if name.eq_ignore_ascii_case("content-length") {
            let value = value.trim();
            if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
                return Err("invalid_framing");
            }
            let parsed = value.parse::<u64>().map_err(|_| "invalid_framing")?;
            if parsed > i64::MAX as u64 {
                return Err("invalid_framing");
            }
            if length.is_some_and(|previous| previous != parsed) {
                return Err("invalid_framing");
            }
            length = Some(parsed);
        }
        if name.eq_ignore_ascii_case("transfer-encoding") {
            if transfer.is_some() || !value.trim().eq_ignore_ascii_case("chunked") {
                return Err("invalid_framing");
            }
            transfer = Some(());
        }
    }
    match (length, transfer) {
        (Some(_), Some(_)) => Err("invalid_framing"),
        (Some(length), None) => Ok(Framing::Length(length)),
        (None, Some(())) => Ok(Framing::Chunked),
        (None, None) => Ok(Framing::Eof),
    }
}

#[cfg(test)]
mod tests;
