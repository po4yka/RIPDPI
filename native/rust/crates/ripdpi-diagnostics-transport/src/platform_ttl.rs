//! Platform-gated helpers for the outbound socket TTL.

/// Set the outbound TTL (IPv4) or unicast hop limit (IPv6) on a connected stream.
///
/// The option follows the traffic family: a v4-mapped IPv6 peer sends IPv4 packets, so it gets the IPv4 TTL.
pub fn set_outbound_ttl(stream: &std::net::TcpStream, ttl: u8) -> std::io::Result<()> {
    if stream.peer_addr()?.ip().to_canonical().is_ipv4() {
        stream.set_ttl(u32::from(ttl))
    } else {
        socket2::SockRef::from(stream).set_unicast_hops_v6(u32::from(ttl))
    }
}

/// Make the next close of `stream` abortive (`SO_LINGER` 0): the kernel drops unsent data and sends RST.
pub fn set_abortive_close(stream: &std::net::TcpStream) -> std::io::Result<()> {
    socket2::SockRef::from(stream).set_linger(Some(std::time::Duration::ZERO))
}

/// Read the local outbound IPv4 TTL configured on a TCP stream.
///
/// Linux may return an IPv4 value for an IPv6 socket. Use the IPv4 option
/// only on an IPv4 connection. Linux does not supply received TTL as
/// `IP_RECVTTL` ancillary data for TCP sockets.
/// Returns `None` when the option is unavailable or inapplicable.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn get_local_socket_ttl(stream: &std::net::TcpStream) -> Option<u8> {
    use std::os::unix::io::AsRawFd;
    if !stream.peer_addr().ok()?.is_ipv4() {
        return None;
    }
    let fd = stream.as_raw_fd();
    let mut ttl: libc::c_int = 0;
    let mut len: libc::socklen_t = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
    // SAFETY: `fd` is a live socket descriptor borrowed from `stream` via
    // `as_raw_fd()`; `stream` outlives this call, so the fd stays valid for the
    // syscall's duration. `getsockopt(IPPROTO_IP, IP_TTL)` is a pure read: the
    // kernel writes a `c_int` into the buffer pointed to by the value pointer and
    // updates `len`. Both `ttl` (a `c_int`) and `len` (a `socklen_t`) are
    // stack-allocated, correctly aligned for their types, initialized, and their
    // pointers remain valid and exclusively borrowed for the duration of the call.
    // The return value is checked below before `ttl` is read.
    let ret = unsafe {
        libc::getsockopt(
            fd,
            libc::IPPROTO_IP,
            libc::IP_TTL,
            (&mut ttl as *mut libc::c_int).cast::<libc::c_void>(),
            &mut len,
        )
    };
    if ret == 0 && ttl > 0 && ttl <= 255 { Some(ttl as u8) } else { None }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn get_local_socket_ttl(_stream: &std::net::TcpStream) -> Option<u8> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr, TcpListener, TcpStream};

    #[test]
    fn pure_ipv6_connection_has_no_ipv4_ttl() {
        let listener = TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (_peer, _) = listener.accept().unwrap();
        assert_eq!(get_local_socket_ttl(&stream), None);
    }

    #[test]
    fn ipv4_socket_ttl_uses_the_local_value() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (peer, _) = listener.accept().unwrap();
        stream.set_ttl(33).unwrap();
        peer.set_ttl(77).unwrap();
        if cfg!(any(target_os = "linux", target_os = "android")) {
            assert_eq!(get_local_socket_ttl(&stream), Some(33));
        } else {
            assert_eq!(get_local_socket_ttl(&stream), None);
        }
    }

    #[test]
    fn outbound_ttl_setter_uses_the_peer_family() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        set_outbound_ttl(&stream, 7).unwrap();
        assert_eq!(stream.ttl().unwrap(), 7);

        let listener = TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        set_outbound_ttl(&stream, 9).unwrap();
        assert_eq!(socket2::SockRef::from(&stream).unicast_hops_v6().unwrap(), 9);
    }

    #[test]
    fn abortive_close_resets_the_peer() {
        use std::io::Read;
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut peer, _) = listener.accept().unwrap();
        set_abortive_close(&stream).unwrap();
        drop(stream);
        let error = peer.read(&mut [0; 1]).unwrap_err();
        assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
    }
}
