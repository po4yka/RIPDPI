use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, Mutex};

use lru::LruCache;

use crate::health::HealthRegistry;
use crate::resolver::EncryptedDnsResolver;
use crate::types::{ResolverNetworkScope, ResolverOracleObservation};

mod builder;
mod exchange;
mod fallback_order;
mod health_updates;
mod ranking;

#[cfg(test)]
mod tests;

pub use builder::ResolverPoolBuilder;

use fallback_order::FallbackEntry;

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
}

impl std::fmt::Debug for ResolverPool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResolverPool")
            .field("resolvers", &self.inner.resolvers.len())
            .field("network_scope", &self.inner.network_scope)
            .finish()
    }
}
