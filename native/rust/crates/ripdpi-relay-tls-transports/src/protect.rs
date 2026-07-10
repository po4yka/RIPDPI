//! Shared `VpnService.protect()` helper for the relay carrier sockets created
//! directly in this crate (shadowsocks, mieru).
//!
//! See `.claude/rules/vpnservice-protect-invariant.md`.

use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;

/// Protect a freshly created outbound carrier socket via the registered
/// `VpnService.protect()` callback BEFORE it binds or connects to a
/// non-loopback peer, so its traffic is not captured by the app's own TUN
/// route (which would loop it back into the tunnel the VPN owns).
///
/// No-op for loopback targets — they never leave the device, and host
/// integration tests dial loopback. Fails closed for a non-loopback target when
/// no callback is registered: under a live TUN there is no other per-socket
/// mechanism to keep the socket out of the tunnel, so refusing the dial is safer
/// than proceeding unprotected. (Own-UID TUN exclusion via `computeAppRoutingPlan`
/// remains the second layer.) Mirrors the `ripdpi-vless` / `ripdpi-xhttp`
/// gold-standard pattern.
pub(crate) fn protect_carrier_socket<T: AsRawFd>(
    socket: &T,
    target: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect_non_loopback(socket.as_raw_fd(), target)
        .map_err(|error| io::Error::new(error.kind(), format!("protect relay carrier socket: {error}")))
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, TcpListener};
    use std::os::fd::RawFd;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::*;

    // The protect callback slot is process-global; serialize the tests that
    // register/clear it.
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

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn loopback_target_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_carrier_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");

        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1, "loopback target must not be protected");
        unregister_protect_callback();
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");

        let err = protect_carrier_socket(
            &listener,
            non_loopback(),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect_err("must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn non_loopback_is_protected_via_callback() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_carrier_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");

        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd(), "non-loopback fd must be protected");
        unregister_protect_callback();
    }
}
