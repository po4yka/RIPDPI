//! Linux raw-socket probing and packet send helpers.
//!
//! Unsafe boundaries are limited to socket options such as `IP_HDRINCL` before
//! sending caller-built packet bytes.

use std::io;
use std::net::{SocketAddr, SocketAddrV6};
use std::os::fd::{AsRawFd, BorrowedFd};

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::fragmentation::fragment_send_error;
use crate::fragmentation::preflight::{capability_error, protection_error, validate_fragment_mtu};

use crate::linux::socket_options::{get_u32_sockopt, set_c_int_sockopt, setsockopt_raw};

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
    mark_source: BorrowedFd<'_>,
) -> io::Result<()> {
    let socket =
        open_raw_socket(target, protect_path, mark_source, true).map_err(|error| fragment_send_error(error, false))?;
    let mut raw_target = target;
    raw_target.set_port(0);
    let sockaddr = raw_send_target(raw_target);
    socket.connect(&sockaddr).map_err(|error| fragment_send_error(error, false))?;
    let (level, option) =
        if target.is_ipv4() { (libc::IPPROTO_IP, libc::IP_MTU) } else { (libc::IPPROTO_IPV6, libc::IPV6_MTU) };
    let mtu = get_u32_sockopt(socket.as_raw_fd(), level, option).map_err(|error| fragment_send_error(error, false))?;
    validate_fragment_mtu([packets[0].len(), packets[1].len()], mtu)
        .map_err(|error| fragment_send_error(error, false))?;
    for packet in packets {
        socket.send_to(packet, &sockaddr).map_err(|error| fragment_send_error(error, true))?;
    }
    Ok(())
}

/// Sends caller-built IP packets from a fresh raw socket.
///
/// `mark_source` is the app socket the packets stand in for; see
/// [`open_raw_socket`] for how its route is reused.
pub(crate) fn send_raw_packets<'a, I>(
    target: SocketAddr,
    packets: I,
    protect_path: Option<&str>,
    mark_source: BorrowedFd<'_>,
) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    let socket = open_raw_socket(target, protect_path, mark_source, false)?;
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
    mark_source: BorrowedFd<'_>,
    inter_segment_delay_ms: u32,
) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    if inter_segment_delay_ms == 0 {
        return send_raw_packets(target, packets, protect_path, mark_source);
    }
    let socket = open_raw_socket(target, protect_path, mark_source, false)?;
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

/// Copies the fwmark of `source` to `socket`.
///
/// On Android the mark of an app socket carries its network selection and,
/// once `VpnService.protect()` ran, the bit that keeps it out of the VPN.
pub(crate) fn inherit_socket_mark(socket: &Socket, source: BorrowedFd<'_>) -> io::Result<()> {
    let mark = get_u32_sockopt(source.as_raw_fd(), libc::SOL_SOCKET, libc::SO_MARK)?;
    if mark == 0 {
        return Ok(());
    }
    set_c_int_sockopt(socket.as_raw_fd(), libc::SOL_SOCKET, libc::SO_MARK, mark.cast_signed())
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

/// Opens an `IPPROTO_RAW` socket with the header included.
///
/// With a protect path the VPN callback protects it. Without one (the root
/// helper, which runs outside the app) it takes the fwmark of `mark_source`,
/// so the crafted packets route like the protected app socket and stay out
/// of the VPN.
pub(super) fn open_raw_socket(
    target: SocketAddr,
    protect_path: Option<&str>,
    mark_source: BorrowedFd<'_>,
    fragmented: bool,
) -> io::Result<Socket> {
    let map_capability_error = |error| if fragmented { capability_error(error) } else { error };
    let map_protection_error = |error| if fragmented { protection_error(error) } else { error };
    let socket = match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))
                .map_err(map_capability_error)?;
            crate::protect_socket(&socket, protect_path).map_err(map_protection_error)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            socket
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))
                .map_err(map_capability_error)?;
            crate::protect_socket(&socket, protect_path).map_err(map_protection_error)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            socket
        }
    };
    if protect_path.is_none() {
        inherit_socket_mark(&socket, mark_source).map_err(map_protection_error)?;
    }
    Ok(socket)
}
