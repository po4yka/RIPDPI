use std::net::IpAddr;
use std::num::NonZeroUsize;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use lru::LruCache;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolverMappingSource {
    System,
    DohPrimary,
    DohSecondary,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolverIpFamily {
    Ipv4,
    Ipv6,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedMapping {
    pub best_ip: IpAddr,
    pub ip_family: ResolverIpFamily,
    pub source: ResolverMappingSource,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ResolverMappingKey {
    host: String,
    network_scope: String,
}

impl ResolverMappingKey {
    pub fn new(host: impl AsRef<str>, network_scope: impl AsRef<str>) -> Self {
        Self {
            host: host.as_ref().trim().trim_end_matches('.').to_ascii_lowercase(),
            network_scope: network_scope.as_ref().trim().to_string(),
        }
    }
}

struct CacheEntry {
    mapping: ResolvedMapping,
    expires_at: Instant,
}

pub struct ResolverMappingCache {
    entries: Mutex<LruCache<ResolverMappingKey, CacheEntry>>,
}

impl ResolverMappingCache {
    pub fn new(capacity: usize) -> Self {
        let capacity = NonZeroUsize::new(capacity.max(1)).expect("capacity is non-zero");
        Self { entries: Mutex::new(LruCache::new(capacity)) }
    }

    pub fn insert(&self, key: ResolverMappingKey, mapping: ResolvedMapping, ttl: Duration) {
        if ttl.is_zero() {
            return;
        }
        if let Ok(mut entries) = self.entries.lock() {
            entries.put(key, CacheEntry { mapping, expires_at: Instant::now() + ttl });
        }
    }

    pub fn lookup(&self, key: &ResolverMappingKey) -> Option<ResolvedMapping> {
        let mut entries = self.entries.lock().ok()?;
        let entry = entries.get(key)?;
        if entry.expires_at <= Instant::now() {
            entries.pop(key);
            return None;
        }
        Some(entry.mapping.clone())
    }
}

impl Default for ResolverMappingCache {
    fn default() -> Self {
        Self::new(1024)
    }
}

impl From<IpAddr> for ResolverIpFamily {
    fn from(value: IpAddr) -> Self {
        match value {
            IpAddr::V4(_) => Self::Ipv4,
            IpAddr::V6(_) => Self::Ipv6,
        }
    }
}
