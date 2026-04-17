use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::platform;

pub(crate) struct UdpRelaySockets {
    pub(crate) client: UdpSocket,
}

pub(crate) fn build_udp_relay_sockets(ip: IpAddr, _protect_path: Option<&str>) -> io::Result<UdpRelaySockets> {
    let client = bind_udp_socket(SocketAddr::new(ip, 0), None)?;
    client.set_nonblocking(true)?;
    Ok(UdpRelaySockets { client })
}

fn bind_udp_socket(bind_addr: SocketAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
    let domain = match bind_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, Some(path))?;
    }
    socket.bind(&SockAddr::from(bind_addr))?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    Ok(socket)
}

pub(crate) fn build_udp_upstream_socket(
    target: SocketAddr,
    protect_path: Option<&str>,
    bind_low_port: bool,
) -> io::Result<UdpSocket> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, Some(path))?;
    }
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    if bind_low_port {
        let local_ip = match target {
            SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        };
        if let Err(err) = platform::bind_udp_low_port(&socket, local_ip, 4_096) {
            tracing::warn!(%target, %err, "failed to bind UDP flow to a low source port");
        }
    }
    socket.connect(target)?;
    socket.set_nonblocking(true)?;
    Ok(socket)
}
