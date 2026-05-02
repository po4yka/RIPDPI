use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::linux::socket_options::setsockopt_raw;

pub(super) fn send_ip_packet(target: SocketAddr, packet: &[u8], protect_path: Option<&str>) -> io::Result<()> {
    let socket = match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: valid live socket fd and integer option payload.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            socket
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: valid live socket fd and integer option payload.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            socket
        }
    };
    socket.send_to(packet, &SockAddr::from(target))?;
    Ok(())
}

pub(super) fn send_icmp_packet(target: IpAddr, ttl: u8, packet: &[u8], protect_path: Option<&str>) -> io::Result<()> {
    let socket = match target {
        IpAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMP)))?;
            crate::protect_socket(&socket, protect_path)?;
            socket.set_ttl_v4(u32::from(ttl.max(1)))?;
            socket
        }
        IpAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMPV6)))?;
            crate::protect_socket(&socket, protect_path)?;
            socket.set_unicast_hops_v6(u32::from(ttl.max(1)))?;
            socket
        }
    };
    socket.send_to(packet, &SockAddr::from(SocketAddr::new(target, 0)))?;
    Ok(())
}

pub(super) fn open_icmp_recv_socket(bind_ip: IpAddr) -> io::Result<Socket> {
    let socket = match bind_ip {
        IpAddr::V4(_) => Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMP)))?,
        IpAddr::V6(_) => Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMPV6)))?,
    };
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))?;
    Ok(socket)
}

pub(super) fn sock_addr_ip(addr: &SockAddr) -> Option<IpAddr> {
    addr.as_socket().map(|socket| socket.ip())
}
