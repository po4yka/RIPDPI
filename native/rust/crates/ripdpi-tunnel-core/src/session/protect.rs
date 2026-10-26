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
//! It is called on every relay socket with the address the socket will reach.
//! A loopback target (the default tunnel→local-proxy hop, `socks5.address =
//! 127.0.0.1`) never enters the TUN, so it skips the blocking protect round
//! trip, as the invariant allows. When no protect mechanism is installed (unit
//! tests, loopback-only CLI runs) it is a no-op. The case the invariant
//! requires is a non-loopback proxy address, a supported configuration whose
//! sockets would otherwise loop back into the TUN.

use std::io;
use std::net::IpAddr;
use std::os::fd::AsRawFd;

/// Protect `socket` via the registered `VpnService.protect` callback when one
/// is installed (the Android path), otherwise via the privileged-ops UDS socket
/// server when a `protect_path` is configured (the CLI path). Returns `Ok(())`
/// as a no-op for a loopback `target` or when neither mechanism is available.
///
/// On failure the caller MUST close the socket and fail the connection rather
/// than proceed with an unprotected socket — never swallow the error.
pub(crate) fn protect_socket_if_available<T: AsRawFd>(
    socket: &T,
    target: IpAddr,
    protect_path: Option<&str>,
) -> io::Result<()> {
    if target.is_loopback() {
        return Ok(());
    }
    if ripdpi_runtime_platform::protect::has_protect_callback() {
        return ripdpi_runtime_platform::protect::protect_socket_via_callback(socket.as_raw_fd()).map_err(|error| {
            io::Error::new(error.kind(), format!("protect tunnel relay socket via callback: {error}"))
        });
    }
    ripdpi_privileged_ops::protect_socket(socket, protect_path).map_err(|error| {
        io::Error::new(error.kind(), format!("protect tunnel relay socket via socket server: {error}"))
    })
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, UdpSocket};
    use std::os::fd::RawFd;
    use std::sync::{Arc, Mutex};

    use ripdpi_runtime_platform::protect::{
        ProtectCallback, ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if,
    };

    use super::protect_socket_if_available;

    struct Recorder(Mutex<Vec<RawFd>>);
    impl ProtectCallback for Recorder {
        fn protect(&self, fd: RawFd) -> std::io::Result<()> {
            self.0.lock().expect("recorder lock").push(fd);
            Ok(())
        }
    }
    struct Guard(ProtectGeneration);
    impl Drop for Guard {
        fn drop(&mut self) {
            // Superseded by another test's registration: nothing of ours to release.
            let _ = unregister_protect_callback_if(self.0);
        }
    }

    #[tokio::test]
    async fn only_non_loopback_targets_go_through_protect() {
        let _protect_lock = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;
        let recorder = Arc::new(Recorder(Mutex::new(Vec::new())));
        let _guard = Guard(register_protect_callback_versioned(Arc::clone(&recorder) as Arc<dyn ProtectCallback>));
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("socket");

        protect_socket_if_available(&socket, IpAddr::V4(Ipv4Addr::LOCALHOST), None).expect("loopback");
        protect_socket_if_available(&socket, IpAddr::V6(Ipv6Addr::LOCALHOST), None).expect("loopback v6");
        assert!(recorder.0.lock().expect("recorder lock").is_empty(), "loopback hops need no protect");

        protect_socket_if_available(&socket, IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), None).expect("protect");
        let fd = std::os::fd::AsRawFd::as_raw_fd(&socket);
        assert!(recorder.0.lock().expect("recorder lock").contains(&fd), "a non-loopback proxy must be protected");
    }
}
