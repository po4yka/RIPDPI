use std::num::NonZeroUsize;
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use lru::LruCache;
use rustls::pki_types::CertificateDer;

use super::{PoolInner, ResolverPool};
use crate::health::HealthRegistry;
use crate::resolver::EncryptedDnsResolver;
use crate::transport::DEFAULT_TIMEOUT;
use crate::types::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsTransport, ResolverNetworkScope,
};

const DEFAULT_FALLBACK_CACHE_SIZE: usize = 8;
const DEFAULT_HEALTH_HALF_LIFE: Duration = Duration::from_secs(60);

/// Builder for `ResolverPool`.
pub struct ResolverPoolBuilder {
    endpoints: Vec<(EncryptedDnsEndpoint, EncryptedDnsTransport)>,
    timeout: Duration,
    health_half_life: Duration,
    fallback_cache_size: usize,
    tls_roots: Vec<CertificateDer<'static>>,
    health_registry: Option<HealthRegistry>,
    network_scope: ResolverNetworkScope,
}

impl Default for ResolverPoolBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl ResolverPoolBuilder {
    pub fn new() -> Self {
        Self {
            endpoints: Vec::new(),
            timeout: DEFAULT_TIMEOUT,
            health_half_life: DEFAULT_HEALTH_HALF_LIFE,
            fallback_cache_size: DEFAULT_FALLBACK_CACHE_SIZE,
            tls_roots: Vec::new(),
            health_registry: None,
            network_scope: ResolverNetworkScope::global(),
        }
    }

    pub fn add_endpoint(mut self, endpoint: EncryptedDnsEndpoint, transport: EncryptedDnsTransport) -> Self {
        self.endpoints.push((endpoint, transport));
        self
    }

    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    pub fn health_half_life(mut self, half_life: Duration) -> Self {
        self.health_half_life = half_life;
        self
    }

    pub fn fallback_cache_size(mut self, size: usize) -> Self {
        self.fallback_cache_size = size;
        self
    }

    pub fn tls_roots(mut self, roots: Vec<CertificateDer<'static>>) -> Self {
        self.tls_roots = roots;
        self
    }

    /// Selects the opaque network scope used to partition resolver memory.
    ///
    /// This crate only owns the scope token itself. Platform-specific identity
    /// plumbing such as SSID/BSSID or operator ID remains outside this crate.
    pub fn network_scope(mut self, scope: ResolverNetworkScope) -> Self {
        self.network_scope = scope;
        self
    }

    /// Provide a pre-existing `HealthRegistry` to share observations across pool recreations.
    ///
    /// When a pool is dropped and a new one built with the same registry and the same scope,
    /// the new pool starts with all previous health data intact.
    pub fn health_registry(mut self, registry: HealthRegistry) -> Self {
        self.health_registry = Some(registry);
        self
    }

    pub fn build(self) -> Result<ResolverPool, EncryptedDnsError> {
        let health = self.health_registry.unwrap_or_else(|| HealthRegistry::new(self.health_half_life));
        let cache_size = NonZeroUsize::new(self.fallback_cache_size.max(1)).unwrap_or(NonZeroUsize::new(8).unwrap());
        let (resolvers, labels) =
            build_resolvers(self.endpoints, self.timeout, self.tls_roots, health.clone(), self.network_scope.clone())?;

        Ok(ResolverPool {
            inner: Arc::new(PoolInner {
                resolvers,
                labels,
                health,
                network_scope: self.network_scope,
                rotation_counter: AtomicUsize::new(0),
                fallback_cache: Mutex::new(LruCache::new(cache_size)),
                doq_demoted_scopes: Mutex::new(std::collections::HashSet::new()),
            }),
        })
    }
}

fn build_resolvers(
    endpoints: Vec<(EncryptedDnsEndpoint, EncryptedDnsTransport)>,
    timeout: Duration,
    tls_roots: Vec<CertificateDer<'static>>,
    health: HealthRegistry,
    network_scope: ResolverNetworkScope,
) -> Result<(Vec<EncryptedDnsResolver>, Vec<String>), EncryptedDnsError> {
    let mut resolvers = Vec::with_capacity(endpoints.len());
    let mut labels = Vec::with_capacity(endpoints.len());

    for (endpoint, transport) in endpoints {
        let resolver = instantiate_resolver(
            endpoint,
            transport,
            timeout,
            tls_roots.clone(),
            health.clone(),
            network_scope.clone(),
        )?;
        labels.push(resolver.endpoint_label());
        resolvers.push(resolver);
    }

    Ok((resolvers, labels))
}

fn instantiate_resolver(
    endpoint: EncryptedDnsEndpoint,
    transport: EncryptedDnsTransport,
    timeout: Duration,
    tls_roots: Vec<CertificateDer<'static>>,
    health: HealthRegistry,
    network_scope: ResolverNetworkScope,
) -> Result<EncryptedDnsResolver, EncryptedDnsError> {
    EncryptedDnsResolver::with_health_in_scope_default_tls(
        endpoint,
        transport,
        timeout,
        tls_roots,
        Some(health),
        network_scope,
        EncryptedDnsConnectHooks::default(),
    )
}
