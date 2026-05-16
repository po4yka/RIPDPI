pub mod dns_hostname_cache;
pub mod route_aware_cache;

pub use dns_hostname_cache::*;
pub use route_aware_cache::{
    CacheKey, CrossRouteReusePolicy, LookupResult, NegativeCachePolicy, ResolvedAnswer, ResolverPath,
    RouteAwareDnsCache, RouteDecision,
};
