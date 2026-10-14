use boring::ssl::{SslConnector, SslVerifyMode};
use boring::x509::X509;
use thiserror::Error;

use crate::bridge_line::WebTunnelBridgeConfig;

#[derive(Debug, Error)]
pub enum WebTunnelTlsError {
    #[error("TLS profile error: {0}")]
    Profile(#[from] ripdpi_tls_profiles::Error),
    #[error("WebTunnel trust root: {0}")]
    TrustRoot(boring::error::ErrorStack),
}

/// Build the TLS connector for a WebTunnel bridge.
///
/// The bridge certificate is always verified, as in upstream WebTunnel, which
/// has no option to skip verification. The chain must lead to a bundled Mozilla
/// root (seeded by `ripdpi_tls_profiles`, because BoringSSL on Android has no
/// default CA store) or to `config.root_certificate_pem` when it is set. The
/// client connects with `config.servername`, so BoringSSL also checks the
/// certificate name against it. Without this, an on-path attacker with any
/// certificate receives the secret WebTunnel path.
pub fn build_tls_connector(config: &WebTunnelBridgeConfig) -> Result<SslConnector, WebTunnelTlsError> {
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_profile)?;
    builder.set_verify(SslVerifyMode::PEER);
    if let Some(root_pem) = &config.root_certificate_pem {
        let root = X509::from_pem(root_pem.as_bytes()).map_err(WebTunnelTlsError::TrustRoot)?;
        builder.cert_store_mut().add_cert(root).map_err(WebTunnelTlsError::TrustRoot)?;
    }
    Ok(builder.build())
}
