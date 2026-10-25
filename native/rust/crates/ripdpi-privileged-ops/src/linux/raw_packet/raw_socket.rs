//! Linux raw-socket probing and packet send helpers.
//!
//! Unsafe boundaries are limited to socket options such as `IP_HDRINCL` before
//! sending caller-built packet bytes.

use std::io;
use std::net::{SocketAddr, SocketAddrV6};
use std::os::fd::AsRawFd;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::linux::socket_options::{set_c_int_sockopt, setsockopt_raw};

pub(crate) fn probe_raw_socket(
    domain: Domain,
    protocol: libc::c_int,
    protect_path: Option<&str>,
    level: libc::c_int,
    option_name: libc::c_int,
) -> io::Result<()> {
    let socket = Socket::new(domain, Type::RAW, Some(Protocol::from(protocol)))?;
    crate::protect_socket(&socket, protect_path)?;
    set_c_int_sockopt(socket.as_raw_fd(), level, option_name, 1)
}

pub(crate) fn send_raw_fragments(
    target: SocketAddr,
    packets: [&[u8]; 2],
    protect_path: Option<&str>,
) -> io::Result<()> {
    send_raw_packets(target, packets, protect_path)
}

pub(crate) fn send_raw_packets<'a, I>(target: SocketAddr, packets: I, protect_path: Option<&str>) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    let socket = match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            socket
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            socket
        }
    };
    let sockaddr = raw_send_target(target);
    for packet in packets {
        socket.send_to(packet, &sockaddr)?;
    }
    Ok(())
}

pub(crate) fn send_raw_packets_with_delay<'a, I>(
    target: SocketAddr,
    packets: I,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    if inter_segment_delay_ms == 0 {
        return send_raw_packets(target, packets, protect_path);
    }
    let socket = open_raw_socket(target, protect_path)?;
    let sockaddr = raw_send_target(target);
    let delay = std::time::Duration::from_millis(u64::from(inter_segment_delay_ms));
    let mut first = true;
    for packet in packets {
        if !first {
            std::thread::sleep(delay);
        }
        socket.send_to(packet, &sockaddr)?;
        first = false;
    }
    Ok(())
}

/// The address for a raw `sendto`. An AF_INET6 raw socket reads `sin6_port`
/// as the protocol number and fails with EINVAL above 255, so the transport
/// port is dropped; the crafted header already carries it.
pub(crate) fn raw_send_target(target: SocketAddr) -> SockAddr {
    match target {
        SocketAddr::V4(_) => SockAddr::from(target),
        SocketAddr::V6(addr) => {
            let raw_target = SocketAddr::V6(SocketAddrV6::new(*addr.ip(), 0, addr.flowinfo(), addr.scope_id()));
            SockAddr::from(raw_target)
        }
    }
}

fn open_raw_socket(target: SocketAddr, protect_path: Option<&str>) -> io::Result<Socket> {
    match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            Ok(socket)
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            Ok(socket)
        }
    }
}
