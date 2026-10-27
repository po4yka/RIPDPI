use std::io;
use std::net::TcpStream;
use std::time::Duration;

use boring::ssl::{SslConnector, SslStream};
use thiserror::Error;

use crate::bridge_line::WebTunnelBridgeConfig;
use crate::http_upgrade::{HttpUpgradeError, HttpUpgradeRequest, perform_http_upgrade, perform_http_upgrade_async};
use crate::tls::{WebTunnelTlsError, build_http11_tls_connector, build_tls_connector, configure_connection};

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
    #[error("WebTunnel bridge selected ALPN h2; the upgrade needs HTTP/1.1")]
    H2Selected,
    #[error(transparent)]
    HttpUpgrade(#[from] HttpUpgradeError),
}

/// Which ALPN list the ClientHello offers.
#[derive(Clone, Copy)]
enum AlpnOffer {
    /// The TLS profile's own list (h2 and http/1.1), for fingerprint parity.
    Profile,
    /// Only http/1.1, used after a front selected h2.
    Http11Only,
}

fn connector_for(config: &WebTunnelBridgeConfig, offer: AlpnOffer) -> Result<SslConnector, WebTunnelClientError> {
    Ok(match offer {
        AlpnOffer::Profile => build_tls_connector(config)?,
        AlpnOffer::Http11Only => build_http11_tls_connector(config)?,
    })
}

/// The WebTunnel upgrade is an HTTP/1.1 request; on an h2 connection the front
/// expects the HTTP/2 preface and closes. Never send the secret path there.
fn reject_h2(ssl: &boring::ssl::SslRef) -> Result<(), WebTunnelClientError> {
    if ssl.selected_alpn_protocol() == Some(b"h2") {
        return Err(WebTunnelClientError::H2Selected);
    }
    Ok(())
}

/// Connect to a WebTunnel bridge: TCP, verified TLS (see
/// [`build_tls_connector`]), then the HTTP Upgrade to the secret path.
///
/// The first ClientHello offers the profile ALPN list. A front with HTTP/2
/// enabled selects h2 for it; the client then closes that connection before
/// it sends the secret path and connects again offering only `http/1.1`.
pub fn connect_webtunnel(config: &WebTunnelBridgeConfig) -> Result<WebTunnelStream, WebTunnelClientError> {
    match connect_webtunnel_with(config, AlpnOffer::Profile) {
        Err(WebTunnelClientError::H2Selected) => connect_webtunnel_with(config, AlpnOffer::Http11Only),
        result => result,
    }
}

fn connect_webtunnel_with(
    config: &WebTunnelBridgeConfig,
    offer: AlpnOffer,
) -> Result<WebTunnelStream, WebTunnelClientError> {
    let tcp = ripdpi_subprocess_protect::protected_tcp_connect_blocking(&config.addr, config.protect_path.as_deref())
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;
    tcp.set_read_timeout(Some(IO_TIMEOUT))
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;
    tcp.set_write_timeout(Some(IO_TIMEOUT))
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;

    let connector = connector_for(config, offer)?;
    let tls = configure_connection(&connector, config)?
        .connect(&config.servername, tcp)
        .map_err(|error| WebTunnelClientError::TlsHandshake(error.to_string()))?;
    reject_h2(tls.ssl())?;
    let request = HttpUpgradeRequest { host: config.http_host.clone(), secret_path: config.secret_path.clone() };
    perform_http_upgrade(tls, &request).map_err(WebTunnelClientError::from)
}

/// Async counterpart of [`connect_webtunnel`]: the same TLS + HTTP-Upgrade
/// handshake (and the same http/1.1 retry after an h2 selection) driven over
/// tokio, yielding a [`WebTunnelAsyncStream`] suitable for async datapaths.
/// The TLS fingerprint, SNI, secret path and certificate verification are
/// identical to the sync client.
///
/// # Cancel safety
///
/// cancel-safe: dropping the future drops the in-progress TCP/TLS stream; no
/// state outlives it and the secret path is sent only on a finished upgrade.
pub async fn connect_webtunnel_async(
    config: &WebTunnelBridgeConfig,
) -> Result<WebTunnelAsyncStream, WebTunnelClientError> {
    match connect_webtunnel_async_with(config, AlpnOffer::Profile).await {
        Err(WebTunnelClientError::H2Selected) => connect_webtunnel_async_with(config, AlpnOffer::Http11Only).await,
        result => result,
    }
}

/// # Cancel safety
///
/// cancel-safe: see [`connect_webtunnel_async`].
async fn connect_webtunnel_async_with(
    config: &WebTunnelBridgeConfig,
    offer: AlpnOffer,
) -> Result<WebTunnelAsyncStream, WebTunnelClientError> {
    let tcp = ripdpi_subprocess_protect::protected_tcp_connect(&config.addr, config.protect_path.as_deref())
        .await
        .map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;
    tcp.set_nodelay(true).map_err(|source| WebTunnelClientError::TcpConnect { addr: config.addr.clone(), source })?;

    let connector = connector_for(config, offer)?;
    let ssl = configure_connection(&connector, config)?;
    let tls = tokio_boring::connect(ssl, &config.servername, tcp)
        .await
        .map_err(|error| WebTunnelClientError::TlsHandshake(error.to_string()))?;
    reject_h2(tls.ssl())?;

    let request = HttpUpgradeRequest { host: config.http_host.clone(), secret_path: config.secret_path.clone() };
    perform_http_upgrade_async(tls, &request).await.map_err(WebTunnelClientError::from)
}
