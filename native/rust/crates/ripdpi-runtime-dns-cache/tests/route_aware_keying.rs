use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use ripdpi_runtime_dns_cache::{
    CacheKey, CrossRouteReusePolicy, LookupResult, ResolvedAnswer, ResolverPath, RouteAwareDnsCache, RouteDecision,
};

fn make_answer(domain: &str, route: RouteDecision) -> ResolvedAnswer {
    ResolvedAnswer {
        domain: domain.to_string(),
        qtype: 1,
        ips: vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
        resolver_path: ResolverPath::BootstrapDirect,
        route_decision: route,
        expiry: Instant::now() + Duration::from_secs(300),
        policy_version: 1,
    }
}

/// lookup(domain, qtype, route=Proxy) must NOT return an entry inserted under route=Direct
/// when cross-route reuse is NOT permitted.
#[test]
fn proxy_lookup_does_not_return_direct_entry() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);

    let direct_key = CacheKey { domain: "example.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    cache.insert(direct_key, make_answer("example.com", RouteDecision::Direct));

    let proxy_key = CacheKey { domain: "example.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    let result = cache.lookup(&proxy_key);

    assert!(
        !matches!(result, LookupResult::Hit(_)),
        "Proxy lookup must not return a Direct-keyed entry when cross_route_reuse_allowed=false"
    );
}

/// lookup(domain, qtype, route=Direct) must NOT return an entry inserted under route=Proxy
/// when cross-route reuse is NOT permitted.
#[test]
fn direct_lookup_does_not_return_proxy_entry() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);

    let proxy_key = CacheKey { domain: "example.com".to_string(), qtype: 1, route: RouteDecision::Proxy };
    cache.insert(proxy_key, make_answer("example.com", RouteDecision::Proxy));

    let direct_key = CacheKey { domain: "example.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    let result = cache.lookup(&direct_key);

    assert!(
        !matches!(result, LookupResult::Hit(_)),
        "Direct lookup must not return a Proxy-keyed entry when cross_route_reuse_allowed=false"
    );
}

/// Same route key returns Hit.
#[test]
fn same_route_key_returns_hit() {
    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);

    let key = CacheKey { domain: "same.com".to_string(), qtype: 1, route: RouteDecision::Direct };
    cache.insert(key.clone(), make_answer("same.com", RouteDecision::Direct));

    assert!(matches!(cache.lookup(&key), LookupResult::Hit(_)));
}
