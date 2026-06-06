use std::sync::{Arc, Mutex};
use std::time::Duration;

use rustls::ClientConfig;
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::CertificateDer;
use tokio::sync::Mutex as AsyncMutex;

use super::connection::ConnectionPool;
use super::doq;
use crate::health::HealthRegistry;
use crate::transport::{build_client_config, build_doh_client, normalize_endpoint};
use crate::types::{
    DnsCryptCachedCertificate, EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol,
    EncryptedDnsTransport, ResolverNetworkScope,
};

#[derive(Debug)]
pub(crate) struct ResolverInner {
    pub(crate) endpoint: EncryptedDnsEndpoint,
    pub(crate) transport: EncryptedDnsTransport,
    pub(crate) timeout: Duration,
    #[cfg_attr(feature = "hickory-backend", allow(dead_code))]
    pub(crate) doh_client: Option<reqwest::Client>,
    pub(crate) connect_hooks: EncryptedDnsConnectHooks,
    pub(crate) dot_tls_config: Arc<ClientConfig>,
    pub(crate) dot_extra_roots: Vec<CertificateDer<'static>>,
    pub(crate) dot_pin_verifier: Option<Arc<dyn ServerCertVerifier>>,
    pub(crate) dot_unpinned_custom_verifier_requested: bool,
    #[cfg(feature = "hickory-backend")]
    pub(crate) tls_roots: Vec<CertificateDer<'static>>,
    #[cfg(feature = "hickory-backend")]
    pub(crate) tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    pub(crate) dnscrypt_state: Mutex<Option<DnsCryptCachedCertificate>>,
    pub(crate) connection_pool: ConnectionPool,
    pub(crate) health: Option<HealthRegistry>,
    pub(crate) network_scope: ResolverNetworkScope,
    pub(crate) doq_endpoint: Option<quinn::Endpoint>,
    pub(crate) doq_connection: AsyncMutex<Option<quinn::Connection>>,
}

#[derive(Clone, Default)]
pub(crate) struct ResolverTlsVerifiers {
    pub(crate) tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    pub(crate) dot_pin_verifier: Option<Arc<dyn ServerCertVerifier>>,
}

pub(crate) fn build_inner(
    endpoint: EncryptedDnsEndpoint,
    transport: EncryptedDnsTransport,
    timeout: Duration,
    tls_roots: Vec<CertificateDer<'static>>,
    health: Option<HealthRegistry>,
    network_scope: ResolverNetworkScope,
    tls_verifiers: ResolverTlsVerifiers,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<ResolverInner, EncryptedDnsError> {
    let normalized = normalize_endpoint(endpoint, &transport)?;
    let dot_tls_config = build_client_config(tls_verifiers.tls_verifier.as_ref(), &tls_roots);
    let doh_client = build_doh_client_for_endpoint(
        &normalized,
        &transport,
        timeout,
        &tls_roots,
        health.as_ref(),
        &network_scope,
        tls_verifiers.tls_verifier.as_ref(),
        &connect_hooks,
    )?;
    let doq_endpoint = build_doq_endpoint_for_endpoint(&normalized, &connect_hooks, dot_tls_config.clone())?;
    let dot_extra_roots = tls_roots.clone();
    let dot_unpinned_custom_verifier_requested =
        tls_verifiers.tls_verifier.is_some() && tls_verifiers.dot_pin_verifier.is_none();

    Ok(ResolverInner {
        endpoint: normalized,
        transport,
        timeout,
        doh_client,
        connect_hooks,
        dot_tls_config,
        dot_extra_roots,
        dot_pin_verifier: tls_verifiers.dot_pin_verifier,
        dot_unpinned_custom_verifier_requested,
        #[cfg(feature = "hickory-backend")]
        tls_roots,
        #[cfg(feature = "hickory-backend")]
        tls_verifier: tls_verifiers.tls_verifier,
        dnscrypt_state: Mutex::new(None),
        connection_pool: ConnectionPool::default(),
        health,
        network_scope,
        doq_endpoint,
        doq_connection: AsyncMutex::new(None),
    })
}

fn build_doh_client_for_endpoint(
    endpoint: &EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
    timeout: Duration,
    tls_roots: &[CertificateDer<'static>],
    health: Option<&HealthRegistry>,
    network_scope: &ResolverNetworkScope,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    connect_hooks: &EncryptedDnsConnectHooks,
) -> Result<Option<reqwest::Client>, EncryptedDnsError> {
    if !matches!(endpoint.protocol, EncryptedDnsProtocol::Doh | EncryptedDnsProtocol::Odoh)
        || matches!(transport, EncryptedDnsTransport::Direct)
            && (connect_hooks.has_direct_tcp_connector() || connect_hooks.requires_direct_tcp_connector())
    {
        return Ok(None);
    }

    build_doh_client(endpoint, transport, timeout, tls_roots, health, network_scope, tls_verifier).map(Some)
}

fn build_doq_endpoint_for_endpoint(
    endpoint: &EncryptedDnsEndpoint,
    connect_hooks: &EncryptedDnsConnectHooks,
    dot_tls_config: Arc<ClientConfig>,
) -> Result<Option<quinn::Endpoint>, EncryptedDnsError> {
    if endpoint.protocol != EncryptedDnsProtocol::Doq {
        return Ok(None);
    }

    let mut doq_tls = (*dot_tls_config).clone();
    doq_tls.alpn_protocols = vec![b"doq".to_vec()];
    let client_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(doq_tls)
            .map_err(|err| EncryptedDnsError::Tls(format!("DoQ TLS config: {err}")))?,
    ));
    let mut endpoint = doq::build_doq_endpoint(endpoint, connect_hooks)
        .map_err(|err| EncryptedDnsError::Request(format!("DoQ endpoint: {err}")))?;
    endpoint.set_default_client_config(client_config);
    Ok(Some(endpoint))
}
