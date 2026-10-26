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

use state::{ResolverInner, ResolverTlsVerifiers};

/// The overall deadline of one exchange, in multiples of the per-stage timeout.
const EXCHANGE_DEADLINE_TIMEOUTS: u32 = 2;

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
        Self::with_health(
            endpoint,
            transport,
            DEFAULT_TIMEOUT,
            Vec::new(),
            None,
            ResolverTlsVerifiers { tls_verifier, dot_pin_verifier: None },
            connect_hooks,
        )
    }

    pub fn with_pinned_tls_verifier(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        tls_verifier: Arc<dyn ServerCertVerifier>,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_pinned_tls_verifier_and_connect_hooks(
            endpoint,
            transport,
            tls_verifier,
            EncryptedDnsConnectHooks::default(),
        )
    }

    pub fn with_pinned_tls_verifier_and_connect_hooks(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        tls_verifier: Arc<dyn ServerCertVerifier>,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health(
            endpoint,
            transport,
            DEFAULT_TIMEOUT,
            Vec::new(),
            None,
            ResolverTlsVerifiers { tls_verifier: Some(tls_verifier.clone()), dot_pin_verifier: Some(tls_verifier) },
            connect_hooks,
        )
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
        Self::with_health(endpoint, transport, timeout, tls_roots, None, ResolverTlsVerifiers::default(), connect_hooks)
    }

    pub(crate) fn with_health(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
        health: Option<crate::health::HealthRegistry>,
        tls_verifiers: ResolverTlsVerifiers,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health_in_scope(
            endpoint,
            transport,
            timeout,
            tls_roots,
            health,
            ResolverNetworkScope::global(),
            tls_verifiers,
            connect_hooks,
        )
    }

    pub(crate) fn with_health_in_scope(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
        health: Option<crate::health::HealthRegistry>,
        network_scope: ResolverNetworkScope,
        tls_verifiers: ResolverTlsVerifiers,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Ok(Self {
            inner: Arc::new(state::build_inner(
                endpoint,
                transport,
                timeout,
                tls_roots,
                health,
                network_scope,
                tls_verifiers,
                connect_hooks,
            )?),
        })
    }

    pub(crate) fn with_health_in_scope_default_tls(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
        health: Option<crate::health::HealthRegistry>,
        network_scope: ResolverNetworkScope,
        connect_hooks: EncryptedDnsConnectHooks,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health_in_scope(
            endpoint,
            transport,
            timeout,
            tls_roots,
            health,
            network_scope,
            ResolverTlsVerifiers::default(),
            connect_hooks,
        )
    }

    #[cfg(test)]
    pub(crate) fn ranked_bootstrap_ips_for_test(&self) -> Vec<std::net::IpAddr> {
        if let Some(health) = &self.inner.health {
            health.rank_bootstrap_ips_in_scope(&self.inner.network_scope, &self.inner.endpoint.bootstrap_ips)
        } else {
            self.inner.endpoint.bootstrap_ips.clone()
        }
    }

    pub fn endpoint(&self) -> &EncryptedDnsEndpoint {
        &self.inner.endpoint
    }

    /// Runs one query, bounded by one overall deadline of twice the configured
    /// timeout. Each stage (connect per bootstrap IP, TLS, exchange, a retry on a
    /// stale pooled stream) also has its own timeout, but without the overall
    /// deadline those stack to many times the configured value.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe: every protocol exchange owns its stream and drops it on
    /// cancellation instead of returning it to the pool; the overall deadline
    /// relies on the same property.
    pub async fn exchange_with_metadata(
        &self,
        query_bytes: &[u8],
    ) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let started = std::time::Instant::now();
        let deadline = self.inner.timeout.saturating_mul(EXCHANGE_DEADLINE_TIMEOUTS);
        let response_bytes =
            tokio::time::timeout(deadline, self.exchange_protocol(query_bytes)).await.map_err(|_| {
                EncryptedDnsError::Request(format!("encrypted DNS exchange timed out after {deadline:?}"))
            })??;

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

        let runtime = match self.inner.blocking_runtime.get() {
            Some(runtime) => runtime,
            None => {
                let runtime = Builder::new_current_thread().enable_all().build().map_err(|err| {
                    EncryptedDnsError::TaskJoin(format!("build blocking DNS exchange runtime: {err}"))
                })?;
                // A concurrent first call may win the race; its runtime is used and this one is dropped.
                self.inner.blocking_runtime.get_or_init(|| state::BlockingRuntime::new(runtime))
            }
        };
        runtime.get().block_on(self.exchange_with_metadata(query_bytes))
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

    fn requires_direct_tcp_connector(&self) -> bool {
        matches!(self.inner.transport, EncryptedDnsTransport::Direct)
            && self.inner.connect_hooks.requires_direct_tcp_connector()
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::*;

    #[tokio::test(start_paused = true)]
    async fn exchange_has_one_overall_deadline_across_bootstrap_ips() {
        let timeout = Duration::from_millis(100);
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("blackholed".to_string()),
            host: "dot.example".to_string(),
            port: 853,
            tls_server_name: Some("dot.example".to_string()),
            bootstrap_ips: (1..=4).map(|last| IpAddr::V4(Ipv4Addr::new(198, 18, 0, last))).collect(),
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        };
        // Every bootstrap IP is blackholed: the connect never completes.
        let hooks = EncryptedDnsConnectHooks::new()
            .with_direct_tcp_connector(|_, _| std::future::pending::<std::io::Result<std::net::TcpStream>>());
        let resolver = EncryptedDnsResolver::with_timeout_and_connect_hooks(
            endpoint,
            EncryptedDnsTransport::Direct,
            timeout,
            hooks,
        )
        .expect("resolver builds");

        let started = tokio::time::Instant::now();
        let error = resolver.exchange(&[0u8; 12]).await.expect_err("blackholed resolver must fail");

        assert_eq!(started.elapsed(), timeout * EXCHANGE_DEADLINE_TIMEOUTS, "error: {error}");
        assert!(error.to_string().contains("timed out"), "error: {error}");
    }
}
