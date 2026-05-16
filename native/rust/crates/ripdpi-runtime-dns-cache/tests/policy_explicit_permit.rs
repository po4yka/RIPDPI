use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use ripdpi_runtime_dns_cache::{
    CacheKey, CrossRouteReusePolicy, LookupResult, ResolvedAnswer, ResolverPath, RouteAwareDnsCache, RouteDecision,
};

fn make_answer(route: RouteDecision) -> ResolvedAnswer {
    ResolvedAnswer {
        domain: "permit.com".to_string(),
        qtype: 1,
        ips: vec![IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9))],
        resolver_path: ResolverPath::BootstrapDirect,
        route_decision: route,
        expiry: Instant::now() + Duration::from_secs(300),
        policy_version: 7,
    }
}

/// With cross_route_reuse_allowed=true, a Proxy-keyed lookup returns a Direct-inserted entry.
#[test]
fn cross_route_reuse_allowed_direct_to_proxy() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: true };
    let cache = RouteAwareDnsCache::new(policy);

    let direct_key = CacheKey { domain: "permit.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    cache.insert(direct_key, make_answer(RouteDecision::Direct));

    let proxy_key = CacheKey { domain: "permit.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    assert!(
        matches!(cache.lookup(&proxy_key), LookupResult::Hit(_)),
        "with cross_route_reuse_allowed=true, Proxy lookup should hit a Direct-inserted entry"
    );
}

/// With cross_route_reuse_allowed=true, a Direct-keyed lookup returns a Proxy-inserted entry.
#[test]
fn cross_route_reuse_allowed_proxy_to_direct() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: true };
    let cache = RouteAwareDnsCache::new(policy);

    let proxy_key = CacheKey { domain: "permit.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    cache.insert(proxy_key, make_answer(RouteDecision::Proxy));

    let direct_key = CacheKey { domain: "permit.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    assert!(
        matches!(cache.lookup(&direct_key), LookupResult::Hit(_)),
        "with cross_route_reuse_allowed=true, Direct lookup should hit a Proxy-inserted entry"
    );
}

/// Sanity: cross_route_reuse_allowed=false still blocks reuse (regression guard).
#[test]
fn cross_route_reuse_denied_still_blocks() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);

    let direct_key = CacheKey { domain: "permit.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    cache.insert(direct_key, make_answer(RouteDecision::Direct));

    let proxy_key = CacheKey { domain: "permit.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    assert!(
        !matches!(cache.lookup(&proxy_key), LookupResult::Hit(_)),
        "cross_route_reuse_allowed=false must block cross-route reuse"
    );
}
