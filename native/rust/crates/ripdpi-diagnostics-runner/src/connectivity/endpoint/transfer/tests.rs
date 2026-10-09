use super::*;
use std::io::{self, Cursor};

fn read_fixture(bytes: &[u8], window: usize) -> TransferMeasurement {
    let mut reader = Cursor::new(bytes);
    let cancel = AtomicBool::new(false);
    let mut callback = |_: &TransferMeasurement| {};
    let mut tracker = TransferTracker::new(1, 1, &mut callback);
    let mut reader =
        TransferReader::new(&mut reader, &cancel, Instant::now() + Duration::from_secs(1), Duration::from_secs(1));
    let headers = reader.headers(&mut tracker).expect("headers");
    let reason = reader.body(&mut tracker, &headers, window).unwrap_or_else(|reason| reason);
    tracker.finish(reason, window);
    tracker.measurement
}

#[test]
fn transfer_fixed_length_retains_truncation_and_expected_bytes() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc", 20);
    assert_eq!(result.received_body_byte_count, 3);
    assert_eq!(result.expected_body_byte_count, Some(10));
    assert_eq!(result.termination_reason.as_deref(), Some("early_eof"));
    assert!(!result.response_complete);
    assert!(result.first_body_byte_ms.is_some());
    assert_eq!(result.samples.last().expect("last").body_byte_count, 3);
}

#[test]
fn transfer_complete_short_response_is_not_a_completed_window() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc", 20);
    assert!(result.response_complete);
    assert!(!result.window_complete);
    assert_eq!(result.termination_reason.as_deref(), Some("content_length_complete"));
}

#[test]
fn transfer_window_limit_is_not_response_completion() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabcdefghij", 3);
    assert!(!result.response_complete);
    assert!(result.window_complete);
    assert_eq!(result.termination_reason.as_deref(), Some("window_limit"));
}

#[test]
fn transfer_chunked_excludes_metadata_and_supports_trailers() {
    let result = read_fixture(
        b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;ext=yes\r\nab\r\n1\r\nc\r\n0\r\nX-Test: yes\r\n\r\n",
        20,
    );
    assert_eq!(result.received_body_byte_count, 3);
    assert_eq!(result.expected_body_byte_count, None);
    assert_eq!(result.termination_reason.as_deref(), Some("chunked_complete"));
    assert!(result.response_complete);
}

#[test]
fn transfer_invalid_framing_retains_preceding_payload() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nabXX", 20);
    assert_eq!(result.received_body_byte_count, 2);
    assert_eq!(result.termination_reason.as_deref(), Some("invalid_framing"));
    assert!(!result.response_complete);
    for headers in [
        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nab".as_slice(),
        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\nab",
        b"HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\nab",
    ] {
        assert_eq!(read_fixture(headers, 20).termination_reason.as_deref(), Some("invalid_framing"));
    }
}

#[test]
fn transfer_eof_completion_keeps_length_unknown() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\n\r\nabc", 20);
    assert_eq!(result.received_body_byte_count, 3);
    assert_eq!(result.expected_body_byte_count, None);
    assert_eq!(result.termination_reason.as_deref(), Some("eof_complete"));
}

struct InterruptedReader<'a> {
    cancel: &'a AtomicBool,
    reads: usize,
    fail: ErrorKind,
}
impl Read for InterruptedReader<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.reads += 1;
        match self.reads {
            1 => {
                buffer[..3].copy_from_slice(b"abc");
                Ok(3)
            }
            2 if self.fail == ErrorKind::Interrupted => {
                self.cancel.store(true, Ordering::Release);
                Err(io::Error::from(ErrorKind::TimedOut))
            }
            _ => Err(io::Error::from(self.fail)),
        }
    }
}

#[test]
fn transfer_cancel_and_reset_preserve_partial_bytes() {
    for (fail, expected) in [(ErrorKind::Interrupted, "cancelled"), (ErrorKind::ConnectionReset, "reset")] {
        let cancel = AtomicBool::new(false);
        let mut source = InterruptedReader { cancel: &cancel, reads: 0, fail };
        let mut callback = |_: &TransferMeasurement| {};
        let mut tracker = TransferTracker::new(1, 1, &mut callback);
        let mut reader =
            TransferReader::new(&mut source, &cancel, Instant::now() + Duration::from_secs(1), Duration::from_secs(1));
        let reason = reader.body(&mut tracker, b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n", 20).expect_err("stopped");
        tracker.finish(reason, 20);
        assert_eq!(tracker.measurement.received_body_byte_count, 3);
        assert_eq!(reason, expected);
    }
}

struct PollingReader {
    polls: usize,
}
impl Read for PollingReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.polls += 1;
        if self.polls <= 3 {
            std::thread::sleep(Duration::from_millis(5));
            return Err(io::Error::from(ErrorKind::TimedOut));
        }
        buffer[0] = b'a';
        Ok(1)
    }
}

#[test]
fn transfer_poll_timeout_is_not_idle_timeout() {
    let cancel = AtomicBool::new(false);
    let mut source = PollingReader { polls: 0 };
    let mut callback = |_: &TransferMeasurement| {};
    let mut tracker = TransferTracker::new(1, 1, &mut callback);
    let mut reader =
        TransferReader::new(&mut source, &cancel, Instant::now() + Duration::from_secs(1), Duration::from_millis(100));
    assert_eq!(
        reader.body(&mut tracker, b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\n", 2),
        Ok("content_length_complete")
    );
    assert_eq!(tracker.measurement.received_body_byte_count, 1);
}

#[test]
fn transfer_idle_and_deadline_are_distinct() {
    for (idle, deadline, expected) in [
        (Duration::from_millis(5), Duration::from_secs(1), "idle_timeout"),
        (Duration::from_secs(1), Duration::ZERO, "deadline"),
    ] {
        let cancel = AtomicBool::new(false);
        let mut source = PollingReader { polls: 0 };
        let mut callback = |_: &TransferMeasurement| {};
        let mut tracker = TransferTracker::new(1, 1, &mut callback);
        let mut reader = TransferReader::new(&mut source, &cancel, Instant::now() + deadline, idle);
        assert_eq!(reader.body(&mut tracker, b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\n", 2), Err(expected));
    }
}

#[test]
fn transfer_samples_are_bounded_keep_endpoints_and_rate_limit() {
    let mut seen = Vec::new();
    let mut callback = |measurement: &TransferMeasurement| seen.push(measurement.clone());
    let mut tracker = TransferTracker::new(1, 1, &mut callback);
    for index in 1..1000 {
        tracker.received(1);
        tracker.measurement.elapsed_ms = index;
        tracker.sample();
    }
    tracker.started = Instant::now() - Duration::from_millis(1000);
    tracker.finish("window_limit", 999);
    assert!(tracker.measurement.samples.len() <= MAX_SAMPLES);
    assert_eq!(tracker.measurement.samples[0].body_byte_count, 0);
    assert_eq!(tracker.measurement.samples.last().expect("last").body_byte_count, 999);
    assert_eq!(seen.len(), 2, "initial and final only for a fast transfer");
}

#[test]
fn transfer_chunked_window_does_not_wait_for_unmeasured_suffix() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc", 3);
    assert!(result.window_complete);
    assert!(!result.response_complete);
    assert_eq!(result.termination_reason.as_deref(), Some("window_limit"));
}

#[test]
fn transfer_length_outside_shared_numeric_contract_is_invalid() {
    let result = read_fixture(b"HTTP/1.1 200 OK\r\nContent-Length: 18446744073709551615\r\n\r\nabc", 20);
    assert_eq!(result.expected_body_byte_count, None);
    assert_eq!(result.termination_reason.as_deref(), Some("invalid_framing"));
}
