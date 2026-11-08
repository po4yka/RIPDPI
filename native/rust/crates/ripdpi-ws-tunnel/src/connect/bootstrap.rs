use std::io;
use std::net::SocketAddr;
use std::thread;
use std::time::{Duration, Instant};

use socket2::{SockAddr, Socket};

const BOOTSTRAP_POLL_INTERVAL: Duration = Duration::from_millis(10);

pub(super) struct Bootstrap<'a> {
    deadline: Option<Instant>,
    should_cancel: &'a dyn Fn() -> bool,
}

impl<'a> Bootstrap<'a> {
    pub(super) fn new(timeout: Option<Duration>, should_cancel: &'a dyn Fn() -> bool) -> io::Result<Self> {
        let deadline = timeout
            .map(|timeout| {
                Instant::now().checked_add(timeout).ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidInput, "WS bootstrap timeout exceeds the clock range")
                })
            })
            .transpose()?;
        Ok(Self { deadline, should_cancel })
    }

    pub(super) fn check(&self) -> io::Result<()> {
        if (self.should_cancel)() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "WS bootstrap cancelled"));
        }
        if self.deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "WS bootstrap deadline expired"));
        }
        Ok(())
    }

    fn poll_interval(&self) -> io::Result<Duration> {
        self.check()?;
        Ok(self.deadline.map_or(BOOTSTRAP_POLL_INTERVAL, |deadline| {
            deadline.saturating_duration_since(Instant::now()).min(BOOTSTRAP_POLL_INTERVAL)
        }))
    }

    pub(super) fn wait(&self) -> io::Result<()> {
        thread::sleep(self.poll_interval()?);
        self.check()
    }

    pub(super) fn connect(&self, socket: &Socket, target: SocketAddr) -> io::Result<()> {
        // socket2 leaves a timed-out connect pending: resume that socket instead of starting another connect.
        match socket.connect_timeout(&SockAddr::from(target), self.poll_interval()?) {
            Ok(()) => return self.check(),
            Err(error) if error.kind() == io::ErrorKind::TimedOut => {}
            Err(error) => return Err(error),
        }
        socket.set_nonblocking(true)?;
        loop {
            self.check()?;
            if let Some(error) = socket.take_error()? {
                return Err(error);
            }
            match socket.peer_addr() {
                Ok(_) => return self.check(),
                Err(error) if error.kind() == io::ErrorKind::NotConnected => self.wait()?,
                Err(error) => return Err(error),
            }
        }
    }
}
