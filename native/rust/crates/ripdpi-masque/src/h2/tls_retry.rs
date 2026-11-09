use std::io;
use std::net::SocketAddr;

use crate::client::AsyncIo;
use crate::config::MasqueConfig;
use crate::tls::{apply_h2_client_auth, apply_h2_root_certificate, ech_setup};
use crate::url::ProxyOrigin;

/// # Cancel safety:
/// cancel-safe: partial carriers and TLS state are owned until return. Retry is
/// possible only for an owned numeric peer, never for an injected chain carrier.
pub(super) async fn connect<S: AsyncIo + 'static>(
    config: &MasqueConfig,
    origin: &ProxyOrigin,
    transport: S,
    peer: Option<SocketAddr>,
) -> io::Result<tokio_boring::SslStream<Box<dyn AsyncIo>>> {
    let mut setup = ech_setup(config);
    let mut carrier: Box<dyn AsyncIo> = Box::new(transport);
    for attempt in 0..2 {
        let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
            .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
        builder
            .set_alpn_protos(b"\x02h2")
            .map_err(|error| io::Error::other(format!("H2 ALPN configuration failed: {error}")))?;
        apply_h2_client_auth(&mut builder, config)?;
        apply_h2_root_certificate(&mut builder, config)?;
        #[cfg(test)]
        if config.root_certificate_pem.is_none() {
            super::relax_loopback_fixture_certificate_verification(&mut builder, &origin.host);
        }
        let mut ssl = builder
            .build()
            .configure()
            .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
        ripdpi_tls_profiles::configure_ech(&mut ssl, &setup)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("MASQUE H2 ECH: {error}")))?;
        match tokio_boring::connect(ssl, &origin.host, carrier).await {
            Ok(tls) => {
                if matches!(setup, ripdpi_tls_profiles::EchSetup::Real(_)) && !tls.ssl().ech_accepted() {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "MASQUE H2 ECH was not accepted"));
                }
                if tls.ssl().selected_alpn_protocol() != Some(b"h2".as_slice()) {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "MASQUE H2 peer did not negotiate h2"));
                }
                return Ok(tls);
            }
            Err(error) => {
                let retry = error
                    .ssl()
                    .and_then(|ssl| ssl.get_ech_retry_configs())
                    .and_then(|bytes| ripdpi_tls_profiles::ech::retry::outbound_ech_retry_config(bytes.to_vec()));
                if attempt == 0
                    && matches!(setup, ripdpi_tls_profiles::EchSetup::Real(_))
                    && let (Some(peer), Some(retry)) = (peer, retry)
                {
                    setup = ripdpi_tls_profiles::EchSetup::Real(retry);
                    drop(error);
                    carrier = Box::new(super::connect_proxy_tcp(peer, config.socket_protection).await?);
                    continue;
                }
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    format!("H2 TLS handshake failed: {error}"),
                ));
            }
        }
    }
    Err(io::Error::other("ECH retry exhausted"))
}

#[cfg(test)]
mod tests;
