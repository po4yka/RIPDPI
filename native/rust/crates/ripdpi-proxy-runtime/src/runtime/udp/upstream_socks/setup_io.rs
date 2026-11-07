use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

use crate::runtime::state::RuntimeState;
use crate::sync::{AtomicBool, Ordering};

pub(super) struct UdpSetupIo<'a> {
    stream: &'a mut TcpStream,
    state: &'a RuntimeState,
    running: &'a AtomicBool,
    deadline: Option<Instant>,
}

impl<'a> UdpSetupIo<'a> {
    pub(super) fn new(
        stream: &'a mut TcpStream,
        state: &'a RuntimeState,
        running: &'a AtomicBool,
        deadline: Option<Instant>,
    ) -> Self {
        Self { stream, state, running, deadline }
    }

    pub(super) fn check_running(&self) -> io::Result<()> {
        if !self.running.load(Ordering::Relaxed) || self.state.shutdown_requested() {
            return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "UDP association stopped"));
        }
        if self.deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "upstream UDP setup timed out"));
        }
        Ok(())
    }

    fn io_timeout(&self) -> io::Result<Duration> {
        self.check_running()?;
        let timeout = self.deadline.map_or(Duration::from_millis(100), |deadline| {
            deadline.saturating_duration_since(Instant::now()).min(Duration::from_millis(100))
        });
        if timeout.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "upstream UDP setup timed out"));
        }
        Ok(timeout)
    }
}

impl Read for UdpSetupIo<'_> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        loop {
            let timeout = self.io_timeout()?;
            self.stream.set_read_timeout(Some(timeout))?;
            match self.stream.read(bytes) {
                Err(error) if retryable(&error) => {}
                result => return result,
            }
        }
    }
}

impl Write for UdpSetupIo<'_> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        loop {
            let timeout = self.io_timeout()?;
            self.stream.set_write_timeout(Some(timeout))?;
            match self.stream.write(bytes) {
                Err(error) if retryable(&error) => {}
                result => return result,
            }
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        self.check_running()?;
        self.stream.flush()
    }
}

fn retryable(error: &io::Error) -> bool {
    matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted)
}
