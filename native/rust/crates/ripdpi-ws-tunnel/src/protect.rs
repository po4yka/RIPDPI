use std::io;

/// Protect a socket from being routed through the Android VPN tunnel.
///
/// On Android, VPN apps must "protect" outgoing sockets so traffic does not
/// loop back through the TUN interface. This sends the socket FD to the Java
/// VpnService via a Unix socket at `path`, which calls `VpnService.protect()`.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};
    use std::io::IoSlice;
    use std::io::Read;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;
    use std::time::Duration;

    let stream = UnixStream::connect(path)?;
    stream.set_read_timeout(Some(Duration::from_secs(1)))?;
    stream.set_write_timeout(Some(Duration::from_secs(1)))?;

    let payload = [b'1'];
    let iov = [IoSlice::new(&payload)];
    let fd = socket.as_raw_fd();
    let fds = [fd];
    let cmsg = [ControlMessage::ScmRights(&fds)];
    sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;

    let mut ack = [0u8; 1];
    (&stream).read_exact(&mut ack)?;
    // VpnProtectSocketServer writes 0 on success and 1 on denial; fail closed on
    // a non-zero ack so the caller never proceeds with an unprotected socket.
    if ack[0] != 0 {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect denied the socket"));
    }
    Ok(())
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "socket protection is only supported on Linux/Android"))
}

/// Protect a connecting socket via the in-process `VpnService.protect` callback
/// registry when there is no UDS protect path.
///
/// This is the second protection mechanism for the WS tunnel: callers prefer
/// the UDS path (`protect_socket`) when a `protect_path` is configured, and use
/// this when it is not — so the socket is still protected when only the JNI
/// callback is registered (the VPN is up). It is a no-op when no callback is
/// registered (desktop / VPN down) and for loopback targets, and fails closed
/// when protection is required but the callback errors.
///
/// # Legacy presence-probing seam
///
/// The probe and the protect are two separate registry reads, so a VPN session
/// starting between them leaves the socket unprotected while the TUN comes up
/// (routing-loop window), and one stopping turns protection into a spurious
/// failure. Relay transports avoid this by threading an explicit
/// `SocketProtectionPolicy` from runtime configuration. See the
/// "Presence-probing caveat" section of `ripdpi-native-protect`.
// TODO(author): migrate WS-tunnel callers to SocketProtectionPolicy threading
// and retire this probe, matching the relay transport pattern.
pub(crate) fn protect_via_callback_if_active<S: std::os::fd::AsRawFd>(
    socket: &S,
    target: std::net::SocketAddr,
) -> io::Result<()> {
    if target.ip().is_loopback() || !ripdpi_native_protect::has_protect_callback() {
        return Ok(());
    }
    ripdpi_native_protect::protect_socket_via_callback(socket.as_raw_fd())
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    // The protect-callback slot is process-global; serialize the tests that
    // touch it so a parallel test in the same binary cannot race the slot.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback(AtomicI32);

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> std::io::Result<()> {
            self.0.store(fd, Ordering::Release);
            Ok(())
        }
    }

    /// A registered callback protects a non-loopback WS-tunnel socket even when
    /// no UDS `protect_path` is configured, and never protects a loopback target.
    #[test]
    fn callback_protects_non_loopback_but_not_loopback() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback(AtomicI32::new(-1)));
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let sock = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("bind probe fd");

        super::protect_via_callback_if_active(&sock, SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443)))
            .expect("non-loopback protect must succeed");
        assert_eq!(cb.0.load(Ordering::Acquire), sock.as_raw_fd(), "non-loopback target must be protected");

        cb.0.store(-1, Ordering::Release);
        super::protect_via_callback_if_active(&sock, SocketAddr::from((Ipv4Addr::LOCALHOST, 443)))
            .expect("loopback protect is a no-op");
        assert_eq!(cb.0.load(Ordering::Acquire), -1, "loopback target must not be protected");

        unregister_protect_callback();
    }

    /// With no callback registered (desktop / VPN down), protecting a
    /// non-loopback target is a no-op and must not error.
    #[test]
    fn no_callback_is_a_noop() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let sock = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("bind probe fd");
        super::protect_via_callback_if_active(&sock, SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443)))
            .expect("no-callback path must be a no-op");
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    #[test]
    fn protect_socket_reports_unsupported_off_android() {
        let err =
            super::protect_socket(&(), "/tmp/ripdpi-protect.sock").expect_err("protect socket should be unsupported");

        assert_eq!(err.kind(), std::io::ErrorKind::Unsupported);
        assert_eq!(err.to_string(), "socket protection is only supported on Linux/Android");
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn protect_socket_fails_when_unix_path_does_not_exist() {
        let sock = UdpSocket::bind("127.0.0.1:0").expect("bind udp");
        let err = super::protect_socket(&sock, "/tmp/ripdpi-nonexistent-protect.sock")
            .expect_err("should fail on missing path");
        assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    }
}
