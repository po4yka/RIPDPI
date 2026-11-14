//! OS-primitive adapter — socket-option syscalls.
//!
//! TCP Fast Open, `TCP_MD5SIG`, SACK drop, window clamp, receive-buffer size,
//! low-port UDP bind, and TCP-stage waiting. Each is a thin `#[cfg]`-split
//! wrapper over `ripdpi-privileged-ops`; non-Linux targets return
//! `Unsupported`. Surfaced through the `socket` facade.

use std::io;
use std::net::{IpAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Duration;

#[cfg(test)]
mod tests;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_tcp_fastopen_connect<T: AsRawFd>(socket: &T) -> io::Result<()> {
    ripdpi_privileged_ops::enable_tcp_fastopen_connect(socket)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_tcp_fastopen_connect<T>(_socket: &T) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    ripdpi_privileged_ops::set_tcp_md5sig(stream, key_len)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_md5sig(_stream: &TcpStream, _key_len: u16) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::attach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::detach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    observe_window_option("window_clamp", size, ripdpi_privileged_ops::set_tcp_window_clamp(stream, size), || {
        ripdpi_privileged_ops::get_tcp_window_clamp(stream)
    })
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_window_clamp(_stream: &TcpStream, _size: u32) -> io::Result<()> {
    observe_window_option(
        "window_clamp",
        _size,
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android")),
        || Err(io::ErrorKind::Unsupported.into()),
    )
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_rcvbuf(fd: &impl AsRawFd, size: u32) -> io::Result<()> {
    observe_window_option("receive_buffer", size, ripdpi_privileged_ops::set_rcvbuf(fd, size), || {
        ripdpi_privileged_ops::get_rcvbuf(fd)
    })
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_rcvbuf(_fd: &impl AsRawFd, _size: u32) -> io::Result<()> {
    observe_window_option(
        "receive_buffer",
        _size,
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android")),
        || Err(io::ErrorKind::Unsupported.into()),
    )
}

fn observe_window_option(
    option: &'static str,
    requested: u32,
    result: io::Result<()>,
    readback: impl FnOnce() -> io::Result<u32>,
) -> io::Result<()> {
    // Readback reports a kernel option value, not a negotiated scale or a wire window.
    match &result {
        Ok(()) => match readback() {
            Ok(effective) => tracing::info!(
                ring = "proxy",
                source = "tcp_socket_option",
                kind = "tcp_socket_option",
                option,
                requested,
                effective,
                outcome = "applied",
                "TCP socket option {} requested={} effective={}",
                option,
                requested,
                effective
            ),
            Err(error) => record_window_option_failure(option, requested, "readback_failed", &error),
        },
        Err(error) => {
            let outcome = if window_option_error_kind(error) == "unsupported" { "unsupported" } else { "failed" };
            record_window_option_failure(option, requested, outcome, error);
        }
    }
    result
}

fn window_option_error_kind(error: &io::Error) -> &'static str {
    if error.kind() == io::ErrorKind::Unsupported || error.raw_os_error() == Some(libc::ENOPROTOOPT) {
        return "unsupported";
    }
    match error.kind() {
        io::ErrorKind::PermissionDenied => "permission_denied",
        io::ErrorKind::InvalidInput => "invalid_input",
        io::ErrorKind::NotConnected => "not_connected",
        _ => "other",
    }
}

fn record_window_option_failure(option: &'static str, requested: u32, outcome: &'static str, error: &io::Error) {
    let io_error_kind = window_option_error_kind(error);
    tracing::warn!(
        ring = "proxy",
        source = "tcp_socket_option",
        kind = "tcp_socket_option",
        option,
        requested,
        outcome,
        io_error_kind,
        os_error_code = error.raw_os_error(),
        "TCP socket option {} requested={} was not verified",
        option,
        requested
    );
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn bind_udp_low_port(socket: &UdpSocket, local_ip: IpAddr, max_port: u16) -> io::Result<u16> {
    ripdpi_privileged_ops::bind_udp_low_port(socket, local_ip, max_port)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn bind_udp_low_port(_socket: &UdpSocket, _local_ip: IpAddr, _max_port: u16) -> io::Result<u16> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    ripdpi_privileged_ops::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn wait_tcp_stage(_stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
