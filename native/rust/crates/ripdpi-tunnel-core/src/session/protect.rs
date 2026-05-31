//! Shared `VpnService.protect()` seam for outbound sockets the tunnel's SOCKS5
//! sessions create directly.
//!
//! The DNS-resolver path already protects its sockets via
//! `io_loop::dns_intercept::protect_hooks`; this is the same logic factored out
//! for the UDP-ASSOCIATE relay path (`session::udp::UdpSession`). See
//! `.claude/rules/vpnservice-protect-invariant.md`: every non-loopback outbound
//! socket MUST be protected *before* it issues `connect`/`bind`, otherwise the
//! kernel routes its traffic back into the TUN the VPN owns (a packet loop).
//!
//! It is called unconditionally on the relay sockets. When no protect mechanism
//! is installed (unit tests, loopback-only CLI runs) it is a no-op; when a JNI
//! protect callback *is* registered it protects every fd, including the
//! loopback-by-default tunnel→local-proxy hop (`socks5.address = 127.0.0.1`) —
//! an extra `VpnService.protect` call the invariant does not require but which
//! is harmless. The case the invariant *does* require is a non-loopback proxy
//! address, a supported configuration whose sockets would otherwise loop back
//! into the TUN.

use std::io;
use std::os::fd::AsRawFd;

/// Protect `socket` via the registered `VpnService.protect` callback when one
/// is installed (the Android path), otherwise via the privileged-ops UDS socket
/// server when a `protect_path` is configured (the CLI path). Returns `Ok(())`
/// as a no-op when neither mechanism is available.
///
/// On failure the caller MUST close the socket and fail the connection rather
/// than proceed with an unprotected socket — never swallow the error.
pub(crate) fn protect_socket_if_available<T: AsRawFd>(socket: &T, protect_path: Option<&str>) -> io::Result<()> {
    if ripdpi_runtime_platform::protect::has_protect_callback() {
        return ripdpi_runtime_platform::protect::protect_socket_via_callback(socket.as_raw_fd()).map_err(|error| {
            io::Error::new(error.kind(), format!("protect tunnel relay socket via callback: {error}"))
        });
    }
    ripdpi_privileged_ops::protect_socket(socket, protect_path).map_err(|error| {
        io::Error::new(error.kind(), format!("protect tunnel relay socket via socket server: {error}"))
    })
}
