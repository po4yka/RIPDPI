use std::io;
use std::sync::Arc;

use bytes::Bytes;
use rustls::client::{EchConfig, EchMode};
use rustls::pki_types::EchConfigListBytes;
use rustls::RootCertStore;

use super::socket::{build_client_udp_socket, maybe_rebind_quic_endpoint};
use crate::config::MasqueConfig;
use crate::response::AttemptError;
use crate::tls::load_client_identity;
use crate::url::{parse_proxy_origin, resolve_proxy_socket_addr};

pub(super) async fn connect_h3_transport(
    config: &MasqueConfig,
    enable_datagram: bool,
) -> Result<
    (h3::client::Connection<h3_quinn::Connection, Bytes>, h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>),
    AttemptError,
> {
    let proxy_origin = parse_proxy_origin(config)?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let tls_config = if let Some(ech_config) = config.ech_config.as_ref() {
        let provider = rustls::crypto::aws_lc_rs::default_provider();
        let ech = EchConfig::new(
            EchConfigListBytes::from(ech_config.config_list.clone()),
            rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
        )
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid MASQUE ECH config: {error}")))?;
        rustls::ClientConfig::builder_with_provider(provider.into())
            .with_ech(EchMode::Enable(ech))
            .map_err(|error| io::Error::other(format!("failed to enable MASQUE ECH: {error}")))?
            .with_root_certificates(roots)
    } else {
        rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_root_certificates(roots)
    };
    let mut tls_config = if let Some((certificates, private_key)) = load_client_identity(config)? {
        tls_config
            .with_client_auth_cert(certificates, private_key)
            .map_err(|error| io::Error::other(format!("failed to configure MASQUE client identity: {error}")))?
    } else {
        tls_config.with_no_client_auth()
    };
    tls_config.alpn_protocols = vec![b"h3".to_vec()];

    let quic_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::other(format!("failed to build QUIC TLS config: {error}")))?,
    ));

    let proxy_addr = resolve_proxy_socket_addr(&proxy_origin)?;
    let socket = build_client_udp_socket(proxy_addr.is_ipv6(), config.quic_bind_low_port)
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
    builder.build(h3_quinn::Connection::new(connection)).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate HTTP/3: {error}")).into()
    })
}
