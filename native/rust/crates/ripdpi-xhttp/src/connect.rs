use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use hyper::client::conn::http2;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::XhttpMode;
use crate::finalmask;
use crate::pool::PooledConnection;

// NOT cancel-safe: dropping this future mid-flight may abandon a partially
// established TCP/TLS/H2 connection without returning it to the pool; the caller
// must treat a cancelled connect as "no connection" and retry from scratch.
// (PQ-KEM telemetry only fires after a fully-completed handshake, so a cancelled
// connect never miscounts.)
pub(crate) async fn create_connection(
    mode: &XhttpMode,
    max_concurrent_streams: usize,
) -> io::Result<Arc<PooledConnection>> {
    let io = match mode {
        XhttpMode::Reality(config) => {
            let transport = finalmask::wrap_tcp_stream(
                connect_tcp_stream(&config.vless.server, config.vless.port, config.bind_ip).await?,
                &config.finalmask,
            )?;
            let tls = ripdpi_vless::reality::connect_reality_tls_over(transport, &config.vless).await?;
            TokioIo::new(tls)
        }
        XhttpMode::Tls(config) => {
            let transport = finalmask::wrap_tcp_stream(
                connect_tcp_stream(&config.server, config.port, config.bind_ip).await?,
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
            TokioIo::new(tls)
        }
    };

    let (sender, connection) = http2::handshake(TokioExecutor::new(), io)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP H2 handshake: {error}")))?;

    let pooled = Arc::new(PooledConnection::new(sender, max_concurrent_streams));
    let pooled_for_task = pooled.clone();
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "xHTTP H2 connection closed");
        }
        pooled_for_task.mark_closed();
    });
    Ok(pooled)
}

async fn connect_tcp_stream(server: &str, port: u16, bind_ip: Option<IpAddr>) -> io::Result<TcpStream> {
    let target = resolve_server_addr(server, port, bind_ip).await?;
    let socket = match target {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
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

// Cancel safety: cancel-safe. `tokio::net::lookup_host` runs the blocking
// resolver on tokio's blocking pool; cancellation drops only the address
// iterator. No caller-visible state mutates across the `.await`.
async fn resolve_server_addr(server: &str, port: u16, bind_ip: Option<IpAddr>) -> io::Result<SocketAddr> {
    // Async DNS via the blocking pool — avoids parking a tokio worker thread
    // in libc `getaddrinfo` on every xHTTP connection establishment.
    let mut candidates = tokio::net::lookup_host((server, port))
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("resolve {server}:{port}: {error}")))?;
    if let Some(bind_ip) = bind_ip {
        candidates.find(|address| address.is_ipv4() == bind_ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "xHTTP server has no address matching outbound bind IP family")
        })
    } else {
        candidates
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "xHTTP server resolved to no addresses"))
    }
}
