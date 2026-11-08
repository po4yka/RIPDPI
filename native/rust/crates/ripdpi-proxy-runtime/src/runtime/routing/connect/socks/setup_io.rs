use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

pub(super) struct SocksSetupIo<'a> {
    stream: &'a mut TcpStream,
    deadline: Option<Instant>,
}

impl<'a> SocksSetupIo<'a> {
    pub(super) fn new(stream: &'a mut TcpStream, deadline: Option<Instant>) -> Self {
        Self { stream, deadline }
    }

    pub(super) fn check_deadline(&self) -> io::Result<()> {
        self.remaining().map(|_| ())
    }

    fn remaining(&self) -> io::Result<Option<Duration>> {
        self.deadline
            .map(|deadline| {
                deadline
                    .checked_duration_since(Instant::now())
                    .filter(|duration| !duration.is_zero())
                    .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "upstream SOCKS setup timed out"))
            })
            .transpose()
    }
}

impl Read for SocksSetupIo<'_> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        self.stream.set_read_timeout(self.remaining()?)?;
        let result = self.stream.read(bytes);
        self.check_deadline()?;
        result
    }
}

impl Write for SocksSetupIo<'_> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.stream.set_write_timeout(self.remaining()?)?;
        let result = self.stream.write(bytes);
        self.check_deadline()?;
        result
    }

    fn flush(&mut self) -> io::Result<()> {
        self.check_deadline()?;
        self.stream.flush()
    }
}
