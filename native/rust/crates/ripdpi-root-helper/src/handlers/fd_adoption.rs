use std::net::{TcpStream, UdpSocket};
use std::os::fd::{FromRawFd, RawFd};

pub(in crate::handlers) fn adopt_tcp_stream(fd: RawFd) -> TcpStream {
    // SAFETY: root-helper command fds come from SCM_RIGHTS and ownership is
    // transferred into this process exactly once for the duration of a handler.
    unsafe { TcpStream::from_raw_fd(fd) }
}

pub(in crate::handlers) fn adopt_udp_socket(fd: RawFd) -> UdpSocket {
    // SAFETY: root-helper command fds come from SCM_RIGHTS and ownership is
    // transferred into this process exactly once for the duration of a handler.
    unsafe { UdpSocket::from_raw_fd(fd) }
}
