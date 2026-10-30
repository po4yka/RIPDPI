use boring::ssl::{ConnectConfiguration, SslAlert, SslConnector, SslRef, SslVerifyError, SslVerifyMode};
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
    #[error("WebTunnel cert option must be a base64 SHA-256 certificate chain hash")]
    CertPin,
    #[error("WebTunnel TLS setup: {0}")]
    Configure(boring::error::ErrorStack),
}

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";

/// Build the TLS connector for a WebTunnel bridge.
///
/// The bridge certificate is always verified, as in upstream WebTunnel. By
/// default the chain must lead to a bundled Mozilla root (seeded by
/// `ripdpi_tls_profiles`, because BoringSSL on Android has no default CA
/// store) or to `config.root_certificate_pem` when it is set, and its name must
/// match (see [`configure_connection`]). With the bridge-line `cert` option the
/// presented chain must instead match that pinned chain hash exactly, which
/// replaces the CA and name checks, as in upstream (this is how a bridge with a
/// self-signed certificate is reached). Without verification, an on-path
/// attacker with any certificate receives the secret WebTunnel path.
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
    if let Some(pin) = &config.cert {
        let pin = decode_cert_pin(pin)?;
        builder.set_custom_verify_callback(SslVerifyMode::PEER, move |ssl| verify_pinned_chain(ssl, &pin));
        return Ok(builder.build());
    }
    // configure_builder already verifies the peer; set_verify here would drop its blocklist callback.
    if let Some(root_pem) = &config.root_certificate_pem {
        let root = X509::from_pem(root_pem.as_bytes()).map_err(WebTunnelTlsError::TrustRoot)?;
        builder.cert_store_mut().add_cert(root).map_err(WebTunnelTlsError::TrustRoot)?;
    }
    Ok(builder.build())
}

/// The per-connection TLS setup. Connect it with `config.servername`, which is
/// the SNI name. The certificate name is checked against `config.cert_domain`
/// when set (the bridge-line `cert-domain` option, or the URL host with
/// `sni-imitation`), else against `config.servername`. With a `cert` pin no
/// name is checked, as in upstream.
pub fn configure_connection(
    connector: &SslConnector,
    config: &WebTunnelBridgeConfig,
) -> Result<ConnectConfiguration, WebTunnelTlsError> {
    let mut connection = connector.configure().map_err(WebTunnelTlsError::Configure)?;
    if config.cert.is_none()
        && let Some(domain) = &config.cert_domain
    {
        connection.set_verify_hostname(false);
        connection.param_mut().set_host(domain).map_err(WebTunnelTlsError::Configure)?;
    }
    Ok(connection)
}

fn decode_cert_pin(pin: &str) -> Result<[u8; 32], WebTunnelTlsError> {
    let hash = boring::base64::decode_block(pin.trim()).map_err(|_| WebTunnelTlsError::CertPin)?;
    hash.try_into().map_err(|_| WebTunnelTlsError::CertPin)
}

/// Upstream `certiChainHashCalc.GenerateCertChainHash` over the DER
/// certificates as the server sent them, leaf first: `h = SHA-256(cert0)`,
/// then `h = SHA-256(h || SHA-256(cert_i))`. `None` for an empty chain.
fn cert_chain_hash<'a>(certificates: impl IntoIterator<Item = &'a [u8]>) -> Option<[u8; 32]> {
    certificates.into_iter().fold(None, |hash, certificate| {
        let certificate_hash = boring::sha::sha256(certificate);
        Some(match hash {
            None => certificate_hash,
            Some(hash) => boring::sha::sha256(&[hash, certificate_hash].concat()),
        })
    })
}

fn verify_pinned_chain(ssl: &mut SslRef, pin: &[u8; 32]) -> Result<(), SslVerifyError> {
    let bad_certificate = SslVerifyError::Invalid(SslAlert::BAD_CERTIFICATE);
    // On the client side the peer chain includes the leaf.
    let chain = ssl.peer_cert_chain().ok_or(bad_certificate)?;
    let certificates = chain
        .iter()
        .map(boring::x509::X509Ref::to_der)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| SslVerifyError::Invalid(SslAlert::INTERNAL_ERROR))?;
    let hash = cert_chain_hash(certificates.iter().map(Vec::as_slice)).ok_or(bad_certificate)?;
    if boring::memcmp::eq(&hash, pin) { Ok(()) } else { Err(bad_certificate) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cert_chain_hash_chains_sha256_over_the_chain_leaf_first() {
        let (leaf, intermediate) = (b"leaf der".as_slice(), b"intermediate der".as_slice());
        let leaf_hash = boring::sha::sha256(leaf);
        let mut joined = leaf_hash.to_vec();
        joined.extend_from_slice(&boring::sha::sha256(intermediate));

        assert_eq!(cert_chain_hash([leaf]), Some(leaf_hash));
        assert_eq!(cert_chain_hash([leaf, intermediate]), Some(boring::sha::sha256(&joined)));
        assert_eq!(cert_chain_hash([]), None);
    }

    #[test]
    fn cert_pin_must_be_a_base64_sha256_hash() {
        assert!(decode_cert_pin(&boring::base64::encode_block(&[7; 32])).is_ok());
        assert!(matches!(decode_cert_pin("YWJj"), Err(WebTunnelTlsError::CertPin)));
        assert!(matches!(decode_cert_pin("not base64!"), Err(WebTunnelTlsError::CertPin)));
    }
}
