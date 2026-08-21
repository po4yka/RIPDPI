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
mod xudp;
mod yamux_session;

pub use mux::{MuxConfigError, VlessMuxConfig, VlessMuxProtocol};
pub use yamux_session::VlessYamuxSession;

use std::future::Future;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use socket2::{SockRef, TcpKeepalive};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::VlessRealityConfig;
use crate::reality::RealityTlsStream;
use crate::vision::VisionStream;
use crate::wire::ResponseHeaderStream;

type VlessRealityStream = VisionStream<ResponseHeaderStream<RealityTlsStream<TcpStream>>>;
pub type VlessXudpSession = xudp::VlessXudpSession;

const TCP_KEEPALIVE_IDLE: Duration = Duration::from_secs(30);
const TCP_KEEPALIVE_INTERVAL: Duration = Duration::from_secs(30);
const TCP_KEEPALIVE_RETRIES: u32 = 3;
#[cfg(any(target_os = "android", target_os = "fuchsia", target_os = "linux", target_os = "cygwin"))]
const TCP_USER_TIMEOUT: Duration = Duration::from_secs(90);

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
    /// Establish one VLESS+Reality carrier for the configured SagerNet
    /// sing-mux/yamux session. The carrier's VLESS destination is fixed by the
    /// upstream protocol; individual destinations are requested on yamux
    /// substreams afterwards.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: dropping this future discards its exclusively
    /// owned incomplete carrier; partial remote handshake/request bytes cannot
    /// be resumed or returned to the pool.
    pub async fn connect_mux(config: &VlessRealityConfig, bind_ip: Option<IpAddr>) -> io::Result<VlessYamuxSession> {
        let mux = config.mux.ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "VLESS mux session requested without mux config")
        })?;
        let tcp = trace_io_stage("tcp_connect", connect_tcp(config, bind_ip)).await?;
        let tls = trace_io_stage("reality_tls", reality::connect_reality_tls(tcp, config)).await?;
        let carrier = Self::vless_handshake_and_wrap(tls, config, mux::SING_MUX_DESTINATION).await?;
        VlessYamuxSession::establish(Box::new(carrier), mux.max_concurrent_streams).await
    }

    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream`.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: delegates to
    /// `connect_with_optional_bind`, which drops rather than resumes its
    /// exclusively owned incomplete carrier.
    pub async fn connect(config: &VlessRealityConfig, target: &str) -> io::Result<VlessRealityStream> {
        Self::connect_with_optional_bind(config, None, target).await
    }

    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream` while binding
    /// the underlying TCP socket to a specific local IP.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: delegates to
    /// `connect_with_optional_bind`, which drops rather than resumes its
    /// exclusively owned incomplete carrier.
    pub async fn connect_with_bind(
        config: &VlessRealityConfig,
        bind_ip: IpAddr,
        target: &str,
    ) -> io::Result<VlessRealityStream> {
        Self::connect_with_optional_bind(config, Some(bind_ip), target).await
    }

    /// # Cancel safety
    ///
    /// conditionally cancel-safe: dropping at the `connect_tcp`, Reality TLS,
    /// or VLESS-request `.await` may leave the peer with a partial
    /// handshake/request, but the in-progress carrier is exclusively owned and
    /// dropped and is never resumed or returned to a pool; the stage guard
    /// emits one `cancelled` record.
    async fn connect_with_optional_bind(
        config: &VlessRealityConfig,
        bind_ip: Option<IpAddr>,
        target: &str,
    ) -> io::Result<VlessRealityStream> {
        let tcp = trace_io_stage("tcp_connect", connect_tcp(config, bind_ip)).await?;
        let tls = trace_io_stage("reality_tls", reality::connect_reality_tls(tcp, config)).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Open one Xray-compatible XUDP association over a dedicated protected
    /// `TCP -> Reality -> VLESS Mux -> Vision` carrier.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: dropping at the TCP connect, Reality TLS, or
    /// VLESS request awaits may leave only a partial peer-side handshake; the
    /// exclusively owned local carrier is dropped and never resumed or pooled.
    pub async fn connect_xudp(config: &VlessRealityConfig, bind_ip: Option<IpAddr>) -> io::Result<VlessXudpSession> {
        if config.flow == crate::addons::VlessFlow::None {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "VLESS XUDP requires an XTLS Vision flow"));
        }
        tracing::debug!("VLESS+Reality: connecting XUDP carrier");
        let tcp = connect_tcp(config, bind_ip).await?;
        let tls = reality::connect_reality_tls(tcp, config).await?;
        let carrier = Self::vless_handshake_and_wrap_command(tls, config, wire::VlessCommand::Mux, None).await?;
        xudp::VlessXudpSession::new(carrier, config.flow == crate::addons::VlessFlow::VisionUdp443)
    }

    /// Perform `Reality TLS -> VLESS handshake` over an existing transport.
    ///
    /// Used for chain relay: the `transport` is the output of a previous
    /// `VlessRealityClient::connect()` call (first hop), and we layer a second
    /// VLESS+Reality connection on top of it to reach the final destination.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: cancellation may leave partial Reality/VLESS
    /// bytes on `transport`; the caller must discard that transport and must
    /// not resume or pool it.
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

    /// Send the VLESS request and wrap the stream for lazy response-header
    /// validation plus the selected Vision flow.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: delegates to
    /// `vless_handshake_and_wrap_command`; cancellation is safe only while the
    /// exclusively owned incomplete stream is discarded.
    async fn vless_handshake_and_wrap<S>(
        tls: S,
        config: &VlessRealityConfig,
        target: &str,
    ) -> io::Result<VisionStream<ResponseHeaderStream<S>>>
    where
        S: AsyncIo + 'static,
    {
        Self::vless_handshake_and_wrap_command(tls, config, wire::VlessCommand::Tcp, Some(target)).await
    }

    /// # Cancel safety
    ///
    /// conditionally cancel-safe: `write_all(&request).await` may write a prefix
    /// before cancellation; this is safe only because `tls` is exclusively
    /// owned, dropped with the future, and never resumed or pooled. The stage
    /// guard emits one `cancelled` terminal transition.
    async fn vless_handshake_and_wrap_command<S>(
        mut tls: S,
        config: &VlessRealityConfig,
        command: wire::VlessCommand,
        target: Option<&str>,
    ) -> io::Result<VisionStream<ResponseHeaderStream<S>>>
    where
        S: AsyncIo + 'static,
    {
        // Write VLESS request. The addons block is driven by the
        // profile's `flow` field so the engine can honor xray servers
        // that advertise `flow: ""` or `xtls-rprx-vision-udp443`. See
        // [`crate::addons::VlessFlow`] and audit finding C3.
        let request = wire::encode_command_request(&config.uuid, config.flow.as_addons_bytes(), command, target)?;
        trace_io_stage("vless_request", tls.write_all(&request)).await?;

        // xray-core buffers its response header until the first outbound
        // payload is available. Strip it lazily on read so the caller can
        // first send the request that makes the server flush that header.
        let response = ResponseHeaderStream::new_traced(tls);

        // Wrap for the selected flow: real XTLS Vision framing for
        // `xtls-rprx-vision[-udp443]`, or a transparent passthrough for
        // `flow=none`. The Vision wrapper pads the inner-TLS handshake and
        // splices to raw afterwards, mirroring the wire format the server's
        // `xtls-rprx-vision` reader expects (see [`crate::vision`]).
        let stream = match config.flow {
            crate::addons::VlessFlow::None => VisionStream::new_passthrough(response),
            crate::addons::VlessFlow::Vision | crate::addons::VlessFlow::VisionUdp443 => {
                if command == wire::VlessCommand::Mux {
                    VisionStream::new_vision_tls_only(response, config.uuid)
                } else {
                    VisionStream::new_vision(response, config.uuid)
                }
            }
        };
        Ok(stream)
    }
}

/// # Cancel safety
///
/// conditionally cancel-safe: this wrapper inherits `future`'s cancel safety;
/// dropping at `future.await` emits exactly one `cancelled` transition but
/// does not make a non-cancel-safe inner future safe.
async fn trace_io_stage<T>(stage: &'static str, future: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    let mut guard = RelayStageGuard::new(stage);
    match future.await {
        Ok(value) => {
            guard.finish("succeeded", None, None);
            Ok(value)
        }
        Err(error) => {
            guard.finish("failed", Some(&error), None);
            Err(error)
        }
    }
}

pub(crate) struct RelayStageGuard {
    stage: &'static str,
    started: Instant,
    finished: bool,
}

impl RelayStageGuard {
    pub(crate) fn new(stage: &'static str) -> Self {
        let started = Instant::now();
        emit_stage(stage, "started", started, None, None);
        Self { stage, started, finished: false }
    }

    pub(crate) fn finish(
        &mut self,
        outcome: &'static str,
        error: Option<&io::Error>,
        peer_close_phase: Option<&'static str>,
    ) {
        if self.finished {
            return;
        }
        emit_stage(self.stage, outcome, self.started, error, peer_close_phase);
        self.finished = true;
    }
}

impl Drop for RelayStageGuard {
    fn drop(&mut self) {
        if !self.finished {
            emit_stage(self.stage, "cancelled", self.started, None, None);
        }
    }
}

pub(crate) fn emit_stage(
    stage: &'static str,
    outcome: &'static str,
    started: Instant,
    error: Option<&io::Error>,
    peer_close_phase: Option<&'static str>,
) {
    let duration_ms = u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX);
    let io_error_kind = error.map(|value| io_error_kind_name(value.kind()));
    let os_error_code = error.and_then(io::Error::raw_os_error);
    tracing::info!(
        kind = "relay_attempt_stage",
        stage,
        outcome,
        duration_ms,
        failure_stage = error.map(|_| stage),
        failure_class = error.map(failure_class),
        io_error_kind,
        os_error_code,
        peer_close_phase,
        "relay attempt stage transition"
    );
}

fn failure_class(error: &io::Error) -> &'static str {
    match error.kind() {
        io::ErrorKind::ConnectionReset => "connection_reset",
        io::ErrorKind::ConnectionRefused => "connection_refused",
        io::ErrorKind::TimedOut => "timeout",
        io::ErrorKind::UnexpectedEof => "peer_closed",
        _ => "io_error",
    }
}

fn io_error_kind_name(kind: io::ErrorKind) -> &'static str {
    match kind {
        io::ErrorKind::NotFound => "not_found",
        io::ErrorKind::PermissionDenied => "permission_denied",
        io::ErrorKind::ConnectionRefused => "connection_refused",
        io::ErrorKind::ConnectionReset => "connection_reset",
        io::ErrorKind::HostUnreachable => "host_unreachable",
        io::ErrorKind::NetworkUnreachable => "network_unreachable",
        io::ErrorKind::ConnectionAborted => "connection_aborted",
        io::ErrorKind::NotConnected => "not_connected",
        io::ErrorKind::AddrInUse => "address_in_use",
        io::ErrorKind::AddrNotAvailable => "address_not_available",
        io::ErrorKind::BrokenPipe => "broken_pipe",
        io::ErrorKind::AlreadyExists => "already_exists",
        io::ErrorKind::WouldBlock => "would_block",
        io::ErrorKind::InvalidInput => "invalid_input",
        io::ErrorKind::InvalidData => "invalid_data",
        io::ErrorKind::TimedOut => "timed_out",
        io::ErrorKind::WriteZero => "write_zero",
        io::ErrorKind::Interrupted => "interrupted",
        io::ErrorKind::Unsupported => "unsupported",
        io::ErrorKind::UnexpectedEof => "unexpected_eof",
        io::ErrorKind::OutOfMemory => "out_of_memory",
        _ => "other",
    }
}

/// # Cancel safety
///
/// conditionally cancel-safe: resolver and socket-connect progress is
/// abandoned by dropping the exclusively owned socket; retry starts with a
/// fresh protected socket and never resumes the partial connect.
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
    let stream = socket.connect(address).await?;
    stream.set_nodelay(true)?;
    // Half-open detection for every carrier, not only XUDP.
    configure_tcp_liveness(&stream)?;
    Ok(stream)
}

fn configure_tcp_liveness(stream: &TcpStream) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let keepalive = TcpKeepalive::new()
        .with_time(TCP_KEEPALIVE_IDLE)
        .with_interval(TCP_KEEPALIVE_INTERVAL)
        .with_retries(TCP_KEEPALIVE_RETRIES);
    socket.set_tcp_keepalive(&keepalive)?;
    #[cfg(any(target_os = "android", target_os = "fuchsia", target_os = "linux", target_os = "cygwin"))]
    socket.set_tcp_user_timeout(Some(TCP_USER_TIMEOUT))?;
    Ok(())
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
    /// # Cancel safety
    ///
    /// NOT cancel-safe: cancellation can leave the process-global protect
    /// callback registered. The test is never externally raced or timed out.
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
        assert!(SockRef::from(&stream).keepalive().expect("read SO_KEEPALIVE"), "connect_tcp must apply TCP liveness");
        drop(stream);

        assert_eq!(cb.calls.load(Ordering::SeqCst), 0, "loopback target must be exempt from protect");

        unregister_protect_callback();
        drop(listener);
    }
}
