use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::Arc;

use hyper::client::conn::http2;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::{AsyncIo, XhttpMode, XhttpSocketProtector};
use crate::finalmask;
use crate::pool::PooledConnection;

/// NOT cancel-safe: dropping this future mid-flight may abandon a partially
/// established TCP/TLS/H2 connection without returning it to the pool; the caller
/// must treat a cancelled connect as "no connection" and retry from scratch.
/// (PQ-KEM telemetry only fires after a fully-completed handshake, so a cancelled
/// connect never miscounts.)
pub(crate) async fn create_connection(
    mode: &XhttpMode,
    max_concurrent_streams: usize,
) -> io::Result<Arc<PooledConnection>> {
    let io: Box<dyn AsyncIo> = match mode {
        XhttpMode::Reality(config) => {
            let transport = finalmask::wrap_tcp_stream(
                connect_tcp_stream(
                    &config.vless.server,
                    config.vless.port,
                    config.bind_ip,
                    config.socket_protector.as_ref(),
                )
                .await?,
                &config.finalmask,
            )?;
            let tls = ripdpi_vless::reality::connect_reality_tls_over(transport, &config.vless).await?;
            Box::new(tls)
        }
        XhttpMode::Tls(config) => {
            let transport = finalmask::wrap_tcp_stream(
                connect_tcp_stream(&config.server, config.port, config.bind_ip, config.socket_protector.as_ref())
                    .await?,
                &config.finalmask,
            )?;
            // Resolve the fingerprint profile per connection: the `rotating`
            // marker draws a fresh uTLS fingerprint from the rotation pool,
            // otherwise the configured profile is used as-is. (The Reality branch
            // above inherits rotation through `connect_reality_tls_over`.)
            let profile_name =
                ripdpi_tls_profiles::resolve_connection_profile(&config.tls_fingerprint_profile, &config.server_name);
            // Equivalent to `build_connector(profile_name, true)` (cert
            // verification stays ON — `configure_builder` does not disable it),
            // but split so an optional post-quantum KEM group override can be
            // applied AFTER profile resolution and BEFORE `.build()`.
            let mut builder = ripdpi_tls_profiles::configure_builder(profile_name)
                .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
            if let Some(kem_groups) = config.kem_groups.as_deref() {
                ripdpi_tls_profiles::apply_kem_groups(&mut builder, kem_groups)
                    .map_err(|error| io::Error::other(format!("TLS KEM groups: {error}")))?;
            }
            let connector = builder.build();
            let mut ssl = connector.configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
            ripdpi_tls_profiles::configure_boring_ech(&mut ssl, config.ech_config.as_ref())
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP ECH: {error}")))?;
            let tls = tokio_boring::connect(ssl, &config.server_name, transport).await.map_err(|error| {
                if config.ech_config.is_some() && error.ssl().is_some_and(|ssl| ssl.get_ech_retry_configs().is_some()) {
                    return io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!("xHTTP ECH: {}", ripdpi_tls_profiles::EchOutboundError::RetryRequired),
                    );
                }
                io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP TLS handshake: {error}"))
            })?;
            // PQ-KEM negotiation telemetry: increments `tls.pq_kem_negotiated`
            // iff the negotiated group is the hybrid X25519MLKEM768.
            // Privacy-safe (no authority / SNI / IP in the event).
            ripdpi_tls_profiles::note_pq_kem_negotiation(tls.ssl().curve());
            Box::new(tls)
        }
    };
    let io = TokioIo::new(io);

    let (sender, connection) = http2::handshake(TokioExecutor::new(), io)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP H2 handshake: {error}")))?;

    let pooled = Arc::new(PooledConnection::new(sender, max_concurrent_streams));
    let pooled_for_task = Arc::downgrade(&pooled);
    let driver = tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "xHTTP H2 connection closed");
        }
        if let Some(pooled) = pooled_for_task.upgrade() {
            pooled.mark_closed();
        }
    });
    pooled.attach_driver(driver);
    Ok(pooled)
}

/// cancel-safe: DNS/TCP futures and the not-yet-returned owned socket are
/// dropped on cancellation; no connected stream is published to the caller.
async fn connect_tcp_stream(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    socket_protector: Option<&XhttpSocketProtector>,
) -> io::Result<TcpStream> {
    let protect = socket_protector.cloned().map(|protector| {
        Box::new(move |fd| protector.protect(fd)) as Box<ripdpi_vless::endpoint_resolver::SocketProtectFn>
    });
    let target = ripdpi_vless::endpoint_resolver::resolve_server_addr(server, port, bind_ip, protect).await?;
    let socket = match target {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    if let Some(protector) = socket_protector {
        protector.protect(socket.as_raw_fd())?;
    }
    if let Some(bind_ip) = bind_ip {
        let bind_addr = match (bind_ip, target) {
            (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
            (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "outbound bind IP family does not match xHTTP server address family",
                ));
            }
        };
        socket.bind(bind_addr)?;
    }
    let stream = socket.connect(target).await?;
    stream.set_nodelay(true)?;
    Ok(stream)
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
    use std::time::Duration;

    use tokio::net::TcpListener;

    use super::*;
    use crate::config::{FinalmaskConfig, XhttpProtocolMode, XhttpRealityConfig, XhttpSocketProtector, XmuxConfig};

    #[tokio::test]
    async fn tcp_connect_invokes_socket_protector_before_connect() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind listener");
        let port = listener.local_addr().expect("local addr").port();
        let protect_ran = Arc::new(AtomicBool::new(false));
        let observed_fd = Arc::new(AtomicI32::new(-1));
        let protect_ran_cb = Arc::clone(&protect_ran);
        let observed_fd_cb = Arc::clone(&observed_fd);
        let protector = XhttpSocketProtector::new(move |fd| {
            observed_fd_cb.store(fd, Ordering::SeqCst);
            protect_ran_cb.store(true, Ordering::SeqCst);
            Ok(())
        });

        let accept = tokio::spawn(async move { listener.accept().await.expect("accept").0 });
        let stream =
            connect_tcp_stream("127.0.0.1", port, None, Some(&protector)).await.expect("protected xHTTP TCP connect");

        assert!(protect_ran.load(Ordering::SeqCst), "the socket protector must run before connect completes");
        assert!(observed_fd.load(Ordering::SeqCst) >= 0, "the protector saw the real socket fd");
        drop(stream);
        drop(accept.await.expect("accept task"));
    }

    #[tokio::test]
    async fn tcp_connect_fails_closed_when_socket_protector_rejects() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind listener");
        let port = listener.local_addr().expect("local addr").port();
        let protect_ran = Arc::new(AtomicBool::new(false));
        let protect_ran_cb = Arc::clone(&protect_ran);
        let protector = XhttpSocketProtector::new(move |_fd| {
            protect_ran_cb.store(true, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "protect rejected"))
        });

        let err = connect_tcp_stream("127.0.0.1", port, None, Some(&protector))
            .await
            .expect_err("rejecting protector must abort before connect");

        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied, "protector error propagates");
        assert!(protect_ran.load(Ordering::SeqCst), "the socket protector was consulted");
        let accepted = tokio::time::timeout(Duration::from_millis(100), listener.accept()).await;
        assert!(accepted.is_err(), "no unprotected TCP connection may be established after protect rejection");
    }

    #[tokio::test]
    async fn tcp_connect_invokes_socket_protector_before_optional_bind() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind listener");
        let port = listener.local_addr().expect("local addr").port();
        let protect_ran = Arc::new(AtomicBool::new(false));
        let protect_ran_cb = Arc::clone(&protect_ran);
        let protector = XhttpSocketProtector::new(move |_fd| {
            protect_ran_cb.store(true, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "protect before bind sentinel"))
        });

        let err = connect_tcp_stream("127.0.0.1", port, Some(IpAddr::from([192, 0, 2, 1])), Some(&protector))
            .await
            .expect_err("rejecting protector must abort before outbound bind");

        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied, "protector error must precede bind failure");
        assert!(protect_ran.load(Ordering::SeqCst), "the socket protector must run before outbound bind");
    }

    #[tokio::test]
    async fn hostname_resolution_uses_socket_protector_before_dns_bootstrap() {
        let protect_ran = Arc::new(AtomicBool::new(false));
        let protect_ran_cb = Arc::clone(&protect_ran);
        let protector = XhttpSocketProtector::new(move |_fd| {
            protect_ran_cb.store(true, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "xHTTP protected resolver sentinel"))
        });

        let err = connect_tcp_stream("relay.invalid", 443, None, Some(&protector))
            .await
            .expect_err("hostname resolution must fail through protected DNS bootstrap");

        assert!(protect_ran.load(Ordering::SeqCst), "the protected DNS bootstrap socket was protected");
        assert!(err.to_string().contains("xHTTP protected resolver sentinel"), "unexpected protected DNS error: {err}",);
    }

    #[tokio::test]
    async fn reality_mode_hostname_resolution_uses_socket_protector_before_dns_bootstrap() {
        let protect_ran = Arc::new(AtomicBool::new(false));
        let protect_ran_cb = Arc::clone(&protect_ran);
        let protector = XhttpSocketProtector::new(move |_fd| {
            protect_ran_cb.store(true, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "xHTTP Reality protected resolver sentinel"))
        });
        let vless = ripdpi_vless::config::VlessRealityConfig::from_strings(
            "relay.invalid",
            443,
            "550e8400-e29b-41d4-a716-446655440000",
            "relay.invalid",
            "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=",
            "abcd",
            "chrome_stable",
        )
        .expect("valid VLESS Reality config");
        let mode = XhttpMode::Reality(XhttpRealityConfig {
            vless,
            path: "/xhttp".to_string(),
            host: None,
            bind_ip: None,
            socket_protector: Some(protector),
            xmux: XmuxConfig::default(),
            finalmask: FinalmaskConfig::default(),
            protocol_mode: XhttpProtocolMode::default(),
        });

        let Err(err) = create_connection(&mode, 1).await else {
            panic!("xHTTP Reality hostname resolution must fail through protected DNS bootstrap");
        };

        assert!(protect_ran.load(Ordering::SeqCst), "the Reality DNS bootstrap socket was protected");
        assert!(
            err.to_string().contains("xHTTP Reality protected resolver sentinel"),
            "unexpected protected DNS error: {err}",
        );
    }
}
