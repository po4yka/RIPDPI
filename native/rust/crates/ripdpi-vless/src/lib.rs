// Crate-local hardening for issue #15 (`Box::from_raw` ownership transfer).
// `ripdpi-vless` is the sole crate in the workspace that uses
// `Box::into_raw` / `Box::from_raw` (the BoringSSL Reality client_hello
// hook) and the workspace's generic FFI handle wrapper (`ScopedHandle`)
// lives in this crate. Per docs/rust-soundness-policy.md § "`Box::into_raw`
// / `Box::from_raw` ownership transfer", every `unsafe` block touching
// the matched pair MUST document its preconditions inline. Re-enabling
// the workspace-deferred `undocumented_unsafe_blocks` lint locally
// turns that documentation requirement into a build-time error, while
// the rest of the workspace continues the gradual migration.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

pub mod addons;
pub mod config;
pub mod endpoint_resolver;
pub mod mux;
pub mod reality;
pub(crate) mod reality_hook;
pub(crate) mod reality_seal;
pub mod scoped_handle;
pub mod vision;
pub mod wire;

pub use mux::{MuxConfigError, VlessMuxConfig, VlessMuxProtocol};

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::VlessRealityConfig;
use crate::vision::VisionStream;
use tokio_boring::SslStream;

/// Trait alias for an async bidirectional stream that is `Send`.
pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// VLESS+Reality client.
///
/// Provides two connection methods:
/// - `connect()`: opens a fresh TCP connection to the server
/// - `connect_over()`: performs VLESS+Reality over an existing transport (for chain relay)
pub struct VlessRealityClient;

impl VlessRealityClient {
    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream`.
    pub async fn connect(config: &VlessRealityConfig, target: &str) -> io::Result<VisionStream<SslStream<TcpStream>>> {
        Self::connect_with_optional_bind(config, None, target).await
    }

    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream` while binding
    /// the underlying TCP socket to a specific local IP.
    pub async fn connect_with_bind(
        config: &VlessRealityConfig,
        bind_ip: IpAddr,
        target: &str,
    ) -> io::Result<VisionStream<SslStream<TcpStream>>> {
        Self::connect_with_optional_bind(config, Some(bind_ip), target).await
    }

    async fn connect_with_optional_bind(
        config: &VlessRealityConfig,
        bind_ip: Option<IpAddr>,
        target: &str,
    ) -> io::Result<VisionStream<SslStream<TcpStream>>> {
        tracing::debug!("VLESS+Reality: connecting");

        let tcp = connect_tcp(config, bind_ip).await?;
        let tls = reality::connect_reality_tls(tcp, config).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Perform `Reality TLS -> VLESS handshake` over an existing transport.
    ///
    /// Used for chain relay: the `transport` is the output of a previous
    /// `VlessRealityClient::connect()` call (first hop), and we layer a second
    /// VLESS+Reality connection on top of it to reach the final destination.
    pub async fn connect_over<S>(
        config: &VlessRealityConfig,
        transport: S,
        target: &str,
    ) -> io::Result<impl AsyncIo + use<S>>
    where
        S: AsyncIo + 'static,
    {
        tracing::debug!("VLESS+Reality (chained): connecting over existing transport");

        let tls = reality::connect_reality_tls_over(transport, config).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Send the VLESS request, read the response, and wrap in a VisionStream.
    async fn vless_handshake_and_wrap<S>(
        mut tls: S,
        config: &VlessRealityConfig,
        target: &str,
    ) -> io::Result<VisionStream<S>>
    where
        S: AsyncIo + 'static,
    {
        // Write VLESS request. The addons block is driven by the
        // profile's `flow` field so the engine can honor xray servers
        // that advertise `flow: ""` or `xtls-rprx-vision-udp443`. See
        // [`crate::addons::VlessFlow`] and audit finding C3.
        let request = wire::encode_request(&config.uuid, config.flow.as_addons_bytes(), target);
        tls.write_all(&request).await?;

        // Read VLESS response header
        wire::read_response(&mut tls).await?;

        tracing::debug!("VLESS handshake completed");

        // Wrap for the selected flow: real XTLS Vision framing for
        // `xtls-rprx-vision[-udp443]`, or a transparent passthrough for
        // `flow=none`. The Vision wrapper pads the inner-TLS handshake and
        // splices to raw afterwards, mirroring the wire format the server's
        // `xtls-rprx-vision` reader expects (see [`crate::vision`]).
        let stream = match config.flow {
            crate::addons::VlessFlow::None => VisionStream::new_passthrough(tls),
            crate::addons::VlessFlow::Vision | crate::addons::VlessFlow::VisionUdp443 => {
                VisionStream::new_vision(tls, config.uuid)
            }
        };
        Ok(stream)
    }
}

async fn connect_tcp(config: &VlessRealityConfig, bind_ip: Option<IpAddr>) -> io::Result<TcpStream> {
    let socket_protection = config.socket_protection;
    let address = endpoint_resolver::resolve_server_addr(
        &config.server,
        config.port,
        bind_ip,
        Some(Box::new(move |fd| socket_protection.protect(fd))),
    )
    .await?;
    let socket = match address {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    // VpnService.protect() invariant: the VLESS server is non-loopback, so the
    // outbound socket must be kept out of the TUN route BEFORE it binds or
    // connects — otherwise its traffic loops back into the tunnel the VPN owns
    // (exponential packet growth). Loopback targets never leave the device and
    // are exempt. `connect_over()` carries no OS socket of its own (it layers
    // over an already-protected transport), so `connect_tcp` is the only VLESS
    // socket-creation site that needs this. See
    // .claude/rules/vpnservice-protect-invariant.md.
    if !address.ip().is_loopback() {
        protect_outbound_socket(&socket, config.socket_protection)?;
    }
    if let Some(bind_ip) = bind_ip {
        let bind_addr = match (bind_ip, address) {
            (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
            (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "outbound bind IP family does not match relay server address family",
                ));
            }
        };
        socket.bind(bind_addr)?;
    }
    let stream = socket
        .connect(address)
        .await
        .map_err(|e| io::Error::new(e.kind(), format!("VLESS TCP connect to {address}: {e}")))?;
    stream.set_nodelay(true)?;
    Ok(stream)
}

/// Protect a freshly created outbound socket via the registered
/// `VpnService.protect()` callback before it connects to a non-loopback peer.
///
/// The explicit inactive policy is a no-op for proxy/host runtimes. The
/// VPN-required policy fails closed when the callback is missing or rejects
/// the fd, before bind/connect can touch the network. See
/// `ripdpi_native_protect` and
/// .claude/rules/vpnservice-protect-invariant.md.
fn protect_outbound_socket<T: AsRawFd>(
    socket: &T,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect(socket.as_raw_fd())
        .map_err(|error| io::Error::new(error.kind(), format!("protect VLESS outbound socket: {error}")))
}

#[cfg(test)]
mod protect_tests {
    //! `connect_tcp` must protect the outbound socket before it touches the
    //! wire (vpnservice-protect-invariant.md). The protect callback registry
    //! is process-global, so every test serializes on `PROTECT_TEST_LOCK` and
    //! clears the slot before releasing it.

    use super::*;
    use base64::prelude::*;
    use std::net::TcpListener;
    use std::os::fd::RawFd;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};

    use ripdpi_native_protect::{
        ProtectCallback, has_protect_callback, register_protect_callback, unregister_protect_callback,
    };

    // Held across `.await`, so it must be an async-aware mutex (clippy
    // `await_holding_lock` would fire on a std Mutex guard).
    static PROTECT_TEST_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
        calls: AtomicUsize,
        fail_with: Option<io::ErrorKind>,
    }

    impl RecordingCallback {
        fn new(fail_with: Option<io::ErrorKind>) -> Arc<Self> {
            Arc::new(Self { last_fd: AtomicI32::new(-1), calls: AtomicUsize::new(0), fail_with })
        }
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::SeqCst);
            self.calls.fetch_add(1, Ordering::SeqCst);
            match self.fail_with {
                Some(kind) => Err(io::Error::new(kind, "test protect failure")),
                None => Ok(()),
            }
        }
    }

    fn config_for(server: &str, port: u16) -> VlessRealityConfig {
        let key = BASE64_STANDARD.encode([0xABu8; 32]);
        let mut config = VlessRealityConfig::from_strings(
            server,
            i32::from(port),
            "550e8400-e29b-41d4-a716-446655440000",
            "www.example.com",
            &key,
            "abcd1234",
            "chrome_stable",
        )
        .expect("valid base config");
        config.socket_protection = ripdpi_native_protect::SocketProtectionPolicy::VpnRequired;
        config
    }

    // TEST-NET-1 (RFC 5737) is a guaranteed-unroutable literal: it parses
    // without DNS and the protect failure aborts before any connect attempt,
    // so the test never actually dials it.
    const NON_LOOPBACK: &str = "192.0.2.1";

    #[tokio::test]
    async fn proxy_mode_allows_nonloopback_socket_without_callback() {
        let _guard = PROTECT_TEST_LOCK.lock().await;
        unregister_protect_callback();
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test socket");

        protect_outbound_socket(&listener, ripdpi_native_protect::SocketProtectionPolicy::Inactive)
            .expect("proxy mode must not require VpnService protection");
    }

    #[tokio::test]
    async fn nonloopback_socket_is_protected_before_connect() {
        let _guard = PROTECT_TEST_LOCK.lock().await;
        let cb = RecordingCallback::new(Some(io::ErrorKind::PermissionDenied));
        register_protect_callback(cb.clone());

        let cfg = config_for(NON_LOOPBACK, 9);
        let err = connect_tcp(&cfg, None).await.expect_err("protect failure must abort the connect");

        // The callback fired (recorded a real fd) and its error kind is
        // propagated — proving protect runs before the wire is touched.
        assert_eq!(cb.calls.load(Ordering::SeqCst), 1, "protect callback must be invoked exactly once");
        assert!(cb.last_fd.load(Ordering::SeqCst) >= 0, "callback must receive the socket fd");
        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied, "protect error kind must propagate");

        unregister_protect_callback();
    }

    #[tokio::test]
    async fn nonloopback_without_callback_fails_closed() {
        let _guard = PROTECT_TEST_LOCK.lock().await;
        unregister_protect_callback();
        assert!(!has_protect_callback(), "test precondition: no protect callback registered");

        let cfg = config_for(NON_LOOPBACK, 9);
        let err = connect_tcp(&cfg, None).await.expect_err("non-loopback dial must fail closed without a callback");

        assert_eq!(err.kind(), io::ErrorKind::NotConnected, "missing protect mechanism must fail closed");
    }

    #[tokio::test]
    async fn loopback_socket_skips_protect_and_connects() {
        let _guard = PROTECT_TEST_LOCK.lock().await;
        // A recording callback that would fail if ever called — loopback must
        // never reach it.
        let cb = RecordingCallback::new(Some(io::ErrorKind::PermissionDenied));
        register_protect_callback(cb.clone());

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
        let port = listener.local_addr().expect("listener addr").port();

        let cfg = config_for("127.0.0.1", port);
        let stream = connect_tcp(&cfg, None).await.expect("loopback connect must succeed without protect");
        drop(stream);

        assert_eq!(cb.calls.load(Ordering::SeqCst), 0, "loopback target must be exempt from protect");

        unregister_protect_callback();
        drop(listener);
    }
}
