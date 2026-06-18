//! Production [`CarrierSocketProtector`] seam for the WG-over-WebSocket carrier.
//!
//! [`ripdpi-wireguard-ws`](ripdpi_wireguard_ws)'s [`connect_protected_carrier`]
//! takes an injected [`CarrierSocketProtector`] and runs it on the outbound
//! carrier socket's fd *before* `connect()` returns, failing closed if the
//! protector rejects the fd (see `.claude/rules/vpnservice-protect-invariant.md`
//! and the carrier crate's `connect` module docs). This module supplies the
//! *production* protector: the JNI-backed `VpnService.protect(int)` shim, reused
//! verbatim from the UDP tunnel's path so the carrier socket and the WireGuard
//! UDP socket share one registration.
//!
//! # Why this is the right protector, with zero new shim code
//!
//! The carrier crate seals [`CarrierSocketProtector`] behind a blanket
//! `impl<F: Fn(RawFd) -> io::Result<()> + Send + Sync>`. The process-global
//! protect entry point [`ripdpi_native_protect::protect_socket_via_callback`]
//! is exactly such an `fn(RawFd) -> io::Result<()>`, so it *is* a
//! `CarrierSocketProtector` already. [`carrier_socket_protector`] hands that
//! function to the carrier-select path; no second JNI bridge, `GlobalRef`, or
//! registry is introduced.
//!
//! The same `jniRegisterVpnProtect` / `jniUnregisterVpnProtect` calls that arm
//! the UDP socket's protector (see [`crate::vpn_protect`]) therefore arm this
//! carrier protector too — one `VpnService` `GlobalRef`, one generation token,
//! both the UDP socket and the WSS carrier socket protected by it.
//!
//! # Scope of this seam (B1 protect deliverable)
//!
//! This wires the protector *construction* only. It deliberately does **not**
//! open a live non-loopback carrier socket: the WG-over-WSS carrier-select
//! transport (the [`WgCarrier`](ripdpi_wireguard_ws) datagram seam inside
//! `ripdpi-warp-core`) is a separate additive follow-up slice. When that lands,
//! it calls `connect_protected_carrier(target, &carrier_socket_protector())` on
//! the carrier socket exactly as the UDP path calls `protect_socket` on its
//! `UdpSocket` fd, and increments
//! [`AmneziaWgRuntime::record_ws_carrier_handshake`](ripdpi_warp_core::AmneziaWgRuntime::record_ws_carrier_handshake)
//! on success.

use std::io;
use std::os::fd::RawFd;

use ripdpi_native_protect::protect_socket_via_callback;
use ripdpi_wireguard_ws::CarrierSocketProtector;

/// The production carrier-socket protector: a fail-closed `fn(RawFd)` that
/// routes through the process-global JNI `VpnService.protect` callback armed by
/// [`crate::vpn_protect::register_from_jni`].
///
/// The return type implements [`CarrierSocketProtector`] via the carrier
/// crate's sealed blanket `Fn` impl, so the carrier-select path passes it
/// straight to [`ripdpi_wireguard_ws::connect_protected_carrier`]:
///
/// ```ignore
/// let protector = carrier_socket_protector();
/// let stream = connect_protected_carrier(exit_addr, &protector).await?;
/// ```
///
/// Returning the concrete `fn` item (not a boxed `dyn`) keeps the seam
/// allocation-free and lets the compiler monomorphize the call site, matching
/// how `ripdpi-warp-core`'s `WarpPlatform::with_socket_protector` consumes the
/// very same function for the UDP socket.
// `dead_code`: this seam is wired but not yet *called* from a non-test path —
// the WG-over-WSS carrier-select transport that consumes it is a separate
// additive follow-up slice (see the module docs). The unit tests below exercise
// the full protect-before-connect + fail-closed contract through
// `connect_protected_carrier`. A localized allow (matching the existing
// `#[allow(dead_code)]` guard blocks in `vpn_protect`) is correct here; it is
// removed the moment the carrier-select path calls this.
#[allow(dead_code)]
#[must_use]
pub(crate) fn carrier_socket_protector() -> impl CarrierSocketProtector + Copy {
    // `fn(RawFd) -> io::Result<()>` satisfies the sealed blanket impl; no shim.
    protect_socket_via_callback as fn(RawFd) -> io::Result<()>
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};
    use ripdpi_wireguard_ws::connect_protected_carrier;
    use tokio::net::{TcpListener, TcpStream};

    use super::*;

    /// Serializes the process-global protect-registry mutations across the
    /// async tests in this module so one test's register/unregister cannot race
    /// another's.
    static REGISTRY_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    /// A fake `VpnService.protect` callback recording the fd it saw, standing in
    /// for the JNI shim with no JVM attached.
    struct RecordingProtect {
        ran: Arc<AtomicBool>,
        observed_fd: Arc<AtomicI32>,
        accept: bool,
    }

    impl ProtectCallback for RecordingProtect {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.ran.store(true, Ordering::SeqCst);
            self.observed_fd.store(fd, Ordering::SeqCst);
            if self.accept {
                Ok(())
            } else {
                Err(io::Error::new(io::ErrorKind::PermissionDenied, "fake protect rejected"))
            }
        }
    }

    /// With a callback registered, the production carrier protector runs it
    /// (protect-before-use) and the carrier connect then completes — proving the
    /// seam threads the JNI-backed protector through `connect_protected_carrier`
    /// exactly as the UDP path expects.
    #[tokio::test]
    async fn production_protector_runs_before_carrier_connect() {
        let _guard = REGISTRY_LOCK.lock().await;

        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind loopback listener");
        let addr = listener.local_addr().expect("local addr");

        let ran = Arc::new(AtomicBool::new(false));
        let observed_fd = Arc::new(AtomicI32::new(-1));
        register_protect_callback(Arc::new(RecordingProtect {
            ran: Arc::clone(&ran),
            observed_fd: Arc::clone(&observed_fd),
            accept: true,
        }));

        let accept = tokio::spawn(async move {
            let _ = listener.accept().await;
        });

        let protector = carrier_socket_protector();
        let stream: TcpStream = connect_protected_carrier(addr, &protector).await.expect("protected carrier connect");

        assert!(ran.load(Ordering::SeqCst), "the registered VpnService.protect callback ran");
        assert!(observed_fd.load(Ordering::SeqCst) >= 0, "the protector saw the real socket fd");
        assert_eq!(stream.peer_addr().expect("peer addr"), addr, "carrier connected to the target");

        drop(stream);
        accept.await.expect("listener accept task joins");
        unregister_protect_callback();
    }

    /// A rejecting `VpnService.protect` callback fails the carrier connect
    /// closed: `connect()` is never reached and no stream is produced. This is
    /// the protect invariant's load-bearing case.
    #[tokio::test]
    async fn rejected_protect_fails_carrier_connect_closed() {
        let _guard = REGISTRY_LOCK.lock().await;

        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind loopback listener");
        let addr = listener.local_addr().expect("local addr");

        let ran = Arc::new(AtomicBool::new(false));
        register_protect_callback(Arc::new(RecordingProtect {
            ran: Arc::clone(&ran),
            observed_fd: Arc::new(AtomicI32::new(-1)),
            accept: false,
        }));

        let protector = carrier_socket_protector();
        let result = connect_protected_carrier(addr, &protector).await;

        assert!(ran.load(Ordering::SeqCst), "the protector was consulted");
        let err = result.expect_err("a rejecting protector must fail the carrier connect closed");
        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied, "the protector error propagates verbatim");

        // No connection was established: the protect error returned before
        // `connect()` ran, so nothing sits on the listener's backlog.
        let pending = std::future::poll_fn(|cx| std::task::Poll::Ready(listener.poll_accept(cx).is_pending())).await;
        assert!(pending, "no carrier connection should have been established");

        drop(listener);
        unregister_protect_callback();
    }

    /// With no callback registered the production protector fails closed: the
    /// carrier connect returns `NotConnected` and never opens an unprotected
    /// socket. This guards the "armed before use" half of the invariant.
    #[tokio::test]
    async fn unregistered_protector_fails_carrier_connect_closed() {
        let _guard = REGISTRY_LOCK.lock().await;
        unregister_protect_callback();

        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind loopback listener");
        let addr = listener.local_addr().expect("local addr");

        let protector = carrier_socket_protector();
        let err = connect_protected_carrier(addr, &protector)
            .await
            .expect_err("an unregistered protector must fail the carrier connect closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected, "missing-callback error propagates");

        let pending = std::future::poll_fn(|cx| std::task::Poll::Ready(listener.poll_accept(cx).is_pending())).await;
        assert!(pending, "no carrier connection should have been established without a protector");

        drop(listener);
    }
}
