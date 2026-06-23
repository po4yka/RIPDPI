use std::io;
use std::net::TcpStream;
use std::time::Duration;

use boring::ssl::SslStream;
use thiserror::Error;

use crate::bridge_line::WebTunnelBridgeConfig;
use crate::http_upgrade::{HttpUpgradeError, HttpUpgradeRequest, perform_http_upgrade, perform_http_upgrade_async};
use crate::tls::{WebTunnelTlsError, build_tls_connector};

const IO_TIMEOUT: Duration = Duration::from_secs(10);

pub type WebTunnelStream = SslStream<TcpStream>;

/// Async WebTunnel tunnel: a tokio `SslStream` over a tokio `TcpStream`. Unlike
/// [`WebTunnelStream`] (sync `std` I/O) this implements tokio
/// `AsyncRead`/`AsyncWrite`, so it can be `tokio::io::split` and driven from an
/// async runtime.
pub type WebTunnelAsyncStream = tokio_boring::SslStream<tokio::net::TcpStream>;

#[derive(Debug, Error)]
pub enum WebTunnelClientError {
    #[error("connect to WebTunnel bridge {addr}: {source}")]
    TcpConnect { addr: String, source: io::Error },
    #[error(transparent)]
    TlsProfile(#[from] WebTunnelTlsError),
    #[error("TLS handshake with WebTunnel bridge: {0}")]
    TlsHandshake(String),
    #[error(transparent)]
    HttpUpgrade(#[from] HttpUpgradeError),
}

pub fn connect_webtunnel(
    config: &WebTunnelBridgeConfig,
    verify: bool,
) -> Result<WebTunnelStream, WebTunnelClientError> {
    let tcp = ripdpi_subprocess_protect::protected_tcp_connect_blocking(&config.addr, config.protect_path.as_deref())
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;
    tcp.set_read_timeout(Some(IO_TIMEOUT))
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;
    tcp.set_write_timeout(Some(IO_TIMEOUT))
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;

    let connector = build_tls_connector(config, verify)?;
    let tls = connector
        .connect(&config.servername, tcp)
        .map_err(|error| WebTunnelClientError::TlsHandshake(error.to_string()))?;
    let request = HttpUpgradeRequest { host: config.http_host.clone(), secret_path: config.secret_path.clone() };
    perform_http_upgrade(tls, &request).map_err(WebTunnelClientError::from)
}

/// Async counterpart of [`connect_webtunnel`]: the same TLS + HTTP-Upgrade
/// handshake driven over tokio, yielding a [`WebTunnelAsyncStream`] suitable for
/// async datapaths. The TLS fingerprint, SNI, secret path and `verify` semantics
/// are identical to the sync client.
pub async fn connect_webtunnel_async(
    config: &WebTunnelBridgeConfig,
    verify: bool,
) -> Result<WebTunnelAsyncStream, WebTunnelClientError> {
    let tcp = ripdpi_subprocess_protect::protected_tcp_connect(&config.addr, config.protect_path.as_deref())
        .await
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;
    tcp.set_nodelay(true).map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;

    let connector = build_tls_connector(config, verify)?;
    let ssl = connector.configure().map_err(|error| WebTunnelClientError::TlsHandshake(error.to_string()))?;
    let tls = tokio_boring::connect(ssl, &config.servername, tcp)
        .await
        .map_err(|error| WebTunnelClientError::TlsHandshake(error.to_string()))?;

    let request = HttpUpgradeRequest { host: config.http_host.clone(), secret_path: config.secret_path.clone() };
    perform_http_upgrade_async(tls, &request).await.map_err(WebTunnelClientError::from)
}
