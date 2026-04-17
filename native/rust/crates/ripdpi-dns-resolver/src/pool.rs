use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use lru::LruCache;
use rustls::pki_types::CertificateDer;

use crate::health::HealthRegistry;
use crate::resolver::EncryptedDnsResolver;
use crate::transport::DEFAULT_TIMEOUT;
use crate::types::{
    EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsTransport,
    ResolverNetworkScope, ResolverOracleObservation,
};

const DEFAULT_FALLBACK_CACHE_SIZE: usize = 8;
const DEFAULT_HEALTH_HALF_LIFE: Duration = Duration::from_secs(60);

struct FallbackEntry {
    last_success: Instant,
}

struct PoolInner {
    resolvers: Vec<EncryptedDnsResolver>,
    labels: Vec<String>,
    health: HealthRegistry,
    network_scope: ResolverNetworkScope,
    rotation_counter: AtomicUsize,
    fallback_cache: Mutex<LruCache<String, FallbackEntry>>,
}

/// Multi-endpoint encrypted DNS resolver pool with health-weighted rotation and fallback memory.
///
/// The pool tries endpoints in order of composite health score (transport success,
/// latency, and oracle trust when available). On cold start (no health data), it
/// consults the fallback cache to prefer a recently successful endpoint within
/// the current network scope. A round-robin injection ensures that endpoints
/// beyond rank-1 are periodically re-evaluated rather than being permanently
/// starved. Steady-state exchange remains sequential and single-path; this type
/// does not hedge or query multiple resolvers unless rank-0 fails.
///
/// The pool is cheap to clone — all state is behind an `Arc`.
#[derive(Clone)]
pub struct ResolverPool {
    inner: Arc<PoolInner>,
}

impl ResolverPool {
    pub fn builder() -> ResolverPoolBuilder {
        ResolverPoolBuilder::new()
    }

    /// Returns the shared `HealthRegistry` used by this pool.
    ///
    /// Callers can pass this to a future pool via `ResolverPoolBuilder::health_registry` to
    /// preserve health history across pool recreations for the same network scope.
    pub fn health_registry(&self) -> &HealthRegistry {
        &self.inner.health
    }

    /// Returns the opaque network scope token used to partition resolver memory.
    pub fn network_scope(&self) -> &ResolverNetworkScope {
        &self.inner.network_scope
    }

    /// Records an oracle trust signal for a specific resolver label in this pool's scope.
    ///
    /// This is intended for bootstrap, failover, or diagnostics paths that already
    /// compare multiple resolvers. The default steady-state exchange path remains
    /// a single-resolver request flow.
    pub fn record_oracle_observation(&self, label: &str, observation: ResolverOracleObservation) {
        self.inner.health.record_oracle_observation_in_scope(&self.inner.network_scope, label, observation);
    }

    /// Returns the number of resolvers in the pool.
    pub fn len(&self) -> usize {
        self.inner.resolvers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.resolvers.is_empty()
    }

    fn try_order(&self) -> Vec<usize> {
        let n = self.inner.resolvers.len();
        if n == 0 {
            return vec![];
        }

        let label_refs: Vec<&str> = self.inner.labels.iter().map(String::as_str).collect();
        let mut ranked = self.inner.health.rank_indices_in_scope(&self.inner.network_scope, &label_refs);

        // Cold start: if the top-ranked endpoint has no observations, prefer a cached success.
        if self.inner.health.observation_count_in_scope(&self.inner.network_scope, &self.inner.labels[ranked[0]]) == 0 {
            if let Ok(cache) = self.inner.fallback_cache.lock() {
                let best = self
                    .inner
                    .labels
                    .iter()
                    .enumerate()
                    .filter_map(|(i, label)| {
                        cache.peek(&fallback_key(&self.inner.network_scope, label)).map(|entry| (i, entry.last_success))
                    })
                    .max_by_key(|(_, timestamp)| *timestamp);
                if let Some((cached_idx, _)) = best {
                    if ranked[0] != cached_idx {
                        ranked.retain(|&i| i != cached_idx);
                        ranked.insert(0, cached_idx);
                    }
                }
            }
        }

        // Round-robin: inject a candidate at position 1 every N calls so that lower-ranked
        // endpoints are occasionally re-evaluated. This only adds latency if rank-0 fails.
        if n > 1 {
            let counter = self.inner.rotation_counter.fetch_add(1, Ordering::Relaxed);
            let rr = counter % n;
            let already_top2 = ranked.first().copied() == Some(rr) || ranked.get(1).copied() == Some(rr);
            if !already_top2 {
                ranked.retain(|&i| i != rr);
                ranked.insert(1.min(ranked.len()), rr);
            }
        }

        ranked
    }

    fn record_success(&self, idx: usize, success: &EncryptedDnsExchangeSuccess) {
        let label = &self.inner.labels[idx];
        self.inner.health.record_endpoint_outcome_in_scope(&self.inner.network_scope, label, true, success.latency_ms);
        if let Ok(mut cache) = self.inner.fallback_cache.lock() {
            cache.put(fallback_key(&self.inner.network_scope, label), FallbackEntry { last_success: Instant::now() });
        }
    }

    fn record_failure(&self, idx: usize, error: &EncryptedDnsError) {
        let label = &self.inner.labels[idx];
        if error.kind() == EncryptedDnsErrorKind::SniBlocked {
            self.inner.health.record_sni_blocked_in_scope(&self.inner.network_scope, label);
        } else {
            let timeout_ms = DEFAULT_TIMEOUT.as_millis().try_into().unwrap_or(u64::MAX);
            self.inner.health.record_endpoint_outcome_in_scope(&self.inner.network_scope, label, false, timeout_ms);
        }
    }

    /// Tries each resolver in health-ranked order, returning the first successful response.
    pub async fn exchange(&self, query: &[u8]) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let order = self.try_order();
        if order.is_empty() {
            return Err(EncryptedDnsError::InvalidEndpoint("resolver pool is empty".to_string()));
        }
        let mut last_error = EncryptedDnsError::InvalidEndpoint("no resolvers tried".to_string());
        for idx in order {
            match self.inner.resolvers[idx].exchange_with_metadata(query).await {
                Ok(success) => {
                    self.record_success(idx, &success);
                    return Ok(success);
                }
                Err(err) => {
                    self.record_failure(idx, &err);
                    last_error = err;
                }
            }
        }
        Err(last_error)
    }

    /// Blocking variant of `exchange`.
    pub fn exchange_blocking(&self, query: &[u8]) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let order = self.try_order();
        if order.is_empty() {
            return Err(EncryptedDnsError::InvalidEndpoint("resolver pool is empty".to_string()));
        }
        let mut last_error = EncryptedDnsError::InvalidEndpoint("no resolvers tried".to_string());
        for idx in order {
            match self.inner.resolvers[idx].exchange_blocking_with_metadata(query) {
                Ok(success) => {
                    self.record_success(idx, &success);
                    return Ok(success);
                }
                Err(err) => {
                    self.record_failure(idx, &err);
                    last_error = err;
                }
            }
        }
        Err(last_error)
    }
}

impl std::fmt::Debug for ResolverPool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResolverPool")
            .field("resolvers", &self.inner.resolvers.len())
            .field("network_scope", &self.inner.network_scope)
            .finish()
    }
}

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

        let mut resolvers = Vec::with_capacity(self.endpoints.len());
        let mut labels = Vec::with_capacity(self.endpoints.len());

        for (endpoint, transport) in self.endpoints {
            let resolver = EncryptedDnsResolver::with_health(
                endpoint,
                transport,
                self.timeout,
                self.tls_roots.clone(),
                Some(health.clone()),
                None,
                crate::types::EncryptedDnsConnectHooks::default(),
            )?;
            labels.push(resolver.endpoint_label());
            resolvers.push(resolver);
        }

        Ok(ResolverPool {
            inner: Arc::new(PoolInner {
                resolvers,
                labels,
                health,
                network_scope: self.network_scope,
                rotation_counter: AtomicUsize::new(0),
                fallback_cache: Mutex::new(LruCache::new(cache_size)),
            }),
        })
    }
}

fn fallback_key(scope: &ResolverNetworkScope, label: &str) -> String {
    format!("{}::{label}", scope.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::EncryptedDnsProtocol;
    use std::net::{IpAddr, Ipv4Addr};

    fn google_doh_endpoint() -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("google".to_string()),
            host: "dns.google".to_string(),
            port: 0,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4))],
            doh_url: Some("https://dns.google/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    fn cloudflare_doh_endpoint() -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("cloudflare".to_string()),
            host: "cloudflare-dns.com".to_string(),
            port: 0,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
            doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    #[test]
    fn builder_creates_pool_with_correct_length() {
        let pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .build()
            .unwrap();
        assert_eq!(pool.len(), 2);
        assert!(!pool.is_empty());
    }

    #[test]
    fn empty_pool_returns_error_from_exchange_blocking() {
        let pool = ResolverPool::builder().build().unwrap();
        assert!(pool.is_empty());
        let query = b"\x00\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00";
        assert!(pool.exchange_blocking(query).is_err());
    }

    #[test]
    fn shared_health_registry_has_same_arc_identity() {
        let pool =
            ResolverPool::builder().add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct).build().unwrap();
        pool.health_registry().record_endpoint_outcome("test", true, 100);
        assert_eq!(pool.health_registry().observation_count("test"), 1);
    }

    #[test]
    fn external_health_registry_is_used_when_provided() {
        let shared = HealthRegistry::new(Duration::from_secs(60));
        shared.record_endpoint_outcome("https://dns.google/dns-query", true, 30);
        let pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .health_registry(shared.clone())
            .build()
            .unwrap();
        assert_eq!(pool.health_registry().observation_count("https://dns.google/dns-query"), 1);
    }

    #[test]
    fn try_order_prefers_cold_start_fallback_when_no_health_data() {
        let pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .build()
            .unwrap();

        {
            let cf_label = &pool.inner.labels[1];
            if let Ok(mut cache) = pool.inner.fallback_cache.lock() {
                cache.put(fallback_key(pool.network_scope(), cf_label), FallbackEntry { last_success: Instant::now() });
            }
        }

        let order = pool.try_order();
        assert_eq!(order[0], 1, "cached resolver should be tried first on cold start");
    }

    #[test]
    fn try_order_prefers_healthier_endpoint_over_fallback() {
        let pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .build()
            .unwrap();

        let g_label = &pool.inner.labels[0];
        for _ in 0..50 {
            pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), g_label, true, 20);
        }
        let cf_label = &pool.inner.labels[1];
        if let Ok(mut cache) = pool.inner.fallback_cache.lock() {
            cache.put(fallback_key(pool.network_scope(), cf_label), FallbackEntry { last_success: Instant::now() });
        }

        let order = pool.try_order();
        assert_eq!(order[0], 0, "healthy endpoint should be tried first");
    }

    #[test]
    fn oracle_quarantine_changes_pool_selection_order() {
        let pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .network_scope(ResolverNetworkScope::new("wifi:lab"))
            .build()
            .unwrap();

        let google_label = pool.inner.labels[0].clone();
        let cloudflare_label = pool.inner.labels[1].clone();
        for _ in 0..20 {
            pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), &google_label, true, 15);
            pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), &cloudflare_label, true, 30);
        }

        pool.record_oracle_observation(&google_label, ResolverOracleObservation::Disagreement);
        pool.record_oracle_observation(&google_label, ResolverOracleObservation::Poisoned);

        let order = pool.try_order();
        assert_eq!(order[0], 1, "quarantined resolver should not dominate rank 0");
    }

    #[test]
    fn ranking_persists_only_within_the_same_network_scope() {
        let shared = HealthRegistry::new(Duration::from_secs(60));
        let wifi = ResolverNetworkScope::new("wifi:alpha");
        let cellular = ResolverNetworkScope::new("cell:beta");
        let google_label = "https://dns.google/dns-query";
        let cloudflare_label = "https://cloudflare-dns.com/dns-query";

        for _ in 0..30 {
            shared.record_endpoint_outcome_in_scope(&wifi, google_label, false, 4000);
            shared.record_endpoint_outcome_in_scope(&wifi, cloudflare_label, true, 20);
        }

        let wifi_pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .health_registry(shared.clone())
            .network_scope(wifi.clone())
            .build()
            .unwrap();
        assert_eq!(wifi_pool.try_order()[0], 1);

        let rebuilt_wifi_pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .health_registry(shared.clone())
            .network_scope(wifi)
            .build()
            .unwrap();
        assert_eq!(rebuilt_wifi_pool.try_order()[0], 1);

        let cellular_pool = ResolverPool::builder()
            .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
            .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
            .health_registry(shared)
            .network_scope(cellular)
            .build()
            .unwrap();
        assert_eq!(cellular_pool.try_order()[0], 0, "different scope should not inherit wifi ranking");
    }
}
