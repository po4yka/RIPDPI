use std::net::{IpAddr, Ipv4Addr};
use std::time::Duration;

use ripdpi_runtime_dns_cache::{
    ResolvedMapping, ResolverIpFamily, ResolverMappingCache, ResolverMappingKey, ResolverMappingSource,
};

#[test]
fn resolver_mapping_cache_stores_and_hits_by_host_and_network_scope() {
    let cache = ResolverMappingCache::new(8);
    let key = ResolverMappingKey::new("Example.Org.", "wifi:home");
    let mapping = mapping(Ipv4Addr::new(203, 0, 113, 10));

    cache.insert(key, mapping.clone(), Duration::from_secs(60));

    assert_eq!(cache.lookup(&ResolverMappingKey::new("example.org", "wifi:home")), Some(mapping));
}

#[test]
fn resolver_mapping_cache_expires_entries() {
    let cache = ResolverMappingCache::new(8);
    let key = ResolverMappingKey::new("example.org", "wifi:home");
    cache.insert(key.clone(), mapping(Ipv4Addr::new(203, 0, 113, 10)), Duration::from_millis(1));

    std::thread::sleep(Duration::from_millis(5));

    assert_eq!(cache.lookup(&key), None);
}

#[test]
fn resolver_mapping_cache_misses_across_network_scopes() {
    let cache = ResolverMappingCache::new(8);
    let mapping = mapping(Ipv4Addr::new(203, 0, 113, 10));
    cache.insert(ResolverMappingKey::new("example.org", "wifi:home"), mapping, Duration::from_secs(60));

    assert_eq!(cache.lookup(&ResolverMappingKey::new("example.org", "cell:carrier")), None);
}

fn mapping(ip: Ipv4Addr) -> ResolvedMapping {
    ResolvedMapping {
        best_ip: IpAddr::V4(ip),
        ip_family: ResolverIpFamily::Ipv4,
        source: ResolverMappingSource::DohPrimary,
    }
}
