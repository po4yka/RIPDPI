use std::sync::Arc;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::CertificateDer;
use tokio::runtime::Builder;

use crate::transport::DEFAULT_TIMEOUT;
use crate::types::*;

mod connection;
mod dispatch;
mod dnscrypt_transport;
mod doh;
mod doq;
mod dot;
mod state;
mod tcp;

use state::ResolverInner;

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
        Ok(Self {
            inner: Arc::new(state::build_inner(
                endpoint,
                transport,
                timeout,
                tls_roots,
                health,
                tls_verifier,
                connect_hooks,
            )?),
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
        let response_bytes = self.exchange_protocol(query_bytes).await?;

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

    fn uses_direct_tcp_connector(&self) -> bool {
        matches!(self.inner.transport, EncryptedDnsTransport::Direct)
            && self.inner.connect_hooks.has_direct_tcp_connector()
    }
}
