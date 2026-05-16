use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use ripdpi_runtime_dns_cache::{
    CacheKey, CrossRouteReusePolicy, LookupResult, ResolvedAnswer, ResolverPath, RouteAwareDnsCache, RouteDecision,
};

fn make_answer(route: RouteDecision) -> ResolvedAnswer {
    ResolvedAnswer {
        domain: "blocked.com".to_string(),
        qtype: 1,
        ips: vec![IpAddr::V4(Ipv4Addr::new(5, 6, 7, 8))],
        resolver_path: ResolverPath::BootstrapProxy,
        route_decision: route,
        expiry: Instant::now() + Duration::from_secs(300),
        policy_version: 1,
    }
}

/// A cross-route mismatch must return Reresolve or FailClosed — never Hit with the foreign entry.
#[test]
fn mismatch_returns_reresolve_or_fail_closed() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);

    // Insert under Direct
    let direct_key = CacheKey { domain: "blocked.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    cache.insert(direct_key, make_answer(RouteDecision::Direct));

    // Lookup under Proxy — must not silently return the Direct entry
    let proxy_key = CacheKey { domain: "blocked.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    let result = cache.lookup(&proxy_key);

    match result {
        LookupResult::Hit(_) => {
            panic!("route mismatch must NOT silently return a Hit with the foreign entry")
        }
        LookupResult::Reresolve | LookupResult::FailClosed | LookupResult::Miss => {
            // all of these are acceptable fail-safe outcomes
        }
    }
}

/// Confirmed: the mismatch result is specifically Reresolve (not Miss), so callers know to retry.
#[test]
fn mismatch_is_reresolve_not_miss() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);

    let direct_key = CacheKey { domain: "check.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    cache.insert(direct_key, make_answer(RouteDecision::Direct));

    let proxy_key = CacheKey { domain: "check.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    let result = cache.lookup(&proxy_key);

    assert!(matches!(result, LookupResult::Reresolve), "expected Reresolve for route mismatch, got {result:?}");
}
