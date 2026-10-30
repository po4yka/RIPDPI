use std::io;
use std::sync::Arc;

use bytes::Bytes;
use rustls::RootCertStore;

use super::socket::{build_client_udp_socket, maybe_rebind_quic_endpoint};
use crate::config::MasqueConfig;
use crate::response::AttemptError;
use crate::tls::{apply_h3_root_certificate, ech_setup, load_client_identity};
use crate::url::{parse_proxy_origin, resolve_proxy_socket_addr};

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

pub(super) async fn connect_h3_transport(
    config: &MasqueConfig,
    enable_datagram: bool,
) -> Result<
    (
        h3::client::Connection<h3_quinn::Connection, Bytes>,
        h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
        quinn::Connection,
    ),
    AttemptError,
> {
    let proxy_origin = parse_proxy_origin(config)?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    apply_h3_root_certificate(&mut roots, config)?;
    let tls_config = h3_tls_config(config, roots)?;

    let mut quic_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::other(format!("failed to build QUIC TLS config: {error}")))?,
    ));
    let mut transport = quinn::TransportConfig::default();
    let mut mtu_discovery = quinn::MtuDiscoveryConfig::default();
    mtu_discovery.upper_bound(H3_MTU_DISCOVERY_UPPER_BOUND);
    transport.mtu_discovery_config(Some(mtu_discovery));
    // Keep idle flows alive: quinn's 30 s idle timeout is shorter than the
    // proxy's UDP inactivity window (RFC 9298 3.1 sets at least 2 minutes).
    transport.keep_alive_interval(Some(H3_KEEP_ALIVE_INTERVAL));
    quic_config.transport_config(Arc::new(transport));

    let proxy_addr = resolve_proxy_socket_addr(config, &proxy_origin).await?;
    let socket = build_client_udp_socket(proxy_addr.is_ipv6(), config.quic_bind_low_port, config.socket_protection)
        .map_err(|error| io::Error::other(format!("failed to bind QUIC client socket: {error}")))?;
    let mut endpoint =
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))
            .map_err(|error| io::Error::other(format!("failed to create QUIC client endpoint: {error}")))?;
    endpoint.set_default_client_config(quic_config);

    let connection = endpoint
        .connect(proxy_addr, &proxy_origin.host)
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC connect failed: {error}")))?
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC handshake failed: {error}")))?;
    maybe_rebind_quic_endpoint(config, &endpoint, proxy_addr)
        .map_err(|error| io::Error::other(format!("failed to rebind QUIC transport: {error}")))?;

    let mut builder = h3::client::builder();
    builder.enable_extended_connect(true);
    builder.enable_datagram(enable_datagram);
    let path_connection = connection.clone();
    let (driver, request_sender) = builder.build(h3_quinn::Connection::new(connection)).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate HTTP/3: {error}"))
    })?;
    Ok((driver, request_sender, path_connection))
}
