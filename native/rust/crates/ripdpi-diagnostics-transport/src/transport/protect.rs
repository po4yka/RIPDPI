//! `VpnService.protect` integration for the `Direct` probe transport.
//!
//! A `Direct` connectivity probe is meant to measure the device's *underlying*
//! network path. When the app's VPN is up, an unprotected probe socket is
//! captured by the TUN route: it no longer measures the direct path, and — per
//! the `VpnService.protect()` invariant — the app's own sockets must not be
//! routed back into the tunnel they own. When a protect callback is registered
//! (the VPN is up), protect the probe fd so it bypasses the tunnel.
//!
//! Fail-open by design only when there is nothing to protect against: the
//! helpers are a no-op when no callback is registered (desktop, tests, or VPN
//! down) and for loopback targets. When a callback *is* registered and protect
//! fails, the error propagates so the caller abandons the unprotected probe
//! (fail-closed). The `Socks5` transport is intentionally NOT routed through
//! here — it measures the through-proxy path and must traverse the tunnel.

use std::io;
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

/// Protect `socket` via the in-process `VpnService.protect` callback registry
/// when one is registered and `target` is non-loopback.
///
/// No-op when no callback is registered or `target` is loopback. Returns the
/// callback's error (fail-closed) when protection is required but fails.
pub(crate) fn protect_for_target<S: AsRawFd>(socket: &S, target: SocketAddr) -> io::Result<()> {
    if target.ip().is_loopback() || !ripdpi_native_protect::has_protect_callback() {
        return Ok(());
    }
    ripdpi_native_protect::protect_socket_via_callback(socket.as_raw_fd())
}

/// Create a TCP socket, protect it if the VPN is active, then connect to
/// `target` with `timeout`. Replaces a bare `TcpStream::connect_timeout` so the
/// protect step can run on the fd before `connect`.
pub(crate) fn protected_tcp_connect(target: SocketAddr, timeout: Duration) -> io::Result<TcpStream> {
    let socket = Socket::new(domain_for(target), Type::STREAM, Some(Protocol::TCP))?;
    protect_for_target(&socket, target)?;
    socket.connect_timeout(&SockAddr::from(target), timeout)?;
    Ok(socket.into())
}

/// Bind a UDP socket at `bind_addr` for an outbound probe to `server`,
/// protecting it if the VPN is active so the probe bypasses the tunnel.
pub(crate) fn protected_udp_bind(bind_addr: SocketAddr, server: SocketAddr) -> io::Result<UdpSocket> {
    let socket = Socket::new(domain_for(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    protect_for_target(&socket, server)?;
    socket.bind(&SockAddr::from(bind_addr))?;
    Ok(socket.into())
}

fn domain_for(addr: SocketAddr) -> Domain {
    if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 }
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, UdpSocket};
    use std::os::fd::RawFd;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::*;

    // The protect callback slot is process-global; serialize the tests that
    // register/clear it so a parallel test in the same binary cannot observe a
    // half-installed callback.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    fn loopback(port: u16) -> SocketAddr {
        SocketAddr::from((Ipv4Addr::LOCALHOST, port))
    }

    fn non_loopback(port: u16) -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), port))
    }

    #[test]
    fn protects_non_loopback_when_callback_registered() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let sock = UdpSocket::bind(loopback(0)).expect("bind probe fd");
        protect_for_target(&sock, non_loopback(443)).expect("protect must succeed");

        assert_eq!(cb.last_fd.load(Ordering::Acquire), sock.as_raw_fd(), "non-loopback target must be protected");
        unregister_protect_callback();
    }

    #[test]
    fn loopback_target_is_not_protected() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let sock = UdpSocket::bind(loopback(0)).expect("bind probe fd");
        protect_for_target(&sock, loopback(443)).expect("loopback protect is a no-op");

        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1, "loopback target must not be protected");
        unregister_protect_callback();
    }

    #[test]
    fn no_callback_is_a_noop() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let sock = UdpSocket::bind(loopback(0)).expect("bind probe fd");
        // No VPN callback registered (desktop / VPN down): protecting a
        // non-loopback target is a no-op and must not error.
        protect_for_target(&sock, non_loopback(443)).expect("no-callback path must be a no-op");
    }
}
