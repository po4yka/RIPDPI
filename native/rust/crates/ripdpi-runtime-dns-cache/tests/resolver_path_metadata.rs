use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use ripdpi_runtime_dns_cache::{
    CacheKey, CrossRouteReusePolicy, LookupResult, ResolvedAnswer, ResolverPath, RouteAwareDnsCache, RouteDecision,
};

/// ResolvedAnswer carries all 7 required fields and the cache returns them intact.
#[test]
fn resolved_answer_preserves_all_seven_fields() {
    let expiry = Instant::now() + Duration::from_secs(120);
    let answer = ResolvedAnswer {
        domain: "meta.com".to_string(),
        qtype: 28, // AAAA
        ips: vec![IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2))],
        resolver_path: ResolverPath::TunneledDoh,
        route_decision: RouteDecision::Proxy,
        expiry,
        policy_version: 42,
    };

    let policy = CrossRouteReusePolicy { cross_route_reuse_allowed: false };
    let cache = RouteAwareDnsCache::new(policy);
    let key = CacheKey { domain: "meta.com".to_string(), qtype: 28, route: RouteDecision::Proxy };
    cache.insert(key.clone(), answer);

    let LookupResult::Hit(got) = cache.lookup(&key) else {
        panic!("expected Hit");
    };

    assert_eq!(got.domain, "meta.com");
    assert_eq!(got.qtype, 28);
    assert_eq!(got.ips.len(), 2);
    assert!(matches!(got.resolver_path, ResolverPath::TunneledDoh));
    assert!(matches!(got.route_decision, RouteDecision::Proxy));
    // expiry is stored as Instant; verify it is still in the future
    assert!(got.expiry > Instant::now());
    assert_eq!(got.policy_version, 42);
}
