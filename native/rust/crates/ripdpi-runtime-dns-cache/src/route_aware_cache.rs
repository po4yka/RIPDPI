use std::net::IpAddr;
use std::num::NonZeroUsize;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use lru::LruCache;

/// Which resolver path produced this DNS answer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolverPath {
    /// Resolved directly without a proxy (bootstrap path).
    BootstrapDirect,
    /// Resolved via an upstream proxy (bootstrap path).
    BootstrapProxy,
    /// Resolved via DNS-over-HTTPS sent through the VPN tunnel.
    TunneledDoh,
    /// Resolved via the system resolver (last-resort fallback).
    SystemFallback,
}

/// The routing decision associated with a DNS answer.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum RouteDecision {
    /// Traffic goes directly to the destination.
    Direct,
    /// Traffic is routed through a proxy.
    Proxy,
    /// Traffic is blocked.
    Block,
}

/// Cache lookup key that binds domain + qtype + route together.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CacheKey {
    pub domain: String,
    pub qtype: u16,
    pub route: RouteDecision,
}

/// A fully-annotated DNS resolution result stored in the route-aware cache.
#[derive(Debug, Clone)]
pub struct ResolvedAnswer {
    pub domain: String,
    pub qtype: u16,
    pub ips: Vec<IpAddr>,
    pub resolver_path: ResolverPath,
    pub route_decision: RouteDecision,
    pub expiry: Instant,
    pub policy_version: u32,
}

/// Result returned by [`RouteAwareDnsCache::lookup`].
#[derive(Debug)]
pub enum LookupResult {
    /// A valid, route-matching entry was found.
    Hit(ResolvedAnswer),
    /// No entry exists for this key at all.
    Miss,
    /// An entry exists but its route does not match; caller must re-resolve.
    Reresolve,
    /// An entry exists but the policy prohibits returning it; treat as an error.
    FailClosed,
}

/// Policy controlling whether answers from one route may be reused for another.
#[derive(Debug, Clone)]
pub struct CrossRouteReusePolicy {
    /// When `true`, a cache entry keyed under any `RouteDecision` may satisfy a
    /// lookup for a different `RouteDecision` (explicit opt-in).
    pub cross_route_reuse_allowed: bool,
}

const DEFAULT_CAPACITY: usize = 4096;

struct Inner {
    lru: LruCache<CacheKey, ResolvedAnswer>,
}

/// A DNS cache whose keys encode the route decision, preventing accidental
/// cross-route reuse of DNS answers.
pub struct RouteAwareDnsCache {
    policy: CrossRouteReusePolicy,
    inner: Mutex<Inner>,
}

impl RouteAwareDnsCache {
    pub fn new(policy: CrossRouteReusePolicy) -> Self {
        let cap = NonZeroUsize::new(DEFAULT_CAPACITY).unwrap();
        Self { policy, inner: Mutex::new(Inner { lru: LruCache::new(cap) }) }
    }

    /// Insert a resolved answer under the given key.
    pub fn insert(&self, key: CacheKey, answer: ResolvedAnswer) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.lru.put(key, answer);
        }
    }

    /// Look up a key. Returns:
    /// - `Hit` if a non-expired, route-matching entry exists (or cross-route reuse is allowed).
    /// - `Reresolve` if an entry exists under a different route and reuse is not permitted.
    /// - `Miss` if no entry exists at all (any route).
    pub fn lookup(&self, key: &CacheKey) -> LookupResult {
        let Ok(mut inner) = self.inner.lock() else { return LookupResult::FailClosed };

        let now = Instant::now();

        // Fast path: exact key match.
        if let Some(entry) = inner.lru.get(key) {
            if entry.expiry > now {
                return LookupResult::Hit(entry.clone());
            }
            // Expired — treat as miss (evict below).
            inner.lru.pop(key);
            return LookupResult::Miss;
        }

        // No exact match — check whether an entry exists under a different route.
        if self.policy.cross_route_reuse_allowed {
            // Search all other routes for this domain+qtype.
            let candidates: Vec<RouteDecision> = [RouteDecision::Direct, RouteDecision::Proxy, RouteDecision::Block]
                .into_iter()
                .filter(|r| r != &key.route)
                .collect();

            for alt_route in candidates {
                let alt_key = CacheKey { domain: key.domain.clone(), qtype: key.qtype, route: alt_route };
                if let Some(entry) = inner.lru.get(&alt_key)
                    && entry.expiry > now
                {
                    return LookupResult::Hit(entry.clone());
                }
            }
        } else {
            // Reuse NOT allowed: check whether a foreign-route entry exists so we can
            // signal Reresolve rather than a plain Miss.
            let candidates: Vec<RouteDecision> = [RouteDecision::Direct, RouteDecision::Proxy, RouteDecision::Block]
                .into_iter()
                .filter(|r| r != &key.route)
                .collect();

            for alt_route in candidates {
                let alt_key = CacheKey { domain: key.domain.clone(), qtype: key.qtype, route: alt_route };
                if let Some(entry) = inner.lru.peek(&alt_key)
                    && entry.expiry > now
                {
                    return LookupResult::Reresolve;
                }
            }
        }

        LookupResult::Miss
    }
}

/// Policy for capping the TTL of negative (NXDOMAIN / SERVFAIL) cache entries.
#[derive(Debug, Clone)]
pub struct NegativeCachePolicy {
    /// Maximum TTL for negative entries.
    pub max_ttl: Duration,
    /// Minimum TTL for negative entries (prevents zero-TTL storms).
    pub min_ttl: Duration,
    /// The resolver path that produced this negative answer.
    pub resolver_path: ResolverPath,
}

impl Default for NegativeCachePolicy {
    fn default() -> Self {
        Self {
            max_ttl: Duration::from_secs(30),
            min_ttl: Duration::from_secs(1),
            resolver_path: ResolverPath::BootstrapDirect,
        }
    }
}

impl NegativeCachePolicy {
    /// Clamp `answer_ttl` into `[min_ttl, max_ttl]`.
    pub fn cap_ttl(&self, answer_ttl: Duration) -> Duration {
        answer_ttl.clamp(self.min_ttl, self.max_ttl)
    }
}
