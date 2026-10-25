use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use rustls::ClientConfig;
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::CertificateDer;
use tokio::runtime::Runtime;
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
    /// Built on first use inside the runtime that drives it, and rebuilt when a query runs on another one.
    pub(crate) doq: AsyncMutex<Option<DoqState>>,
    /// Runtime reused by every blocking exchange, so state such as the DoQ endpoint outlives one call.
    pub(crate) blocking_runtime: OnceLock<BlockingRuntime>,
}

/// A DoQ endpoint and its cached connection. quinn spawns the endpoint and connection drivers on
/// the runtime that built them, so they work only while that runtime runs.
#[derive(Debug)]
pub(crate) struct DoqState {
    pub(crate) runtime: tokio::runtime::Id,
    pub(crate) endpoint: quinn::Endpoint,
    pub(crate) connection: Option<quinn::Connection>,
}

/// Owns the blocking-exchange runtime and shuts it down without blocking, so the resolver can drop anywhere.
#[derive(Debug)]
pub(crate) struct BlockingRuntime(Option<Runtime>);

impl BlockingRuntime {
    pub(crate) fn new(runtime: Runtime) -> Self {
        Self(Some(runtime))
    }

    pub(crate) fn get(&self) -> &Runtime {
        // Infallible: only `Drop` takes the runtime out, and nothing can borrow `self` after that.
        self.0.as_ref().expect("blocking runtime is present until drop")
    }
}

impl Drop for BlockingRuntime {
    fn drop(&mut self) {
        if let Some(runtime) = self.0.take() {
            runtime.shutdown_background();
        }
    }
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
    let dot_tls_config = build_client_config(tls_verifiers.tls_verifier.as_ref(), &tls_roots)?;
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
        doq: AsyncMutex::new(None),
        blocking_runtime: OnceLock::new(),
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

/// Must run inside a Tokio runtime: quinn registers the socket and spawns the endpoint driver on it.
pub(crate) fn build_doq_endpoint_for_endpoint(
    endpoint: &EncryptedDnsEndpoint,
    connect_hooks: &EncryptedDnsConnectHooks,
    dot_tls_config: &ClientConfig,
) -> Result<quinn::Endpoint, EncryptedDnsError> {
    let mut doq_tls = dot_tls_config.clone();
    doq_tls.alpn_protocols = vec![b"doq".to_vec()];
    let client_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(doq_tls)
            .map_err(|err| EncryptedDnsError::Tls(format!("DoQ TLS config: {err}")))?,
    ));
    let mut endpoint = doq::build_doq_endpoint(endpoint, connect_hooks)
        .map_err(|err| EncryptedDnsError::Request(format!("DoQ endpoint: {err}")))?;
    endpoint.set_default_client_config(client_config);
    Ok(endpoint)
}
