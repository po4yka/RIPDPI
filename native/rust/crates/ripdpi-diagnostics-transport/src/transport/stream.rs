use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::os::fd::{AsFd, BorrowedFd};
use std::os::unix::net::UnixStream;
use std::time::Duration;

#[derive(Debug)]
pub enum ProbeStream {
    Tcp(TcpStream),
    Unix(UnixStream),
}

impl ProbeStream {
    pub fn peer_addr(&self) -> io::Result<Option<SocketAddr>> {
        match self {
            Self::Tcp(stream) => stream.peer_addr().map(Some),
            Self::Unix(_) => Ok(None),
        }
    }

    pub fn local_addr(&self) -> io::Result<Option<SocketAddr>> {
        match self {
            Self::Tcp(stream) => stream.local_addr().map(Some),
            Self::Unix(_) => Ok(None),
        }
    }

    pub fn try_clone(&self) -> io::Result<Self> {
        match self {
            Self::Tcp(stream) => stream.try_clone().map(Self::Tcp),
            Self::Unix(stream) => stream.try_clone().map(Self::Unix),
        }
    }

    pub fn shutdown(&self, how: Shutdown) -> io::Result<()> {
        match self {
            Self::Tcp(stream) => stream.shutdown(how),
            Self::Unix(stream) => stream.shutdown(how),
        }
    }

    pub fn set_read_timeout(&self, timeout: Option<Duration>) -> io::Result<()> {
        match self {
            Self::Tcp(stream) => stream.set_read_timeout(timeout),
            Self::Unix(stream) => stream.set_read_timeout(timeout),
        }
    }

    pub fn set_write_timeout(&self, timeout: Option<Duration>) -> io::Result<()> {
        match self {
            Self::Tcp(stream) => stream.set_write_timeout(timeout),
            Self::Unix(stream) => stream.set_write_timeout(timeout),
        }
    }

    pub fn read_timeout(&self) -> io::Result<Option<Duration>> {
        match self {
            Self::Tcp(stream) => stream.read_timeout(),
            Self::Unix(stream) => stream.read_timeout(),
        }
    }

    pub fn write_timeout(&self) -> io::Result<Option<Duration>> {
        match self {
            Self::Tcp(stream) => stream.write_timeout(),
            Self::Unix(stream) => stream.write_timeout(),
        }
    }

    pub fn set_nonblocking(&self, nonblocking: bool) -> io::Result<()> {
        match self {
            Self::Tcp(stream) => stream.set_nonblocking(nonblocking),
            Self::Unix(stream) => stream.set_nonblocking(nonblocking),
        }
    }

    pub fn as_tcp(&self) -> Option<&TcpStream> {
        match self {
            Self::Tcp(stream) => Some(stream),
            Self::Unix(_) => None,
        }
    }
}

impl Read for ProbeStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Tcp(stream) => stream.read(buf),
            Self::Unix(stream) => stream.read(buf),
        }
    }
}

impl Write for ProbeStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            Self::Tcp(stream) => stream.write(buf),
            Self::Unix(stream) => stream.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            Self::Tcp(stream) => stream.flush(),
            Self::Unix(stream) => stream.flush(),
        }
    }
}

impl AsFd for ProbeStream {
    fn as_fd(&self) -> BorrowedFd<'_> {
        match self {
            Self::Tcp(stream) => stream.as_fd(),
            Self::Unix(stream) => stream.as_fd(),
        }
    }
}

impl From<TcpStream> for ProbeStream {
    fn from(stream: TcpStream) -> Self {
        Self::Tcp(stream)
    }
}

impl From<UnixStream> for ProbeStream {
    fn from(stream: UnixStream) -> Self {
        Self::Unix(stream)
    }
}

#[cfg(test)]
mod tests {
    use std::net::TcpListener;
    use std::os::fd::AsRawFd;

    use super::*;

    fn tcp_pair() -> (ProbeStream, ProbeStream) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let client = TcpStream::connect(listener.local_addr().expect("addr")).expect("connect");
        let (server, _) = listener.accept().expect("accept");
        (client.into(), server.into())
    }

    fn unix_pair() -> (ProbeStream, ProbeStream) {
        let (a, b) = UnixStream::pair().expect("pair");
        (a.into(), b.into())
    }

    fn exercise(mut a: ProbeStream, mut b: ProbeStream) {
        a.write_all(b"ping").expect("write");
        let mut buf = [0u8; 4];
        b.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"ping");

        let mut clone = a.try_clone().expect("clone");
        clone.write_all(b"pong").expect("clone write");
        b.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"pong");

        a.set_read_timeout(Some(Duration::from_millis(30))).expect("set timeout");
        a.set_write_timeout(Some(Duration::from_millis(40))).expect("set timeout");
        assert!(a.read_timeout().expect("get").is_some_and(|t| t >= Duration::from_millis(30)));
        assert!(a.write_timeout().expect("get").is_some_and(|t| t >= Duration::from_millis(40)));
        let error = a.read(&mut buf).expect_err("timeout");
        assert!(matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
        a.set_read_timeout(None).expect("clear");
        assert_eq!(a.read_timeout().expect("get"), None);

        a.set_nonblocking(true).expect("nonblocking");
        assert_eq!(a.read(&mut buf).expect_err("empty").kind(), io::ErrorKind::WouldBlock);
        a.set_nonblocking(false).expect("blocking");

        a.shutdown(Shutdown::Both).expect("shutdown");
        assert_eq!(b.read(&mut buf).expect("eof"), 0);
    }

    #[test]
    fn tcp_variant_round_trips_and_reports_addresses() {
        let (a, b) = tcp_pair();
        assert_eq!(a.peer_addr().expect("peer"), b.local_addr().expect("local"));
        assert_eq!(a.local_addr().expect("local"), b.peer_addr().expect("peer"));
        assert!(a.local_addr().expect("local").is_some_and(|addr| addr.ip().is_loopback()));
        assert!(a.as_tcp().is_some());
        exercise(a, b);
    }

    #[test]
    fn unix_variant_round_trips_without_socket_addresses() {
        let (a, b) = unix_pair();
        assert_eq!(a.peer_addr().expect("peer"), None);
        assert_eq!(a.local_addr().expect("local"), None);
        assert!(a.as_tcp().is_none());
        assert_ne!(a.as_fd().as_raw_fd(), b.as_fd().as_raw_fd());
        exercise(a, b);
    }
}
