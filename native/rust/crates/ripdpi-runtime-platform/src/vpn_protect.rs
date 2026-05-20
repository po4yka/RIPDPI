//! Runtime-adaptation — `VpnService.protect` dispatch for outbound sockets.
//!
//! `protect_socket` chooses between two paths at call time: if a
//! `VpnService.protect` callback is registered (via the `protect` /
//! `ripdpi-native-protect` registry) it routes the fd through that callback;
//! otherwise it falls back to `ripdpi-privileged-ops` on Linux/Android, or a
//! no-op on other targets. This is the load-bearing protect invariant — see
//! `.claude/rules/vpnservice-protect-invariant.md`. Surfaced through the
//! `vpn` facade.

use std::io;
use std::os::fd::AsRawFd;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return crate::protect::protect_socket_via_callback(socket.as_raw_fd());
    }

    ripdpi_privileged_ops::protect_socket(socket, path)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: AsRawFd>(socket: &T, _path: Option<&str>) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return crate::protect::protect_socket_via_callback(socket.as_raw_fd());
    }

    Ok(())
}
