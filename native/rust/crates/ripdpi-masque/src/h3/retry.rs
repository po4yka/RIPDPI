use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use super::socket::build_client_udp_socket;
use super::transport::{H3_KEEP_ALIVE_INTERVAL, H3_MTU_DISCOVERY_UPPER_BOUND, h3_tls_config};
use crate::config::MasqueConfig;
use crate::response::AttemptError;
use crate::tls::apply_h3_root_certificate;
use crate::url::{ProxyOrigin, resolve_proxy_socket_addr};

/// # Cancel safety:
/// cancel-safe: dropping the future closes the attempt's endpoint and transport;
/// retry evidence belongs to this one attempt and cannot authorize a later call.
pub(super) async fn connect_quic_with_ech_retry(
    config: &MasqueConfig,
    origin: &ProxyOrigin,
    terminal: Arc<Mutex<bool>>,
) -> Result<(quinn::Endpoint, quinn::Connection, SocketAddr), AttemptError> {
    let address = resolve_proxy_socket_addr(config, origin).await?;
    let mut current = config.clone();
    for attempt in 0..2 {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        apply_h3_root_certificate(&mut roots, &current)?;
        let tls = h3_tls_config(&current, roots)?;
        let evidence = Arc::new(Mutex::new(None));
        let captured = evidence.clone();
        let consumed = terminal.clone();
        let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls)
            .map_err(|error| io::Error::other(format!("failed to build QUIC TLS config: {error}")))?
            .with_tls_error_callback(Arc::new(move |error| {
                if matches!(
                    error,
                    rustls::Error::PeerIncompatible(rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(_))
                ) {
                    if let Ok(mut terminal) = consumed.lock() {
                        *terminal = true;
                    }
                    if let Ok(mut slot) = captured.lock() {
                        *slot = Some(error.clone());
                    }
                }
            }));
        let mut quic = quinn::ClientConfig::new(Arc::new(crypto));
        let mut transport = quinn::TransportConfig::default();
        let mut mtu = quinn::MtuDiscoveryConfig::default();
        mtu.upper_bound(H3_MTU_DISCOVERY_UPPER_BOUND);
        transport.mtu_discovery_config(Some(mtu));
        transport.keep_alive_interval(Some(H3_KEEP_ALIVE_INTERVAL));
        quic.transport_config(Arc::new(transport));
        let socket = build_client_udp_socket(address.is_ipv6(), current.quic_bind_low_port, current.socket_protection)?;
        let mut endpoint =
            quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?;
        endpoint.set_default_client_config(quic);
        let connecting = endpoint.connect(address, &origin.host).map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC connect failed: {error}"))
        })?;
        match connecting.await {
            Ok(connection) => return Ok((endpoint, connection, address)),
            Err(error) => {
                let rejection = evidence.lock().ok().and_then(|mut slot| slot.take());
                let configs = rejection.as_ref().and_then(ripdpi_tls_profiles::ech::retry::rustls_ech_retry_configs);
                if attempt == 0
                    && current.ech_config.is_some()
                    && let Some(configs) = configs
                    && let Some(public_name) = ripdpi_ech_dns::ech_public_name(&configs)
                {
                    current.ech_config = Some(
                        ripdpi_tls_profiles::OutboundEchConfig::new(public_name, configs)
                            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?,
                    );
                    continue;
                }
                return Err(io::Error::new(
                    if rejection.is_some() {
                        io::ErrorKind::PermissionDenied
                    } else {
                        io::ErrorKind::ConnectionRefused
                    },
                    format!("QUIC handshake failed: {error}"),
                )
                .into());
            }
        }
    }
    Err(io::Error::other("ECH retry exhausted").into())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// # Cancel safety:
    /// NOT cancel-safe: the complete fixture exchange must finish; test runtime
    /// teardown closes the endpoint and the fixture owns its server shutdown.
    #[tokio::test]
    async fn quic_certificate_failure_preserves_typed_error_without_retry_evidence() {
        let fixture = local_network_fixture::MasqueH3ConnectUdpFixture::start().expect("QUIC fixture");
        let mut tls = rustls::ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .expect("TLS13")
            .with_root_certificates(rustls::RootCertStore::empty())
            .with_no_client_auth();
        tls.alpn_protocols = vec![b"h3".to_vec()];
        let observed = Arc::new(Mutex::new(None));
        let captured = observed.clone();
        let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls).expect("QUIC TLS").with_tls_error_callback(
            Arc::new(move |error| {
                *captured.lock().expect("typed error") = Some(error.clone());
            }),
        );
        let mut endpoint = quinn::Endpoint::client("127.0.0.1:0".parse().expect("address")).expect("endpoint");
        endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(crypto)));
        let result = tokio::time::timeout(
            std::time::Duration::from_secs(3),
            endpoint.connect(fixture.proxy_address(), "localhost").expect("connect"),
        )
        .await
        .expect("bounded handshake");
        assert!(result.is_err(), "untrusted certificate cannot establish a session");
        let observed = observed.lock().expect("typed error");
        let error = observed.as_ref().expect("TLS error captured before QUIC conversion");
        assert!(matches!(error, rustls::Error::InvalidCertificate(_)));
        assert!(ripdpi_tls_profiles::ech::retry::rustls_ech_retry_configs(error).is_none());
        assert!(fixture.observed_requests().is_empty());
    }
}
