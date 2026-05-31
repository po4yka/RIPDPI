use std::io;
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsConnectHooks;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

pub(in crate::io_loop) fn encrypted_dns_connect_hooks(protect_path: Option<String>) -> EncryptedDnsConnectHooks {
    let tcp_protect_path = protect_path.clone();
    let udp_protect_path = protect_path;
    EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(move |target, timeout| {
            let tcp_protect_path = tcp_protect_path.clone();
            async move { connect_protected_tcp(target, timeout, tcp_protect_path.as_deref()) }
        })
        .with_direct_udp_binder(move |bind_addr| bind_protected_udp(bind_addr, udp_protect_path.as_deref()))
}

fn connect_protected_tcp(target: SocketAddr, timeout: Duration, protect_path: Option<&str>) -> io::Result<TcpStream> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    // Loopback targets never leave the device, so they cannot be captured by the TUN
    // route and are exempt from the protect invariant (vpnservice-protect-invariant.md).
    if !target.ip().is_loopback() {
        protect_non_loopback_socket(&socket, protect_path)?;
    }
    socket.connect_timeout(&SockAddr::from(target), timeout)?;
    let stream: TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn bind_protected_udp(bind_addr: SocketAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
    let socket = UdpSocket::bind(bind_addr)?;
    // The bind address is unspecified and does not reveal the remote, so we cannot prove
    // a loopback destination here; the binder always requires protection (fail closed).
    protect_non_loopback_socket(&socket, protect_path)?;
    Ok(socket)
}

/// Protects a socket whose traffic targets a non-loopback address under the active TUN.
///
/// Fails closed: when neither a registered protect callback nor a `protect_path` is
/// available there is no mechanism to keep the socket out of the TUN route, so the
/// connection is refused instead of silently dialing unprotected (the dns_intercept
/// path always runs under an active TUN — see vpnservice-protect-invariant.md).
fn protect_non_loopback_socket<T: AsRawFd>(socket: &T, protect_path: Option<&str>) -> io::Result<()> {
    if ripdpi_runtime_platform::protect::has_protect_callback() {
        return ripdpi_runtime_platform::protect::protect_socket_via_callback(socket.as_raw_fd()).map_err(|error| {
            io::Error::new(error.kind(), format!("protect encrypted DNS socket via callback: {error}"))
        });
    }
    let Some(protect_path) = protect_path else {
        return Err(io::Error::new(
            io::ErrorKind::NotConnected,
            "no protect mechanism (callback or socket path) available for non-loopback encrypted DNS socket under active TUN",
        ));
    };
    ripdpi_privileged_ops::protect_socket(socket, Some(protect_path)).map_err(|error| {
        io::Error::new(error.kind(), format!("protect encrypted DNS socket via socket server: {error}"))
    })
}
