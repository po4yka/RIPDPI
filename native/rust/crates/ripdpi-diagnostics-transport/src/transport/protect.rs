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
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

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

const CONNECT_STOP_POLL_INTERVAL: Duration = Duration::from_millis(25);

/// Like [`protected_tcp_connect`], but give up once `stop` is set, so an attempt that lost a
/// connect race does not complete a connection that nobody reads (RFC 8305 section 5).
pub(crate) fn protected_tcp_connect_until(
    target: SocketAddr,
    timeout: Duration,
    stop: &AtomicBool,
) -> io::Result<TcpStream> {
    // Acquire pairs with the race owner's Release store when it has a result.
    let stopped = || stop.load(Ordering::Acquire);
    let stop_error = || io::Error::new(io::ErrorKind::Interrupted, "connect attempt stopped");
    if stopped() {
        return Err(stop_error());
    }
    let socket = Socket::new(domain_for(target), Type::STREAM, Some(Protocol::TCP))?;
    protect_for_target(&socket, target)?;
    socket.set_nonblocking(true)?;
    match socket.connect(&SockAddr::from(target)) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::WouldBlock || error.raw_os_error() == Some(libc::EINPROGRESS) => {
            let deadline = Instant::now() + timeout;
            loop {
                if stopped() {
                    return Err(stop_error());
                }
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err(io::Error::from(io::ErrorKind::TimedOut));
                }
                if wait_until_writable(&socket, remaining.min(CONNECT_STOP_POLL_INTERVAL))? {
                    break;
                }
            }
            if let Some(error) = socket.take_error()? {
                return Err(error);
            }
            // POLLHUP without SO_ERROR: the peer is gone although no error was recorded.
            socket.peer_addr()?;
        }
        Err(error) => return Err(error),
    }
    socket.set_nonblocking(false)?;
    Ok(socket.into())
}

/// Wait at most `wait` for a connecting socket to become writable (connected or failed).
fn wait_until_writable(socket: &Socket, wait: Duration) -> io::Result<bool> {
    let mut poll_fd = libc::pollfd { fd: socket.as_raw_fd(), events: libc::POLLOUT, revents: 0 };
    let millis = libc::c_int::try_from(wait.as_millis()).unwrap_or(libc::c_int::MAX).max(1);
    // SAFETY: `poll_fd` is one initialized pollfd for an open socket that outlives the call.
    match unsafe { libc::poll(&mut poll_fd, 1, millis) } {
        0 => Ok(false),
        -1 => {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted { Ok(false) } else { Err(error) }
        }
        _ => Ok(true),
    }
}

/// Bind a UDP socket at `bind_addr` for an outbound probe to `server`,
/// protecting it if the VPN is active so the probe bypasses the tunnel.
pub fn protected_udp_bind(bind_addr: SocketAddr, server: SocketAddr) -> io::Result<UdpSocket> {
    let socket = Socket::new(domain_for(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    protect_for_target(&socket, server).map_err(|error| io::Error::new(io::ErrorKind::PermissionDenied, error))?;
    socket.bind(&SockAddr::from(bind_addr))?;
    Ok(socket.into())
}

fn domain_for(addr: SocketAddr) -> Domain {
    if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 }
}

#[cfg(test)]
mod tests {
    use std::io::Read;
    use std::net::{Ipv4Addr, UdpSocket};
    use std::os::fd::RawFd;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::*;

    #[test]
    fn connect_until_opens_no_connection_once_stopped() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        listener.set_nonblocking(true).unwrap();
        let stop = AtomicBool::new(true);

        let error = protected_tcp_connect_until(listener.local_addr().unwrap(), Duration::from_secs(1), &stop)
            .expect_err("a stopped attempt does not connect");

        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        assert_eq!(listener.accept().map(|_| ()).unwrap_err().kind(), io::ErrorKind::WouldBlock);
    }

    #[test]
    fn connect_until_stops_a_pending_connect_once_stopped() {
        // The protect slot is global: hold the lock so this non-loopback connect cannot reach
        // another test's recording callback.
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        // TEST-NET-1 (RFC 5737) is never routed, so the SYN gets no answer and the connect stays
        // pending. A loopback listener with a full backlog does not work: macOS resets it.
        let target = SocketAddr::from((Ipv4Addr::new(192, 0, 2, 1), 9));
        let stop = Arc::new(AtomicBool::new(false));
        let stopper = {
            let stop = Arc::clone(&stop);
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(100));
                stop.store(true, Ordering::Release);
            })
        };

        let started = Instant::now();
        let error = protected_tcp_connect_until(target, Duration::from_secs(10), &stop)
            .expect_err("a stopped pending attempt does not connect");
        let elapsed = started.elapsed();
        stopper.join().unwrap();

        if matches!(error.kind(), io::ErrorKind::NetworkUnreachable | io::ErrorKind::HostUnreachable) {
            // No route on this host: the connect cannot stay pending, so there is nothing to stop.
            eprintln!("skipped: {error}");
            return;
        }
        assert_eq!(error.kind(), io::ErrorKind::Interrupted, "{error:?}");
        assert!(elapsed < Duration::from_secs(1), "the pending connect ran on for {elapsed:?}");
    }

    #[test]
    fn connect_until_returns_a_blocking_connected_stream() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let stop = AtomicBool::new(false);

        let stream = protected_tcp_connect_until(listener.local_addr().unwrap(), Duration::from_secs(1), &stop)
            .expect("connect to the local listener");
        let (_peer, peer_address) = listener.accept().unwrap();

        assert_eq!(stream.local_addr().unwrap(), peer_address);
        stream.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let error = (&stream).read(&mut [0u8; 1]).expect_err("no data yet");
        assert!(matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut), "{error:?}");
    }

    #[test]
    fn connect_until_reports_a_refused_connection() {
        let port = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap().port();
        let stop = AtomicBool::new(false);

        let error = protected_tcp_connect_until(loopback(port), Duration::from_secs(1), &stop)
            .expect_err("nothing listens on the port");

        assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    }

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
