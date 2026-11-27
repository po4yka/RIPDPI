use std::io;
use std::net::TcpListener;
use std::os::unix::net::UnixListener;
use std::path::{Path, PathBuf};

use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;
use ripdpi_runtime_api::ListenerAddress;
use ripdpi_ws_transport_port::ClientStream;

/// The proxy's listening socket.
#[derive(Debug)]
pub enum ProxyListener {
    Tcp(TcpListener),
    Unix(UnixProxyListener),
}

/// A bound Unix listener that owns its socket file and removes it when dropped.
pub struct UnixProxyListener {
    listener: UnixListener,
    path: PathBuf,
}

impl UnixProxyListener {
    /// Fails when a file already exists at `path`; that file is left untouched.
    pub(super) fn bind(path: &Path) -> io::Result<Self> {
        let listener = listener_platform::bind_unix_listener(path)?;
        Ok(Self { listener, path: path.to_owned() })
    }
}

// A derived Debug would print the private socket path.
impl std::fmt::Debug for UnixProxyListener {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("UnixProxyListener").finish_non_exhaustive()
    }
}

impl Drop for UnixProxyListener {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

impl From<TcpListener> for ProxyListener {
    fn from(listener: TcpListener) -> Self {
        Self::Tcp(listener)
    }
}

impl ProxyListener {
    /// The TCP bind address; a Unix listener has none and reports `Unsupported`.
    pub fn local_addr(&self) -> io::Result<std::net::SocketAddr> {
        match self {
            Self::Tcp(listener) => listener.local_addr(),
            Self::Unix(_) => Err(io::Error::new(io::ErrorKind::Unsupported, "unix listener has no IP address")),
        }
    }

    pub(super) fn set_nonblocking(&self, nonblocking: bool) -> io::Result<()> {
        match self {
            Self::Tcp(listener) => listener.set_nonblocking(nonblocking),
            Self::Unix(listener) => listener.listener.set_nonblocking(nonblocking),
        }
    }

    pub(super) fn accept(&self) -> io::Result<ClientStream> {
        match self {
            Self::Tcp(listener) => listener.accept().map(|(stream, _addr)| ClientStream::Tcp(stream)),
            Self::Unix(listener) => listener.listener.accept().map(|(stream, _addr)| ClientStream::Unix(stream)),
        }
    }

    /// The bound address and the IPv6-only option; a Unix listener has no IP address.
    pub(super) fn binding(&self) -> io::Result<(ListenerAddress, bool)> {
        match self {
            Self::Tcp(listener) => listener_platform::tcp_listener_binding(listener)
                .map(|(addr, ipv6_only)| (ListenerAddress::Tcp(addr), ipv6_only)),
            Self::Unix(_) => Ok((ListenerAddress::Unix, false)),
        }
    }
}
