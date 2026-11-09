use std::future::poll_fn;
use std::io;
use std::task::Poll;

use bytes::Bytes;
use h3::ConnectionState;
use rustls::RootCertStore;

use super::socket::maybe_rebind_quic_endpoint;
use crate::config::MasqueConfig;
use crate::response::AttemptError;
use crate::tls::{ech_setup, load_client_identity};
use crate::url::parse_proxy_origin;

pub(crate) const H3_MTU_DISCOVERY_UPPER_BOUND: u16 = 1_452;
pub(crate) const H3_KEEP_ALIVE_INTERVAL: std::time::Duration = std::time::Duration::from_secs(10);

/// The rustls config of the H3 proxy handshake, with the published ECH config
/// or GREASE ECH (aws-lc-rs supplies the HPKE suites for both).
pub(crate) fn h3_tls_config(config: &MasqueConfig, roots: RootCertStore) -> io::Result<rustls::ClientConfig> {
    let ech_builder = ripdpi_tls_profiles::configure_rustls_ech(
        rustls::ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into()),
        &ech_setup(config),
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid MASQUE ECH config: {error}")))?;
    let builder = ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(ech_builder, roots)
        .map_err(|error| io::Error::other(format!("failed to build MASQUE certificate verifier: {error}")))?;
    let mut tls_config = if let Some((certificates, private_key)) = load_client_identity(config)? {
        builder
            .with_client_auth_cert(certificates, private_key)
            .map_err(|error| io::Error::other(format!("failed to configure MASQUE client identity: {error}")))?
    } else {
        builder.with_no_client_auth()
    };
    tls_config.alpn_protocols = vec![b"h3".to_vec()];
    Ok(tls_config)
}

/// # Cancel safety:
/// cancel-safe: the unpublished QUIC connection and H3 handshake remain owned;
/// dropping them initiates transport shutdown.
pub(super) async fn connect_h3_transport(
    config: &MasqueConfig,
    enable_datagram: bool,
    terminal: std::sync::Arc<std::sync::Mutex<bool>>,
) -> Result<
    (
        h3::client::Connection<h3_quinn::Connection, Bytes>,
        h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
        quinn::Connection,
    ),
    AttemptError,
> {
    let proxy_origin = parse_proxy_origin(config)?;
    let (endpoint, connection, proxy_addr) =
        super::retry::connect_quic_with_ech_retry(config, &proxy_origin, terminal).await?;
    maybe_rebind_quic_endpoint(config, &endpoint, proxy_addr)
        .map_err(|error| io::Error::other(format!("failed to rebind QUIC transport: {error}")))?;

    let mut builder = h3::client::builder();
    builder.enable_extended_connect(true);
    builder.enable_datagram(enable_datagram);
    let path_connection = connection.clone();
    let (mut driver, request_sender) = builder.build(h3_quinn::Connection::new(connection)).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate HTTP/3: {error}"))
    })?;
    poll_fn(|cx| {
        if let Poll::Ready(error) = driver.poll_close(cx) {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("HTTP/3 control stream closed before CONNECT-UDP: {error}"),
            )));
        }
        let Some(settings) = driver.peer_settings() else {
            return Poll::Pending;
        };
        if !settings.enable_extended_connect() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "masque_h3_extended_connect_unsupported: proxy did not enable Extended CONNECT",
            )));
        }
        if enable_datagram && !settings.enable_datagram() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "masque_h3_datagram_unsupported: proxy did not enable HTTP Datagrams; capsule-only H3 is unsupported",
            )));
        }
        if enable_datagram && path_connection.max_datagram_size().is_none() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "masque_h3_datagram_unsupported: proxy did not enable QUIC DATAGRAM transport",
            )));
        }
        Poll::Ready(Ok(()))
    })
    .await?;
    Ok((driver, request_sender, path_connection))
}
