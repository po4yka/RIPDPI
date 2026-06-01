use std::io;
use std::net::TcpStream;
use std::time::Duration;

use boring::ssl::SslStream;
use thiserror::Error;

use crate::bridge_line::WebTunnelBridgeConfig;
use crate::http_upgrade::{HttpUpgradeError, HttpUpgradeRequest, perform_http_upgrade};
use crate::tls::{WebTunnelTlsError, build_tls_connector};

const IO_TIMEOUT: Duration = Duration::from_secs(10);

pub type WebTunnelStream = SslStream<TcpStream>;

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
    let tcp = TcpStream::connect(&config.addr)
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
