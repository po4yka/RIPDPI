//! Platform-gated helpers for reading the configured outbound socket TTL.

/// Read the local outbound IPv4 TTL configured on a TCP stream.
///
/// `getsockopt(IP_TTL)` does not expose the TTL of a received packet. Ingress
/// TTL requires ancillary metadata from `recvmsg` with `IP_RECVTTL` enabled.
/// Returns `None` on non-Linux platforms or if the socket option is unavailable.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn get_local_socket_ttl(stream: &std::net::TcpStream) -> Option<u8> {
    use std::os::unix::io::AsRawFd;
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
