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
    #[error("WebTunnel ALPN: {0}")]
    Alpn(boring::error::ErrorStack),
}

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";

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
    build_connector(config, false)
}

/// Same as [`build_tls_connector`], but the ClientHello offers only
/// `http/1.1` in ALPN, as Chrome does for a WebSocket connection. The client
/// uses it when a front selected h2 for the profile ALPN list, because the
/// WebTunnel upgrade is an HTTP/1.1 request.
pub fn build_http11_tls_connector(config: &WebTunnelBridgeConfig) -> Result<SslConnector, WebTunnelTlsError> {
    build_connector(config, true)
}

fn build_connector(config: &WebTunnelBridgeConfig, http11_only: bool) -> Result<SslConnector, WebTunnelTlsError> {
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_profile)?;
    if http11_only {
        builder.set_alpn_protos(HTTP11_ALPN).map_err(WebTunnelTlsError::Alpn)?;
    }
    builder.set_verify(SslVerifyMode::PEER);
    if let Some(root_pem) = &config.root_certificate_pem {
        let root = X509::from_pem(root_pem.as_bytes()).map_err(WebTunnelTlsError::TrustRoot)?;
        builder.cert_store_mut().add_cert(root).map_err(WebTunnelTlsError::TrustRoot)?;
    }
    Ok(builder.build())
}
