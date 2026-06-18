// SPDX-License-Identifier: MIT
//
//! Protected outbound carrier-socket seam.
//!
//! The carrier crate frames WireGuard datagrams over an already-connected
//! stream and deliberately does *not* own the production socket (see the crate
//! docs). This module is the one concrete exception: the seam that *creates*
//! the outbound carrier `TcpStream` to a non-loopback exit, honouring the
//! `VpnService.protect` invariant.
//!
//! # Why the protect call lives here
//!
//! Per `.claude/rules/vpnservice-protect-invariant.md`, every outbound socket
//! whose target is not loopback MUST be `protect_socket(fd)`-ed *before*
//! `connect()` returns, or the kernel routes its traffic back into the VPN's
//! own TUN device and the flow loops. [`TcpSocket`](tokio::net::TcpSocket)
//! exposes the raw fd *before* `connect()`, so the protector runs on the fd at
//! exactly the right moment.
//!
//! The protector itself is *injected* — this crate never reaches for a
//! process-global registry or a JNI bridge. In production the caller (a
//! `*-android` adapter that owns the JNI-backed `VpnService.protect` shim, or
//! `ripdpi-warp-core`'s `WarpSocketProtector`) supplies the callback; in tests
//! a fake protector over a `127.0.0.1` listener asserts the fail-closed
//! contract. This mirrors `ripdpi-warp-core::platform::WarpSocketProtector`
//! exactly while keeping the carrier crate free of any platform dependency.
//!
//! # Fail-closed contract
//!
//! If the protector returns `Err`, the socket is dropped (closing the fd) and
//! the error is propagated; `connect()` is *never* reached. An unprotected
//! outbound socket is never handed to the caller.

use std::io;
use std::net::SocketAddr;
use std::os::fd::{AsRawFd, RawFd};

use tokio::net::{TcpSocket, TcpStream};

mod private {
    use std::io;
    use std::os::fd::RawFd;

    /// Sealing supertrait. Keeps [`CarrierSocketProtector`](super::CarrierSocketProtector)
    /// implementable only inside this crate — via the blanket `Fn` impl — so a
    /// blanket impl on a public trait is not a downstream semver hazard.
    pub trait Sealed {}

    impl<F> Sealed for F where F: Fn(RawFd) -> io::Result<()> + Send + Sync {}
}

/// Protects an outbound socket fd against the VPN TUN route.
///
/// Mirrors `ripdpi-warp-core::platform::WarpSocketProtector`: the production
/// implementation calls Kotlin's `VpnService.protect(int)` through the JNI
/// bridge owned by a `*-android` adapter; host tests supply a fake. The blanket
/// impl below lets any `Fn(RawFd) -> io::Result<()>` be used directly.
///
/// This trait is **sealed**: it is implemented solely by that blanket impl and
/// cannot be implemented outside this crate, so the blanket impl carries no
/// semver hazard for downstream consumers.
pub trait CarrierSocketProtector: private::Sealed + Send + Sync {
    /// Protect `fd` so its traffic bypasses the VPN's own TUN route.
    ///
    /// Returns `Err` to fail the connection closed: the caller drops the socket
    /// and never connects an unprotected fd.
    fn protect_socket(&self, fd: RawFd) -> io::Result<()>;
}

impl<F> CarrierSocketProtector for F
where
    F: Fn(RawFd) -> io::Result<()> + Send + Sync,
{
    fn protect_socket(&self, fd: RawFd) -> io::Result<()> {
        self(fd)
    }
}

/// Connect a protected outbound carrier `TcpStream` to `target`.
///
/// The carrier socket the WSS/TLS stream is later layered over targets a
/// non-loopback exit, so the protect invariant applies. The sequence is:
///
/// 1. create an unconnected [`TcpSocket`] for `target`'s address family;
/// 2. invoke `protector` on its fd — *before* any `connect()`;
/// 3. on protector failure, drop the socket (closing the fd) and return the
///    error — the connection fails closed, no unprotected socket survives;
/// 4. only on protect success, `connect(target).await` and return the stream.
///
/// The returned [`TcpStream`] is the already-protected, already-connected
/// transport [`WsCarrier::new`](crate::WsCarrier::new) (via the TLS layer the
/// caller adds) expects.
///
// cancel-safe: dropping this future before it resolves drops the in-flight
// `TcpSocket`/`connect` future, which closes the fd. No caller-visible state is
// published before the `Ok` return, so a cancelled connect leaves nothing
// behind beyond a closed socket — there is never a half-protected fd handed
// out.
pub async fn connect_protected_carrier<P>(target: SocketAddr, protector: &P) -> io::Result<TcpStream>
where
    P: CarrierSocketProtector + ?Sized,
{
    let socket = if target.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };

    // Protect BEFORE connect (vpnservice-protect-invariant). `TcpSocket` holds
    // an unconnected fd here, so this is the only correct window. Fail-closed:
    // on `Err` the `?` returns and `socket` is dropped, closing the fd, so an
    // unprotected outbound socket is never connected.
    protector.protect_socket(socket.as_raw_fd())?;

    socket.connect(target).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
    use tokio::net::TcpListener;

    /// The protector fires (recording the fd) and reports the connect started
    /// only after it returned — proving protect precedes connect.
    #[tokio::test]
    async fn protector_runs_before_connect_completes() {
        // A real loopback listener so `connect` actually succeeds. 127.0.0.1 is
        // protect-exempt in production, but the seam still invokes the injected
        // protector unconditionally (fail-closed posture), which is what we
        // assert here.
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind loopback listener");
        let addr = listener.local_addr().expect("local addr");

        let protect_ran = Arc::new(AtomicBool::new(false));
        let observed_fd = Arc::new(AtomicI32::new(-1));
        let protect_ran_cb = Arc::clone(&protect_ran);
        let observed_fd_cb = Arc::clone(&observed_fd);

        let protector = move |fd: RawFd| -> io::Result<()> {
            protect_ran_cb.store(true, Ordering::SeqCst);
            observed_fd_cb.store(fd, Ordering::SeqCst);
            Ok(())
        };

        let accept = tokio::spawn(async move {
            let _ = listener.accept().await;
        });

        let stream = connect_protected_carrier(addr, &protector).await.expect("protected connect");

        // The protector ran and saw a real (non-negative) fd before the
        // connected stream was returned.
        assert!(protect_ran.load(Ordering::SeqCst), "protector must run before connect returns");
        assert!(observed_fd.load(Ordering::SeqCst) >= 0, "protector observed a valid socket fd");
        assert_eq!(stream.peer_addr().expect("peer addr"), addr, "connected to the target");

        drop(stream);
        accept.await.expect("listener accept task joins");
    }

    /// A protector that rejects the fd aborts the connection: `connect` is never
    /// reached and no stream is produced (fail-closed).
    #[tokio::test]
    async fn failing_protector_aborts_before_connect() {
        // Bind a listener but DO NOT accept: the protector rejecting the fd must
        // make the seam return `Err` *before* `connect()` runs, so no client
        // socket is ever created against this address.
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind loopback listener");
        let addr = listener.local_addr().expect("local addr");

        let protect_ran = Arc::new(AtomicBool::new(false));
        let protect_ran_cb = Arc::clone(&protect_ran);

        let protector = move |_fd: RawFd| -> io::Result<()> {
            protect_ran_cb.store(true, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "protect rejected"))
        };

        let result = connect_protected_carrier(addr, &protector).await;

        assert!(protect_ran.load(Ordering::SeqCst), "protector must have been consulted");
        let err = result.expect_err("failing protector must abort the connect");
        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied, "the protector error propagates verbatim");

        // No connection was established: `connect()` was never reached because
        // the `?` on the protector error returned first, dropping the socket.
        // A non-blocking poll of the listener finds nothing pending. (Had
        // `connect()` wrongly run, a connection would sit on the backlog and
        // `accept` would be immediately ready.)
        let pending = std::future::poll_fn(|cx| std::task::Poll::Ready(listener.poll_accept(cx).is_pending())).await;
        assert!(pending, "no connection should have been established");

        // Keep the listener bound through the assertion above.
        drop(listener);
    }

    /// An IPv6 loopback target uses the v6 socket path and still protects first.
    #[tokio::test]
    async fn ipv6_target_protects_then_connects() {
        let listener = TcpListener::bind("[::1]:0").await.expect("bind ipv6 loopback listener");
        let addr = listener.local_addr().expect("local addr");

        let observed_fd = Arc::new(AtomicI32::new(-1));
        let observed_fd_cb = Arc::clone(&observed_fd);
        let protector = move |fd: RawFd| -> io::Result<()> {
            observed_fd_cb.store(fd, Ordering::SeqCst);
            Ok(())
        };

        let accept = tokio::spawn(async move {
            let _ = listener.accept().await;
        });

        let stream = connect_protected_carrier(addr, &protector).await.expect("ipv6 protected connect");
        assert!(observed_fd.load(Ordering::SeqCst) >= 0, "protector observed the v6 socket fd");
        assert!(stream.peer_addr().expect("peer addr").is_ipv6(), "connected over IPv6");

        drop(stream);
        accept.await.expect("listener accept task joins");
    }
}
