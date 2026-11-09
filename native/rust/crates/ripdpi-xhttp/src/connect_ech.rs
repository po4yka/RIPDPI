use std::io;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::OwnedSemaphorePermit;

use crate::config::{AsyncIo, XhttpTlsConfig};
use crate::{finalmask, owned_io::OwnedIo};

/// # Cancel safety:
/// cancel-safe: each attempt owns its socket and a capacity guard; dropping it
/// closes the socket before its final capacity owner is released.
pub(crate) async fn connect_owned_tls(
    config: &XhttpTlsConfig,
    capacity: OwnedSemaphorePermit,
) -> io::Result<Box<dyn AsyncIo>> {
    let server_name = ripdpi_tls_profiles::normalize_server_name(&config.server_name)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let profile = ripdpi_tls_profiles::resolve_connection_profile(&config.tls_fingerprint_profile, &server_name);
    let build = || {
        ripdpi_tls_profiles::configure_builder(profile)
            .map_err(|error| io::Error::other(format!("TLS profile: {error}")))
    };
    if config.ech_config.is_some() {
        tokio::time::timeout(Duration::from_secs(10), connect_attempts(config, &server_name, capacity, build))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "xHTTP ECH handshake deadline exceeded"))?
    } else {
        connect_attempts(config, &server_name, capacity, build).await
    }
}

/// # Cancel safety:
/// cancel-safe: partial TLS and finalmask state is owned by this attempt.
async fn connect_attempts(
    config: &XhttpTlsConfig,
    server_name: &str,
    capacity: OwnedSemaphorePermit,
    build: impl Fn() -> io::Result<boring::ssl::SslConnectorBuilder>,
) -> io::Result<Box<dyn AsyncIo>> {
    let capacity = Arc::new(capacity);
    let mut current = config.ech_config.clone();
    let mut peer = None;
    for attempt in 0..2 {
        let tcp = match peer {
            Some(peer) => {
                super::connect::connect_tcp_target(peer, config.bind_ip, config.socket_protector.as_ref()).await?
            }
            None => {
                super::connect::connect_tcp_stream(
                    &config.server,
                    config.port,
                    config.bind_ip,
                    config.socket_protector.as_ref(),
                )
                .await?
            }
        };
        peer = Some(tcp.peer_addr()?);
        let transport = finalmask::wrap_tcp_stream(OwnedIo::new(tcp, capacity.clone()), &config.finalmask)?;
        let mut builder = build()?;
        builder
            .set_alpn_protos(b"\x02h2")
            .map_err(|error| io::Error::other(format!("xHTTP ALPN configuration: {error}")))?;
        if let Some(groups) = config.kem_groups.as_deref() {
            ripdpi_tls_profiles::apply_kem_groups(&mut builder, groups)
                .map_err(|error| io::Error::other(format!("TLS KEM groups: {error}")))?;
        }
        let mut ssl =
            builder.build().configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
        ripdpi_tls_profiles::configure_boring_ech(&mut ssl, current.as_ref())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP ECH: {error}")))?;
        match tokio_boring::connect(ssl, server_name, transport).await {
            Ok(tls) => {
                if current.is_some() && !tls.ssl().ech_accepted() {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "xHTTP ECH was not accepted"));
                }
                if tls.ssl().selected_alpn_protocol() != Some(b"h2".as_slice()) {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "xHTTP TLS peer did not negotiate h2"));
                }
                ripdpi_tls_profiles::note_pq_kem_negotiation(tls.ssl().curve());
                return Ok(Box::new(tls));
            }
            Err(error) => {
                let retry = error
                    .ssl()
                    .and_then(|ssl| ssl.get_ech_retry_configs())
                    .and_then(|bytes| ripdpi_tls_profiles::ech::retry::outbound_ech_retry_config(bytes.to_vec()));
                if attempt == 0
                    && current.is_some()
                    && let Some(retry) = retry
                {
                    current = Some(retry);
                    drop(error);
                    continue;
                }
                return Err(io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP TLS handshake: {error}")));
            }
        }
    }
    Err(io::Error::other("ECH retry exhausted"))
}

#[cfg(test)]
mod tests;
