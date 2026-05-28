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
    protect_socket_if_available(&socket, protect_path)?;
    socket.connect_timeout(&SockAddr::from(target), timeout)?;
    let stream: TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn bind_protected_udp(bind_addr: SocketAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
    let socket = UdpSocket::bind(bind_addr)?;
    protect_socket_if_available(&socket, protect_path)?;
    Ok(socket)
}

fn protect_socket_if_available<T: AsRawFd>(socket: &T, protect_path: Option<&str>) -> io::Result<()> {
    if ripdpi_runtime_platform::protect::has_protect_callback() {
        return ripdpi_runtime_platform::protect::protect_socket_via_callback(socket.as_raw_fd()).map_err(|error| {
            io::Error::new(error.kind(), format!("protect encrypted DNS socket via callback: {error}"))
        });
    }
    ripdpi_privileged_ops::protect_socket(socket, protect_path).map_err(|error| {
        io::Error::new(error.kind(), format!("protect encrypted DNS socket via socket server: {error}"))
    })
}
