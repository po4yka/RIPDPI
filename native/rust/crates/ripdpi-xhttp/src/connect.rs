use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use hyper::client::conn::http2;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::XhttpMode;
use crate::finalmask;
use crate::pool::PooledConnection;

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
            let connector = ripdpi_tls_profiles::build_connector(&config.tls_fingerprint_profile, true)
                .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
            let ssl = connector.configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
            let tls = tokio_boring::connect(ssl, &config.server_name, transport).await.map_err(|error| {
                io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP TLS handshake: {error}"))
            })?;
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
