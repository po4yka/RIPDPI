use std::sync::{Arc, Mutex};
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::CertificateDer;
use rustls::ClientConfig;
use tokio::runtime::Builder;
use tokio::sync::Mutex as AsyncMutex;

use crate::health::HealthRegistry;
use crate::transport::*;
use crate::types::*;

mod connection;
mod dnscrypt_transport;
mod doh;
mod doq;
mod dot;
mod tcp;

use connection::ConnectionPool;

#[derive(Debug)]
struct ResolverInner {
    endpoint: EncryptedDnsEndpoint,
    transport: EncryptedDnsTransport,
    timeout: Duration,
    #[cfg_attr(feature = "hickory-backend", allow(dead_code))]
    doh_client: Option<reqwest::Client>,
    connect_hooks: EncryptedDnsConnectHooks,
    dot_tls_config: Arc<ClientConfig>,
    /// Extra CA certificates for DoT/DoH TLS verification (e.g., self-signed test certs).
    dot_extra_roots: Vec<CertificateDer<'static>>,
    /// When true, DoT skips certificate verification (custom verifier was provided).
    dot_skip_verify: bool,
    /// Stored for `can_use_hickory()` fallback decisions. Only present when the
    /// hickory-backend feature is enabled; otherwise consumed only by the constructor.
    #[cfg(feature = "hickory-backend")]
    tls_roots: Vec<CertificateDer<'static>>,
    #[cfg(feature = "hickory-backend")]
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    dnscrypt_state: Mutex<Option<DnsCryptCachedCertificate>>,
    connection_pool: ConnectionPool,
    health: Option<HealthRegistry>,
    doq_endpoint: Option<quinn::Endpoint>,
    doq_connection: AsyncMutex<Option<quinn::Connection>>,
}

#[derive(Debug, Clone)]
pub struct EncryptedDnsResolver {
    inner: Arc<ResolverInner>,
}

impl EncryptedDnsResolver {
    pub fn new(endpoint: EncryptedDnsEndpoint, transport: EncryptedDnsTransport) -> Result<Self, EncryptedDnsError> {
        Self::with_timeout(endpoint, transport, DEFAULT_TIMEOUT)
    }

    pub fn with_connect_hooks(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_timeout_and_connect_hooks(endpoint, transport, DEFAULT_TIMEOUT, connect_hooks)
    }

    pub fn with_timeout(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_timeout_and_connect_hooks(endpoint, transport, timeout, EncryptedDnsConnectHooks::default())
    }

    pub fn with_timeout_and_connect_hooks(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_extra_tls_roots_and_connect_hooks(endpoint, transport, timeout, Vec::new(), connect_hooks)
    }

    pub fn with_tls_verifier(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_tls_verifier_and_connect_hooks(
            endpoint,
            transport,
            tls_verifier,
            EncryptedDnsConnectHooks::default(),
        )
    }

    pub fn with_tls_verifier_and_connect_hooks(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health(endpoint, transport, DEFAULT_TIMEOUT, Vec::new(), None, tls_verifier, connect_hooks)
    }

    #[doc(hidden)]
    pub fn with_extra_tls_roots(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_extra_tls_roots_and_connect_hooks(
            endpoint,
            transport,
            timeout,
            tls_roots,
            EncryptedDnsConnectHooks::default(),
        )
    }

    #[doc(hidden)]
    pub fn with_extra_tls_roots_and_connect_hooks(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health(endpoint, transport, timeout, tls_roots, None, None, connect_hooks)
    }

    pub(crate) fn with_health(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
        health: Option<crate::health::HealthRegistry>,
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        let normalized = normalize_endpoint(endpoint, &transport)?;
        let dot_tls_config = build_client_config(tls_verifier.as_ref(), &tls_roots);
        let doh_client = if normalized.protocol == EncryptedDnsProtocol::Doh
            && !(matches!(&transport, EncryptedDnsTransport::Direct) && connect_hooks.has_direct_tcp_connector())
        {
            Some(build_doh_client(
                &normalized,
                &transport,
                timeout,
                &tls_roots,
                health.as_ref(),
                tls_verifier.as_ref(),
            )?)
        } else {
            None
        };

        let doq_endpoint = if normalized.protocol == EncryptedDnsProtocol::Doq {
            let mut doq_tls = (*dot_tls_config).clone();
            doq_tls.alpn_protocols = vec![b"doq".to_vec()];
            let client_config = quinn::ClientConfig::new(Arc::new(
                quinn::crypto::rustls::QuicClientConfig::try_from(doq_tls)
                    .map_err(|e| EncryptedDnsError::Tls(format!("DoQ TLS config: {e}")))?,
            ));
            let mut endpoint = doq::build_doq_endpoint(&normalized, &connect_hooks)
                .map_err(|e| EncryptedDnsError::Request(format!("DoQ endpoint: {e}")))?;
            endpoint.set_default_client_config(client_config);
            Some(endpoint)
        } else {
            None
        };

        let dot_skip_verify = tls_verifier.is_some();
        let dot_extra_roots = tls_roots.clone();

        Ok(Self {
            inner: Arc::new(ResolverInner {
                endpoint: normalized,
                transport,
                timeout,
                doh_client,
                connect_hooks,
                dot_tls_config,
                dot_extra_roots,
                dot_skip_verify,
                #[cfg(feature = "hickory-backend")]
                tls_roots,
                #[cfg(feature = "hickory-backend")]
                tls_verifier,
                dnscrypt_state: Mutex::new(None),
                connection_pool: ConnectionPool::default(),
                health,
                doq_endpoint,
                doq_connection: AsyncMutex::new(None),
            }),
        })
    }

    pub fn endpoint(&self) -> &EncryptedDnsEndpoint {
        &self.inner.endpoint
    }

    pub async fn exchange_with_metadata(
        &self,
        query_bytes: &[u8],
    ) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let started = std::time::Instant::now();
        // Protocol dispatch: hickory-resolver handles DoH/DoT when available
        // (standard webpki roots, Direct transport). Falls back to manual
        // reqwest/tokio-rustls for custom TLS roots, custom verifiers, or
        // SOCKS5 transport. DNSCrypt is always manual (unsupported by hickory).
        let response_bytes = match self.inner.endpoint.protocol {
            EncryptedDnsProtocol::Doh => {
                #[cfg(feature = "hickory-backend")]
                {
                    if self.can_use_hickory() {
                        crate::hickory_backend::exchange_doh(&self.inner.endpoint, query_bytes, self.inner.timeout)
                            .await
                    } else {
                        self.exchange_doh(query_bytes).await
                    }
                }
                #[cfg(not(feature = "hickory-backend"))]
                {
                    self.exchange_doh(query_bytes).await
                }
            }
            EncryptedDnsProtocol::Dot => {
                #[cfg(feature = "hickory-backend")]
                {
                    if self.can_use_hickory() && matches!(self.inner.transport, EncryptedDnsTransport::Direct) {
                        crate::hickory_backend::exchange_dot(&self.inner.endpoint, query_bytes, self.inner.timeout)
                            .await
                    } else {
                        self.exchange_dot(query_bytes).await
                    }
                }
                #[cfg(not(feature = "hickory-backend"))]
                {
                    self.exchange_dot(query_bytes).await
                }
            }
            EncryptedDnsProtocol::DnsCrypt => self.exchange_dnscrypt(query_bytes).await,
            EncryptedDnsProtocol::Doq => self.exchange_doq(query_bytes).await,
        }?;

        let elapsed = started.elapsed();
        let protocol_str = self.inner.endpoint.protocol.as_str();
        let label = self.endpoint_label();
        metrics::histogram!(
            "ripdpi_dns_resolution_duration_seconds",
            "resolver_id" => label.clone(),
            "protocol" => protocol_str.to_string(),
        )
        .record(elapsed.as_secs_f64());

        Ok(EncryptedDnsExchangeSuccess {
            response_bytes,
            endpoint_label: label,
            latency_ms: elapsed.as_millis().try_into().unwrap_or(u64::MAX),
        })
    }

    pub async fn exchange(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        self.exchange_with_metadata(query_bytes).await.map(|success| success.response_bytes)
    }

    pub fn exchange_blocking_with_metadata(
        &self,
        query_bytes: &[u8],
    ) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        if tokio::runtime::Handle::try_current().is_ok() {
            return Err(EncryptedDnsError::TaskJoin(
                "blocking encrypted DNS exchange cannot run inside a Tokio runtime".to_string(),
            ));
        }

        let runtime = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|err| EncryptedDnsError::TaskJoin(format!("build blocking DNS exchange runtime: {err}")))?;
        runtime.block_on(self.exchange_with_metadata(query_bytes))
    }

    pub fn exchange_blocking(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        self.exchange_blocking_with_metadata(query_bytes).map(|success| success.response_bytes)
    }

    pub fn endpoint_label(&self) -> String {
        self.inner
            .endpoint
            .doh_url
            .clone()
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| format!("{}:{}", self.inner.endpoint.host, self.inner.endpoint.port))
    }

    /// Returns `true` when the hickory-resolver backend can handle this resolver's
    /// configuration. Falls back to the manual path when custom TLS roots, a custom
    /// certificate verifier, or a custom DoT TLS builder are configured, because
    /// hickory-resolver manages its own TLS stack and cannot honor those overrides.
    #[cfg(feature = "hickory-backend")]
    fn can_use_hickory(&self) -> bool {
        self.inner.tls_roots.is_empty()
            && self.inner.tls_verifier.is_none()
            && !self.uses_direct_tcp_connector()
            && !(matches!(self.inner.endpoint.protocol, EncryptedDnsProtocol::Dot)
                && self.inner.connect_hooks.has_dot_tls_connector_builder())
    }

    fn uses_direct_tcp_connector(&self) -> bool {
        matches!(self.inner.transport, EncryptedDnsTransport::Direct)
            && self.inner.connect_hooks.has_direct_tcp_connector()
    }
}
