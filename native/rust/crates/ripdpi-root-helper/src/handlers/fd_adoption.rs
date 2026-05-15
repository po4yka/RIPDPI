use std::net::{TcpStream, UdpSocket};
use std::os::fd::{FromRawFd, RawFd};

/// Adopt ownership of a raw fd as a `TcpStream`.
///
/// This is `unsafe` because `TcpStream::from_raw_fd` requires `fd` to be a
/// live, exclusively-owned, kernel-valid TCP socket. The safe `fn` form of
/// this helper used to silently propagate that contract to every caller via
/// comment — soundness issue #3. The helper now surfaces the contract on
/// the signature itself; each caller must enter its own `unsafe` block with
/// a SAFETY comment naming how it knows the fd is sound (in this crate, all
/// callers received the fd over SCM_RIGHTS in a single handler frame and
/// release it via `into_raw_fd` on every exit path).
///
/// # Safety
///
/// The caller must guarantee that `fd`:
/// - is a kernel-valid file descriptor referring to a TCP socket,
/// - is uniquely owned by the caller for the lifetime of the returned
///   `TcpStream` (no other code closes or drops a handle referring to the
///   same descriptor), and
/// - is either dropped by the returned `TcpStream` or extracted with
///   `into_raw_fd` before this function returns control to a code path
///   that does not own the fd.
pub(in crate::handlers) unsafe fn adopt_tcp_stream(fd: RawFd) -> TcpStream {
    // SAFETY: contract delegated to the caller via this function's
    // `# Safety` section.
    unsafe { TcpStream::from_raw_fd(fd) }
}

/// Adopt ownership of a raw fd as a `UdpSocket`.
///
/// See [`adopt_tcp_stream`] — the same contract applies, substituting
/// "UDP socket" for "TCP socket".
///
/// # Safety
///
/// The caller must guarantee that `fd`:
/// - is a kernel-valid file descriptor referring to a UDP socket,
/// - is uniquely owned by the caller for the lifetime of the returned
///   `UdpSocket`, and
/// - is either dropped or extracted with `into_raw_fd` before any other
///   code path could re-use the same descriptor.
pub(in crate::handlers) unsafe fn adopt_udp_socket(fd: RawFd) -> UdpSocket {
    // SAFETY: contract delegated to the caller via this function's
    // `# Safety` section.
    unsafe { UdpSocket::from_raw_fd(fd) }
}
