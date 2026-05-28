use std::io;
use std::net::{SocketAddr, TcpStream as StdTcpStream};
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsConnectHooks;
use ripdpi_runtime_platform::protect::{has_protect_callback, protect_socket_via_callback};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::net::{TcpSocket, TcpStream};
use tokio::time::timeout;

use crate::dns_bootstrap::resolve_connect_targets;

pub(crate) async fn connect_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    timeout(Duration::from_millis(connect_timeout_ms), connect_transport_inner(host, port))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("connect to {host}:{port} timed out")))?
}

pub(crate) fn owned_fetch_dns_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_direct_tcp_connector(|target, timeout| async move {
        let domain = match target {
            SocketAddr::V4(_) => Domain::IPV4,
            SocketAddr::V6(_) => Domain::IPV6,
        };
        let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
        if has_protect_callback() {
            protect_socket_via_callback(socket.as_raw_fd())
                .map_err(|error| io::Error::new(error.kind(), format!("protect owned fetch DNS socket: {error}")))?;
        }
        socket.connect_timeout(&SockAddr::from(target), timeout)?;
        let stream: StdTcpStream = socket.into();
        stream.set_nodelay(true)?;
        Ok(stream)
    })
}

async fn connect_transport_inner(host: &str, port: u16) -> io::Result<TcpStream> {
    let targets = resolve_connect_targets(host, port).await?;
    let mut last_error = None;
    for target in targets {
        let socket = tcp_socket_for(target)?;
        protect_socket_if_available(&socket)?;
        match socket.connect(target).await {
            Ok(stream) => return Ok(stream),
            Err(error) => {
                last_error = Some(io::Error::new(error.kind(), format!("connect to {target}: {error}")));
            }
        }
    }
    Err(last_error.unwrap_or_else(|| {
        io::Error::new(io::ErrorKind::AddrNotAvailable, format!("connect to {host}:{port}: no usable addresses"))
    }))
}

fn tcp_socket_for(target: SocketAddr) -> io::Result<TcpSocket> {
    match target {
        SocketAddr::V4(_) => TcpSocket::new_v4(),
        SocketAddr::V6(_) => TcpSocket::new_v6(),
    }
}

fn protect_socket_if_available(socket: &TcpSocket) -> io::Result<()> {
    if has_protect_callback() {
        protect_socket_via_callback(socket.as_raw_fd())
            .map_err(|error| io::Error::new(error.kind(), format!("protect native TLS fetch socket: {error}")))?;
    }
    Ok(())
}
