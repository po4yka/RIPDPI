use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use super::{SCAN_CANCELLED_SIGNAL, SCAN_DEADLINE_SIGNAL};

const POLL_INTERVAL: Duration = Duration::from_millis(50);

pub(super) fn interruption(cancel: &AtomicBool, deadline: Instant) -> Option<&'static str> {
    // Acquire observes the scan owner's published cancellation flag.
    if cancel.load(Ordering::Acquire) {
        Some(SCAN_CANCELLED_SIGNAL)
    } else if Instant::now() >= deadline {
        Some(SCAN_DEADLINE_SIGNAL)
    } else {
        None
    }
}

/// Poll blocking TLS I/O so progress cannot extend the absolute handshake budget.
pub(super) struct HandshakeIo<'a> {
    stream: TcpStream,
    cancel: &'a AtomicBool,
    deadline: Instant,
    io_timeout: Duration,
}

impl<'a> HandshakeIo<'a> {
    pub(super) fn new(stream: TcpStream, cancel: &'a AtomicBool, deadline: Instant, io_timeout: Duration) -> Self {
        Self { stream, cancel, deadline, io_timeout }
    }

    fn next_timeout(&self, io_deadline: Instant) -> io::Result<Duration> {
        if let Some(reason) = interruption(self.cancel, self.deadline) {
            return Err(io::Error::other(reason));
        }
        io_deadline
            .checked_duration_since(Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .map(|remaining| remaining.min(self.deadline.saturating_duration_since(Instant::now())).min(POLL_INTERVAL))
            .filter(|remaining| !remaining.is_zero())
            .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "TLS socket I/O timed out"))
    }

    fn run_io<T>(
        &mut self,
        set_timeout: fn(&TcpStream, Option<Duration>) -> io::Result<()>,
        mut operation: impl FnMut(&mut TcpStream) -> io::Result<T>,
    ) -> io::Result<T> {
        let io_deadline = Instant::now() + self.io_timeout;
        loop {
            set_timeout(&self.stream, Some(self.next_timeout(io_deadline)?))?;
            match operation(&mut self.stream) {
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                    ) => {}
                result => return result,
            }
        }
    }
}

impl Read for HandshakeIo<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.run_io(TcpStream::set_read_timeout, |stream| stream.read(buffer))
    }
}

impl Write for HandshakeIo<'_> {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.run_io(TcpStream::set_write_timeout, |stream| stream.write(buffer))
    }

    fn flush(&mut self) -> io::Result<()> {
        if let Some(reason) = interruption(self.cancel, self.deadline) {
            return Err(io::Error::other(reason));
        }
        self.stream.flush()
    }
}
