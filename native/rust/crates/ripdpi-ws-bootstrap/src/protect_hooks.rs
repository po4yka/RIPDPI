use std::io;
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsConnectHooks;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use ripdpi_runtime_platform as platform;

pub(crate) fn build_direct_connect_hooks(protect_path: Option<&str>) -> EncryptedDnsConnectHooks {
    let hooks = encrypted_dns_connect_hooks();
    let Some(protect_path) = protect_path else {
        return hooks;
    };
    let tcp_protect_path = protect_path.to_string();
    let udp_protect_path = tcp_protect_path.clone();

    hooks
        .with_direct_tcp_connector(move |target, timeout| {
            connect_protected_tcp_socket(target, &tcp_protect_path, timeout)
        })
        .with_direct_udp_binder(move |bind_addr| bind_protected_udp_socket(bind_addr, &udp_protect_path))
}

fn encrypted_dns_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_dot_tls_connector_builder(|| {
        ripdpi_tls_profiles::configure_builder("chrome_stable").map_err(|error| error.to_string())
    })
}

fn connect_protected_tcp_socket(target: SocketAddr, protect_path: &str, timeout: Duration) -> io::Result<TcpStream> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    platform::vpn::protect_socket(&socket, Some(protect_path))?;
    socket.connect_timeout(&SockAddr::from(target), timeout)?;
    let stream: TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn bind_protected_udp_socket(bind_addr: SocketAddr, protect_path: &str) -> io::Result<UdpSocket> {
    let domain = match bind_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    platform::vpn::protect_socket(&socket, Some(protect_path))?;
    socket.bind(&SockAddr::from(bind_addr))?;
    let socket: UdpSocket = socket.into();
    socket.set_nonblocking(true)?;
    Ok(socket)
}
