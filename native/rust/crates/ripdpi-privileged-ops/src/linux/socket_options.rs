//! Linux socket-option wrappers for privileged network operations.
//!
//! Unsafe boundaries are centralized around typed `getsockopt` and `setsockopt`
//! calls with kernel ABI layouts supplied by callers.

use std::io;
use std::mem::{size_of, zeroed};
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::ptr;

use socket2::SockRef;

use crate::linux::fd::peer_addr;
use crate::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

/// Thin wrapper around `libc::setsockopt` that handles the return-code check
/// and `io::Error` conversion.
///
/// # Safety
/// `fd` must be a live socket descriptor; `val` must be a valid payload for the
/// given `level`/`name` combination per the Linux kernel ABI.
pub(crate) unsafe fn setsockopt_raw<T>(
    fd: libc::c_int,
    level: libc::c_int,
    name: libc::c_int,
    val: &T,
) -> io::Result<()> {
    let rc = unsafe { libc::setsockopt(fd, level, name, (val as *const T).cast(), size_of::<T>() as libc::socklen_t) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Thin wrapper around `libc::getsockopt` that handles zero-init, the
/// return-code check, and `io::Error` conversion. Returns the value together
/// with the actual byte length written by the kernel (useful for variable-size
/// structs like `tcp_info`).
///
/// # Safety
/// `fd` must be a live socket descriptor; `T` must match the kernel's expected
/// output layout for the given `level`/`name` combination.
pub(crate) unsafe fn getsockopt_raw<T>(
    fd: libc::c_int,
    level: libc::c_int,
    name: libc::c_int,
) -> io::Result<(T, libc::socklen_t)> {
    let mut val: T = unsafe { zeroed() };
    let mut len = size_of::<T>() as libc::socklen_t;
    let rc = unsafe { libc::getsockopt(fd, level, name, (&mut val as *mut T).cast(), &mut len) };
    if rc == 0 {
        Ok((val, len))
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Safe wrapper for socket options whose Linux ABI is a plain `c_int`.
pub(crate) fn set_c_int_sockopt(
    fd: libc::c_int,
    level: libc::c_int,
    name: libc::c_int,
    value: libc::c_int,
) -> io::Result<()> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `c_int`.
    unsafe { setsockopt_raw(fd, level, name, &value) }
}

/// Safe wrapper for socket options whose Linux ABI returns a plain `c_int`.
pub(crate) fn get_c_int_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int) -> io::Result<libc::c_int> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `c_int`.
    let (value, _len): (libc::c_int, _) = unsafe { getsockopt_raw(fd, level, name) }?;
    Ok(value)
}

/// Safe wrapper for socket options whose Linux ABI payload is exactly `u32`.
pub(crate) fn set_u32_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int, value: u32) -> io::Result<()> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `u32`.
    unsafe { setsockopt_raw(fd, level, name, &value) }
}

/// Safe wrapper for socket options whose Linux ABI returns exactly `u32`.
pub(crate) fn get_u32_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int) -> io::Result<u32> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `u32`.
    let (value, _len): (u32, _) = unsafe { getsockopt_raw(fd, level, name) }?;
    Ok(value)
}

#[repr(C)]
struct TcpMd5Sig {
    addr: libc::sockaddr_storage,
    pad1: u16,
    key_len: u16,
    pad2: u32,
    key: [u8; 80],
}

pub fn enable_tcp_fastopen_connect<T: AsRawFd>(socket: &T) -> io::Result<()> {
    set_c_int_sockopt(socket.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_FASTOPEN_CONNECT, 1)
}

pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    if usize::from(key_len) > 80 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "md5 key length exceeds linux tcp_md5sig limit"));
    }

    let fd = stream.as_raw_fd();
    let addr = peer_addr(fd)?;
    let md5 = TcpMd5Sig { addr, pad1: 0, key_len, pad2: 0, key: [0; 80] };

    // SAFETY: `md5` is a valid `tcp_md5sig`-compatible buffer and `fd` is a
    // live TCP socket owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, libc::TCP_MD5SIG, &md5) }
}

/// Clamp the TCP receive window to force the server to send small segments.
///
/// Setting `size` to a low value (e.g., 1 or 2) causes the kernel to advertise
/// a tiny window, preventing DPI from reassembling the response stream. The
/// kernel enforces a floor of `SOCK_MIN_RCVBUF / 2` so the effective window
/// will not drop below ~1152 bytes regardless of how small `size` is.
///
/// To remove the clamp on an already-connected socket, pass a value larger
/// than any reasonable advertised window (e.g., `1_000_000`); modern Linux
/// rejects `size=0` on established sockets with `EINVAL` (only `TCP_CLOSE`
/// sockets accept it).
pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    let val = size as libc::c_int;
    set_c_int_sockopt(stream.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_WINDOW_CLAMP, val)
}

/// Read the current `TCP_WINDOW_CLAMP` value on a socket.
#[cfg(test)]
pub fn get_tcp_window_clamp(stream: &TcpStream) -> io::Result<u32> {
    Ok(get_c_int_sockopt(stream.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_WINDOW_CLAMP)? as u32)
}

/// Set the socket receive buffer size (`SO_RCVBUF`).
///
/// On Linux the kernel doubles the requested value to account for bookkeeping
/// overhead.  Setting this **before** `connect()` influences the TCP window
/// scale factor negotiated in the SYN packet.
pub fn set_rcvbuf(fd: &impl AsRawFd, size: u32) -> io::Result<()> {
    let val = size as libc::c_int;
    set_c_int_sockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_RCVBUF, val)
}

/// Read the current `SO_RCVBUF` value on a socket.
#[cfg(test)]
pub fn get_rcvbuf(fd: &impl AsRawFd) -> io::Result<u32> {
    Ok(get_c_int_sockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_RCVBUF)? as u32)
}

/// Bind a UDP socket to a source port that is at most `max_port`.
///
/// Tries random ports in `[1024, max_port]` until one binds successfully.
/// Returns the bound port. Falls back to OS-assigned if all attempts fail.
pub fn bind_udp_low_port(socket: &UdpSocket, local_ip: IpAddr, max_port: u16) -> io::Result<u16> {
    let lower = 1024u16;
    if max_port <= lower {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "max_port too low"));
    }
    // Try a few random ports in the range [1024, max_port].
    let mut rng_state =
        (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().subsec_nanos()) as u16;
    for _ in 0..8 {
        rng_state = rng_state.wrapping_mul(25173).wrapping_add(13849);
        let port = lower + (rng_state % (max_port - lower + 1));
        let addr = SocketAddr::new(local_ip, port);
        let fd = socket.as_raw_fd();
        let sa = socket2::SockAddr::from(addr);
        let ret = unsafe { libc::bind(fd, sa.as_ptr().cast(), sa.len()) };
        if ret == 0 {
            return Ok(port);
        }
    }
    // Fallback: let OS pick.
    let addr = SocketAddr::new(local_ip, 0);
    let fd = socket.as_raw_fd();
    let sa = socket2::SockAddr::from(addr);
    let ret = unsafe { libc::bind(fd, sa.as_ptr().cast(), sa.len()) };
    if ret != 0 {
        return Err(io::Error::last_os_error());
    }
    socket.local_addr().map(|a| a.port())
}

pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    // On dual-stack sockets both options may be valid, so attempt both and
    // succeed if at least one takes effect (mirrors `set_stream_ttl` pattern).
    let ipv4 = set_c_int_sockopt(fd, libc::IPPROTO_IP, libc::IP_RECVTTL, 1);
    let ipv6 = set_c_int_sockopt(fd, libc::IPPROTO_IPV6, libc::IPV6_RECVHOPLIMIT, 1);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    if buf.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "read_chunk_with_ttl: buf must not be empty"));
    }
    let fd = stream.as_raw_fd();
    let ctrl_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as u32) } as usize;
    let mut ctrl = vec![0u8; ctrl_len];
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };
    let mut msg: libc::msghdr = unsafe { zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl.as_mut_ptr().cast();
    msg.msg_controllen = ctrl_len;

    // SAFETY: `msg` references live stack/heap storage for the iov and control
    // buffers, and `fd` is a valid TCP socket descriptor owned by `stream`.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Ok((0, None));
    }

    let mut ttl: Option<u8> = None;
    // SAFETY: `msg` was just populated by `recvmsg`; CMSG_FIRSTHDR/CMSG_NXTHDR
    // iterate over the ancillary data buffer we provided.
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let cmsg_ref = unsafe { &*cmsg };
        if (cmsg_ref.cmsg_level == libc::IPPROTO_IP && cmsg_ref.cmsg_type == libc::IP_TTL)
            || (cmsg_ref.cmsg_level == libc::IPPROTO_IPV6 && cmsg_ref.cmsg_type == libc::IPV6_HOPLIMIT)
        {
            // SAFETY: cmsg_data points into the control buffer we own; the
            // kernel wrote a c_int there per the IP_TTL cmsg spec.
            let value: libc::c_int = unsafe { ptr::read_unaligned(libc::CMSG_DATA(cmsg).cast()) };
            ttl = u8::try_from(value).ok();
            break;
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }
    Ok((n as usize, ttl))
}

/// Read the current TTL (IPv4) or hop limit (IPv6) from a TCP socket.
/// Tries IPv4 first; falls back to IPv6. Returns the value from whichever
/// succeeds first.
pub(crate) fn get_stream_ttl(stream: &TcpStream) -> io::Result<u8> {
    let socket = SockRef::from(stream);
    if let Ok(ttl) = socket.ttl_v4() {
        return u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"));
    }
    let hops = socket.unicast_hops_v6()?;
    u8::try_from(hops).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket hop limit exceeds u8"))
}

pub(crate) fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl_v4(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

/// Attempt to set the IP TTL (IPv4) or unicast hop limit (IPv6) on a TCP
/// stream, returning a typed [`CapabilityOutcome`] rather than an
/// `io::Result`.
///
/// Error mapping:
/// - `ENOPROTOOPT` / `EOPNOTSUPP` / `EROFS` / `EINVAL` (kernel unsupported)
///   → `Unavailable { reason: Unsupported }`
/// - `EACCES` / `EPERM` (permission denied)
///   → `Unavailable { reason: PermissionDenied }`
/// - any other `io::Error`
///   → `ProbeFailed { error: err.to_string() }`
///
/// New code that needs to write a TTL should call this function rather than
/// [`set_stream_ttl`] directly (see module-level doc comment).
pub fn try_set_stream_ttl_with_outcome(stream: &TcpStream, ttl: u8) -> CapabilityOutcome<()> {
    match set_stream_ttl(stream, ttl) {
        Ok(()) => CapabilityOutcome::Available(()),
        Err(err) => match err.raw_os_error() {
            Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EROFS | libc::EINVAL) => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::TtlWrite,
                reason: CapabilityUnavailable::Unsupported,
            },
            Some(libc::EACCES | libc::EPERM) => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::TtlWrite,
                reason: CapabilityUnavailable::PermissionDenied,
            },
            _ => CapabilityOutcome::ProbeFailed { capability: RuntimeCapability::TtlWrite, error: err.to_string() },
        },
    }
}
