use boring::ssl::SslConnector;
use thiserror::Error;

use crate::bridge_line::WebTunnelBridgeConfig;

#[derive(Debug, Error)]
pub enum WebTunnelTlsError {
    #[error("TLS profile error: {0}")]
    Profile(#[from] ripdpi_tls_profiles::Error),
}

pub fn build_tls_connector(config: &WebTunnelBridgeConfig, verify: bool) -> Result<SslConnector, WebTunnelTlsError> {
    Ok(ripdpi_tls_profiles::build_connector(&config.tls_profile, verify)?)
}
