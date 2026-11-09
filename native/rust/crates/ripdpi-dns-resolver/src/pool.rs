use std::collections::HashMap;
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use lru::LruCache;

use crate::health::HealthRegistry;
use crate::resolver::EncryptedDnsResolver;
use crate::types::{EncryptedDnsProtocol, ResolverNetworkScope, ResolverOracleObservation};

mod builder;
mod exchange;
mod fallback_order;
mod health_updates;
mod ranking;

#[cfg(test)]
mod tests;

pub use builder::ResolverPoolBuilder;

use fallback_order::FallbackEntry;

/// How long a DoQ resolver stays out of the candidate order after a connection-establishment
/// failure. RFC 9250 section 5.2: remember per server for a reasonable period.
const DOQ_DEMOTION_TTL: Duration = Duration::from_secs(3600);

struct PoolInner {
    resolvers: Vec<EncryptedDnsResolver>,
    /// Public endpoint labels (`EncryptedDnsResolver::endpoint_label`), possibly shared by several resolvers.
    labels: Vec<String>,
    /// Protocol-qualified key per resolver; keys health, fallback cache and DoQ demotion.
    keys: Vec<String>,
    health: HealthRegistry,
    network_scope: ResolverNetworkScope,
    rotation_counter: AtomicUsize,
    fallback_cache: Mutex<LruCache<String, FallbackEntry>>,
    /// DoQ resolvers (by pool key) demoted until the given instant after a live
    /// connection-establishment failure. Not shared across pool instances.
    doq_demoted_until: Mutex<HashMap<String, Instant>>,
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
/// The pool is cheap to clone: all state is behind an `Arc`.
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

    /// Records an oracle trust signal for every resolver whose public endpoint
    /// label equals `label`, in this pool's scope.
    ///
    /// This is intended for bootstrap, failover, or diagnostics paths that already
    /// compare multiple resolvers. The default steady-state exchange path remains
    /// a single-resolver request flow.
    pub fn record_oracle_observation(&self, label: &str, observation: ResolverOracleObservation) {
        for (idx, resolver_label) in self.inner.labels.iter().enumerate() {
            if resolver_label == label {
                self.inner.health.record_oracle_observation_in_scope(
                    &self.inner.network_scope,
                    &self.inner.keys[idx],
                    observation,
                );
            }
        }
    }

    /// Returns the number of resolvers in the pool.
    pub fn len(&self) -> usize {
        self.inner.resolvers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.resolvers.is_empty()
    }
}

impl PoolInner {
    /// Returns `true` while resolver `idx` is a DoQ resolver inside its demotion window.
    pub(crate) fn is_doq_demoted(&self, idx: usize) -> bool {
        self.resolvers[idx].endpoint().protocol == EncryptedDnsProtocol::Doq
            && self
                .doq_demoted_until
                .lock()
                .is_ok_and(|map| map.get(&self.keys[idx]).is_some_and(|until| *until > Instant::now()))
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
