use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;

#[cfg(test)]
use boring::ssl::SslVerifyMode;
use bytes::Bytes;
use hyper::ext::Protocol;
use hyper_util::rt::TokioIo;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpSocket;
use tokio::sync::mpsc;

use crate::auth::AuthHeader;
use crate::capsule::{
    CapsuleError, CapsuleErrorKind, decode_connect_udp_payload, decode_datagram_capsules, encode_connect_udp_payload,
    encode_datagram_capsule,
};
use crate::client::AsyncIo;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{AttemptError, validate_connect_udp_response, validate_proxy_response};
use crate::tls::{apply_h2_client_auth, apply_h2_root_certificate};
use crate::udp::{MasqueUdpFlow, MasqueUdpSender};
use crate::url::{ProxyOrigin, TargetAuthority, build_connect_udp_path, parse_proxy_origin, resolve_proxy_socket_addr};

pub(crate) fn encode_h2_datagram_capsule(payload: &[u8]) -> Result<Vec<u8>, CapsuleError> {
    encode_datagram_capsule(payload)
}

pub(crate) fn decode_h2_datagram_capsules(input: &[u8]) -> Result<Vec<Vec<u8>>, CapsuleError> {
    decode_datagram_capsules(input)
}

pub(crate) fn build_h2_connect_udp_request(
    proxy_origin: &ProxyOrigin,
    target: &TargetAuthority,
    auth_header: Option<&AuthHeader>,
) -> io::Result<hyper::Request<http_body_util::Empty<Bytes>>> {
    let request_uri = format!("https://{}{}", proxy_origin.authority, build_connect_udp_path(proxy_origin, target));
    let mut request = hyper::Request::builder().method("CONNECT").uri(request_uri).header("capsule-protocol", "?1");
    if let Some(header) = auth_header {
        request = request.header(header.name, header.value.as_str());
    }
    let mut request = request.body(http_body_util::Empty::<Bytes>::new()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H2 CONNECT-UDP request: {error}"))
    })?;
    request.extensions_mut().insert(Protocol::from_static("connect-udp"));
    Ok(request)
}

fn build_h2_connect_tcp_request(
    target: &str,
    config: &MasqueConfig,
    auth_header: Option<&AuthHeader>,
) -> io::Result<hyper::Request<http_body_util::Empty<Bytes>>> {
    apply_request_headers(hyper::Request::builder().method("CONNECT").uri(target), config, auth_header)?
        .body(http_body_util::Empty::<Bytes>::new())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H2 CONNECT request: {error}")))
}

/// Open a protected TCP carrier to the MASQUE proxy for HTTP/2.
///
/// Builds the socket explicitly and protects its fd via the in-process
/// `VpnService.protect()` registry BEFORE connect, so the non-loopback carrier
/// socket bypasses the app's own TUN route (an unprotected outbound socket
/// loops back into the tunnel the VPN owns — exponential packet growth).
/// Loopback-skip and fail-closed are handled by [`protect_outbound_socket`].
/// The H3 datapath does not pass through here; its QUIC socket is protected
/// inside `ripdpi_hysteria2::build_client_udp_socket` (see `crate::h3::socket`).
// cancel-safe: holds no cross-await state and owns the freshly built socket; if
// the caller is dropped at the `.connect().await` point the socket fd is closed
// on drop, so no protected-but-leaked fd survives cancellation.
async fn connect_proxy_tcp(
    proxy_addr: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<tokio::net::TcpStream> {
    let socket = match proxy_addr {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    protect_outbound_socket(&socket, proxy_addr, socket_protection)?;
    let tcp = socket
        .connect(proxy_addr)
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("failed to connect to MASQUE proxy: {error}")))?;
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

/// Protect a freshly created outbound socket via the registered
/// `VpnService.protect()` callback before it connects to a non-loopback peer.
///
/// No-op for loopback. Fails closed for a non-loopback target when no callback
/// is registered: under a live TUN there is no other per-socket mechanism to
/// keep the socket out of the tunnel, so refusing is safer than dialing
/// unprotected. Mirrors the `ripdpi-vless` / `ripdpi-trojan` gold-standard
/// helper. See .claude/rules/vpnservice-protect-invariant.md.
fn protect_outbound_socket<T: AsRawFd>(
    socket: &T,
    target: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect_non_loopback(socket.as_raw_fd(), target)
        .map_err(|error| io::Error::new(error.kind(), format!("protect MASQUE H2 outbound socket: {error}")))
}

pub(crate) async fn attempt_h2_connect_tcp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo + use<>, AttemptError> {
    let proxy_origin = parse_proxy_origin(config)?;
    let tcp =
        connect_proxy_tcp(resolve_proxy_socket_addr(config, &proxy_origin).await?, config.socket_protection).await?;
    attempt_h2_connect_tcp_over_transport(config, tcp, target, auth_header).await
}

pub(crate) async fn attempt_h2_connect_tcp_over_transport<S>(
    config: &MasqueConfig,
    transport: S,
    target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo + use<S>, AttemptError>
where
    S: AsyncIo + 'static,
{
    let proxy_origin = parse_proxy_origin(config)?;
    let mut connector_builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
    apply_h2_client_auth(&mut connector_builder, config)?;
    apply_h2_root_certificate(&mut connector_builder, config)?;
    // Test-only: relax verification for the loopback fixture's self-signed cert
    // ONLY when no explicit trust anchor was pinned, so a test that sets
    // `root_certificate_pem` exercises the real pin-and-verify path.
    #[cfg(test)]
    if config.root_certificate_pem.is_none() {
        relax_loopback_fixture_certificate_verification(&mut connector_builder, &proxy_origin.host);
    }
    let connector = connector_builder.build();
    let mut ssl = connector
        .configure()
        .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
    ripdpi_tls_profiles::configure_boring_ech(&mut ssl, config.ech_config.as_ref())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("MASQUE H2 ECH: {error}")))?;
    let tls = tokio_boring::connect(ssl, &proxy_origin.host, transport).await.map_err(|error| {
        if config.ech_config.is_some() && error.ssl().is_some_and(|ssl| ssl.get_ech_retry_configs().is_some()) {
            return io::Error::new(
                io::ErrorKind::InvalidData,
                format!("MASQUE H2 ECH: {}", ripdpi_tls_profiles::EchOutboundError::RetryRequired),
            );
        }
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 TLS handshake failed: {error}"))
    })?;

    let io = TokioIo::new(tls);
    let (mut sender, connection) =
        hyper::client::conn::http2::handshake(hyper_util::rt::TokioExecutor::new(), io).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate H2: {error}"))
        })?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "MASQUE H2 TCP driver closed");
        }
    });

    let request = build_h2_connect_tcp_request(target, config, auth_header)?;
    let response = sender.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H2 CONNECT request: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to upgrade H2 CONNECT stream: {error}"))
    })?;
    Ok(TokioIo::new(upgraded))
}

pub(crate) async fn attempt_h2_connect_udp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
) -> Result<MasqueUdpFlow, AttemptError> {
    let target = crate::url::parse_target(target)?;
    let target_label = target.authority();
    let proxy_origin = parse_proxy_origin(config)?;
    let tcp =
        connect_proxy_tcp(resolve_proxy_socket_addr(config, &proxy_origin).await?, config.socket_protection).await?;

    let mut connector_builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
    apply_h2_client_auth(&mut connector_builder, config)?;
    apply_h2_root_certificate(&mut connector_builder, config)?;
    // Test-only: relax verification for the loopback fixture's self-signed cert
    // ONLY when no explicit trust anchor was pinned, so a test that sets
    // `root_certificate_pem` exercises the real pin-and-verify path.
    #[cfg(test)]
    if config.root_certificate_pem.is_none() {
        relax_loopback_fixture_certificate_verification(&mut connector_builder, &proxy_origin.host);
    }
    let connector = connector_builder.build();
    let mut ssl = connector
        .configure()
        .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
    ripdpi_tls_profiles::configure_boring_ech(&mut ssl, config.ech_config.as_ref())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("MASQUE H2 ECH: {error}")))?;
    let tls = tokio_boring::connect(ssl, &proxy_origin.host, tcp).await.map_err(|error| {
        if config.ech_config.is_some() && error.ssl().is_some_and(|ssl| ssl.get_ech_retry_configs().is_some()) {
            return io::Error::new(
                io::ErrorKind::InvalidData,
                format!("MASQUE H2 ECH: {}", ripdpi_tls_profiles::EchOutboundError::RetryRequired),
            );
        }
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 TLS handshake failed: {error}"))
    })?;

    let (mut sender, connection) =
        hyper::client::conn::http2::handshake(hyper_util::rt::TokioExecutor::new(), TokioIo::new(tls)).await.map_err(
            |error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate H2: {error}")),
        )?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "MASQUE H2 UDP driver closed");
        }
    });

    let request = build_h2_connect_udp_request(&proxy_origin, &target, auth_header)?;
    let response = sender.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H2 CONNECT-UDP request: {error}"))
    })?;
    validate_connect_udp_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to upgrade H2 CONNECT-UDP stream: {error}"))
    })?;
    let (mut reader, mut writer) = tokio::io::split(TokioIo::new(upgraded));
    let (outgoing_tx, mut outgoing_rx) = mpsc::channel::<Vec<u8>>(64);
    let writer_task = tokio::spawn(async move {
        while let Some(payload) = outgoing_rx.recv().await {
            let datagram = match encode_connect_udp_payload(0, &payload)
                .and_then(|payload| encode_h2_datagram_capsule(&payload))
            {
                Ok(datagram) => datagram,
                Err(error) => {
                    tracing::debug!(error = %error, "failed to encode MASQUE H2 UDP capsule");
                    break;
                }
            };
            if let Err(error) = writer.write_all(&datagram).await {
                tracing::debug!(error = %error, "failed to write MASQUE H2 UDP capsule");
                break;
            }
        }
    });
    let reader_task = tokio::spawn(async move {
        let mut buffer = Vec::new();
        let mut chunk = [0u8; 4096];
        loop {
            let read = match reader.read(&mut chunk).await {
                Ok(0) => break,
                Ok(read) => read,
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "MASQUE H2 UDP capsule reader closed");
                    break;
                }
            };
            buffer.extend_from_slice(&chunk[..read]);
            match decode_h2_datagram_capsules(&buffer) {
                Ok(capsules) => {
                    buffer.clear();
                    for capsule in capsules {
                        match decode_connect_udp_payload(&capsule) {
                            Ok(Some((_, payload))) => {
                                if incoming_tx.send((target_label.clone(), payload)).await.is_err() {
                                    return;
                                }
                            }
                            Ok(None) => {
                                tracing::debug!(target = %target_label, "ignored MASQUE H2 UDP capsule for unsupported context id");
                            }
                            Err(error) => {
                                tracing::debug!(error = %error, target = %target_label, "ignored malformed MASQUE H2 UDP capsule payload");
                            }
                        }
                    }
                }
                Err(error) if error.kind() == CapsuleErrorKind::Truncated => {}
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "failed to decode MASQUE H2 UDP capsules");
                    break;
                }
            }
        }
    });

    Ok(MasqueUdpFlow::new(MasqueUdpSender::H2(outgoing_tx), None, writer_task, reader_task, None, 0))
}

#[cfg(test)]
fn relax_loopback_fixture_certificate_verification(builder: &mut boring::ssl::SslConnectorBuilder, proxy_host: &str) {
    if matches!(proxy_host, "127.0.0.1" | "localhost") {
        builder.set_verify(SslVerifyMode::NONE);
    }
}

#[cfg(test)]
mod protect_tests {
    //! The H2 fallback carrier socket must be protected before it touches the
    //! wire (vpnservice-protect-invariant.md). The protect callback registry is
    //! process-global, so every test serializes on `TEST_LOCK` and clears the
    //! slot before releasing it.

    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::{io, protect_outbound_socket};

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            // Release pairs with the Acquire load in the assertions below.
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    #[test]
    fn ech_bootstrap_hooks_preserve_socket_policy() {
        use ripdpi_native_protect::SocketProtectionPolicy::{Inactive, VpnRequired};
        use std::time::Duration;
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime");
        let bind = Some("203.0.113.254".parse().expect("unassigned source"));
        let hooks = crate::ech::connect_hooks(bind, VpnRequired);
        let tcp = hooks.direct_tcp_connector.expect("required TCP connector");
        let error = runtime.block_on(tcp(non_loopback(), Duration::from_millis(20))).err().expect("no callback");
        assert_eq!(error.kind(), io::ErrorKind::NotConnected, "protect must precede bind/connect");
        let udp = crate::ech::connect_hooks(None, VpnRequired).direct_udp_binder.expect("UDP binder");
        assert_eq!(udp("0.0.0.0:0".parse().unwrap()).unwrap_err().kind(), io::ErrorKind::NotConnected);

        struct RejectCallback;
        impl ProtectCallback for RejectCallback {
            fn protect(&self, _: RawFd) -> io::Result<()> {
                Err(io::Error::new(io::ErrorKind::PermissionDenied, "fixture rejection"))
            }
        }
        register_protect_callback(Arc::new(RejectCallback));
        assert_eq!(
            runtime.block_on(tcp(non_loopback(), Duration::from_millis(20))).err().unwrap().kind(),
            io::ErrorKind::PermissionDenied
        );
        assert_eq!(udp("0.0.0.0:0".parse().unwrap()).unwrap_err().kind(), io::ErrorKind::PermissionDenied);

        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let error = runtime.block_on(tcp(non_loopback(), Duration::from_millis(20))).err().unwrap();
        assert_eq!(error.kind(), io::ErrorKind::AddrNotAvailable);
        assert!(cb.last_fd.load(Ordering::Acquire) >= 0, "protect ran before source bind");
        let socket = udp("0.0.0.0:0".parse().unwrap()).expect("protected UDP");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), socket.as_raw_fd());
        unregister_protect_callback();

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("loopback server");
        let hooks = crate::ech::connect_hooks(None, Inactive);
        runtime
            .block_on(hooks.direct_tcp_connector.unwrap()(listener.local_addr().unwrap(), Duration::from_secs(1)))
            .expect("inactive TCP");
        hooks.direct_udp_binder.unwrap()("127.0.0.1:0".parse().unwrap()).expect("inactive UDP");
    }

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let err = protect_outbound_socket(
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
        protect_outbound_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");
        // The exact fd handed to the callback must be the carrier socket's fd.
        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd());
        unregister_protect_callback();
    }

    #[test]
    fn loopback_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");
        // Loopback skip: the callback must never have been invoked.
        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1);
        unregister_protect_callback();
    }
}
