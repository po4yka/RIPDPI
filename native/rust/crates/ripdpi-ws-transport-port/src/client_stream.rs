use std::io::{self, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpStream};
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, RawFd};
use std::os::unix::net::UnixStream;
use std::time::Duration;

#[derive(Debug)]
pub enum ClientStream {
    Tcp(TcpStream),
    Unix(UnixStream),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClientPeer {
    Ip(SocketAddr),
    LocalPrivate,
}

impl ClientPeer {
    #[must_use]
    pub fn is_local(&self) -> bool {
        match self {
            Self::Ip(addr) => addr.ip().is_loopback(),
            Self::LocalPrivate => true,
        }
    }

    #[must_use]
    pub fn ip(&self) -> Option<IpAddr> {
        match self {
            Self::Ip(addr) => Some(addr.ip()),
            Self::LocalPrivate => None,
        }
    }
}

impl ClientStream {
    pub fn peer(&self) -> io::Result<ClientPeer> {
        match self {
            Self::Tcp(s) => s.peer_addr().map(ClientPeer::Ip),
            Self::Unix(_) => Ok(ClientPeer::LocalPrivate),
        }
    }

    pub fn local_addr(&self) -> io::Result<Option<SocketAddr>> {
        match self {
            Self::Tcp(s) => s.local_addr().map(Some),
            Self::Unix(_) => Ok(None),
        }
    }

    pub fn try_clone(&self) -> io::Result<Self> {
        match self {
            Self::Tcp(s) => s.try_clone().map(Self::Tcp),
            Self::Unix(s) => s.try_clone().map(Self::Unix),
        }
    }

    pub fn shutdown(&self, how: Shutdown) -> io::Result<()> {
        match self {
            Self::Tcp(s) => s.shutdown(how),
            Self::Unix(s) => s.shutdown(how),
        }
    }

    pub fn set_read_timeout(&self, d: Option<Duration>) -> io::Result<()> {
        match self {
            Self::Tcp(s) => s.set_read_timeout(d),
            Self::Unix(s) => s.set_read_timeout(d),
        }
    }

    pub fn set_write_timeout(&self, d: Option<Duration>) -> io::Result<()> {
        match self {
            Self::Tcp(s) => s.set_write_timeout(d),
            Self::Unix(s) => s.set_write_timeout(d),
        }
    }

    pub fn read_timeout(&self) -> io::Result<Option<Duration>> {
        match self {
            Self::Tcp(s) => s.read_timeout(),
            Self::Unix(s) => s.read_timeout(),
        }
    }

    pub fn write_timeout(&self) -> io::Result<Option<Duration>> {
        match self {
            Self::Tcp(s) => s.write_timeout(),
            Self::Unix(s) => s.write_timeout(),
        }
    }

    pub fn set_nonblocking(&self, nonblocking: bool) -> io::Result<()> {
        match self {
            Self::Tcp(s) => s.set_nonblocking(nonblocking),
            Self::Unix(s) => s.set_nonblocking(nonblocking),
        }
    }

    #[must_use]
    pub fn as_tcp(&self) -> Option<&TcpStream> {
        match self {
            Self::Tcp(s) => Some(s),
            Self::Unix(_) => None,
        }
    }
}

impl Read for ClientStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        (&*self).read(buf)
    }
}

impl Write for ClientStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        (&*self).write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        (&*self).flush()
    }
}

impl Read for &ClientStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            ClientStream::Tcp(s) => (&*s).read(buf),
            ClientStream::Unix(s) => (&*s).read(buf),
        }
    }
}

impl Write for &ClientStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            ClientStream::Tcp(s) => (&*s).write(buf),
            ClientStream::Unix(s) => (&*s).write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            ClientStream::Tcp(s) => (&*s).flush(),
            ClientStream::Unix(s) => (&*s).flush(),
        }
    }
}

impl AsFd for ClientStream {
    fn as_fd(&self) -> BorrowedFd<'_> {
        match self {
            Self::Tcp(s) => s.as_fd(),
            Self::Unix(s) => s.as_fd(),
        }
    }
}

impl AsRawFd for ClientStream {
    fn as_raw_fd(&self) -> RawFd {
        match self {
            Self::Tcp(s) => s.as_raw_fd(),
            Self::Unix(s) => s.as_raw_fd(),
        }
    }
}

impl From<TcpStream> for ClientStream {
    fn from(s: TcpStream) -> Self {
        Self::Tcp(s)
    }
}

impl From<UnixStream> for ClientStream {
    fn from(s: UnixStream) -> Self {
        Self::Unix(s)
    }
}

#[cfg(test)]
mod tests {
    use std::net::TcpListener;

    use super::*;

    fn tcp_pair() -> (ClientStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let client = TcpStream::connect(listener.local_addr().expect("addr")).expect("connect");
        let (server, _) = listener.accept().expect("accept");
        (ClientStream::Tcp(server), client)
    }

    fn unix_pair() -> (ClientStream, UnixStream) {
        let (a, b) = UnixStream::pair().expect("pair");
        (ClientStream::Unix(a), b)
    }

    fn exercise<P: Read + Write>(mut stream: ClientStream, mut far: P) {
        far.write_all(b"ping").expect("far write");
        let mut buf = [0u8; 4];
        stream.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"ping");

        stream.write_all(b"pong").expect("write");
        far.read_exact(&mut buf).expect("far read");
        assert_eq!(&buf, b"pong");

        let mut clone = stream.try_clone().expect("clone");
        clone.write_all(b"cl").expect("clone write");
        let mut two = [0u8; 2];
        far.read_exact(&mut two).expect("far read clone");
        assert_eq!(&two, b"cl");

        assert_eq!(stream.read_timeout().expect("get"), None);
        let d = Some(Duration::from_millis(250));
        stream.set_read_timeout(d).expect("set read");
        stream.set_write_timeout(d).expect("set write");
        assert_eq!(stream.read_timeout().expect("get read"), d);
        assert_eq!(stream.write_timeout().expect("get write"), d);

        stream.shutdown(Shutdown::Write).expect("shutdown");
        let mut rest = Vec::new();
        far.read_to_end(&mut rest).expect("far eof");
        assert!(rest.is_empty());
    }

    #[test]
    fn unix_stream_behaves_like_a_client() {
        let (stream, far) = unix_pair();
        assert_eq!(stream.peer().expect("peer"), ClientPeer::LocalPrivate);
        assert_eq!(stream.local_addr().expect("local"), None);
        assert!(stream.as_tcp().is_none());
        exercise(stream, far);
    }

    #[test]
    fn tcp_stream_behaves_like_a_client() {
        let (stream, far) = tcp_pair();
        let far_addr = far.local_addr().expect("far addr");
        assert_eq!(stream.peer().expect("peer"), ClientPeer::Ip(far_addr));
        assert_eq!(stream.local_addr().expect("local"), Some(far.peer_addr().expect("far peer")));
        assert!(stream.as_tcp().is_some());
        exercise(stream, far);
    }

    #[test]
    fn peer_locality_and_ip() {
        let lo = ClientPeer::Ip("127.0.0.1:9".parse().expect("addr"));
        let remote = ClientPeer::Ip("192.0.2.1:9".parse().expect("addr"));
        assert!(lo.is_local() && ClientPeer::LocalPrivate.is_local() && !remote.is_local());
        assert_eq!(remote.ip(), Some("192.0.2.1".parse().expect("ip")));
        assert_eq!(ClientPeer::LocalPrivate.ip(), None);
    }
}
