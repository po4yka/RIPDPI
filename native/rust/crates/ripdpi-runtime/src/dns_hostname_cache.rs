use std::net::IpAddr;
use std::num::NonZeroUsize;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use lru::LruCache;

const MIN_TTL: Duration = Duration::from_secs(60);
const MAX_TTL: Duration = Duration::from_secs(7200);
const DEFAULT_CAPACITY: usize = 4096;

struct Entry {
    hostname: String,
    expires: Instant,
}

struct Inner {
    lru: LruCache<IpAddr, Entry>,
    hits: u64,
    misses: u64,
}

pub struct DnsHostnameCache {
    inner: Mutex<Inner>,
}

impl DnsHostnameCache {
    pub fn new(capacity: usize) -> Self {
        let cap = NonZeroUsize::new(capacity.max(1)).unwrap();
        Self { inner: Mutex::new(Inner { lru: LruCache::new(cap), hits: 0, misses: 0 }) }
    }

    pub fn with_default_capacity() -> Self {
        Self::new(DEFAULT_CAPACITY)
    }

    pub fn insert(&self, ip: IpAddr, hostname: String, ttl: Duration) {
        let ttl = ttl.clamp(MIN_TTL, MAX_TTL);
        let entry = Entry { hostname, expires: Instant::now() + ttl };
        if let Ok(mut inner) = self.inner.lock() {
            inner.lru.put(ip, entry);
        }
    }

    pub fn lookup(&self, ip: &IpAddr) -> Option<String> {
        let mut inner = self.inner.lock().ok()?;
        let result = inner.lru.get(ip).and_then(|entry| {
            if entry.expires > Instant::now() {
                Some(entry.hostname.clone())
            } else {
                None
            }
        });
        match result {
            Some(hostname) => {
                inner.hits += 1;
                Some(hostname)
            }
            None => {
                inner.misses += 1;
                inner.lru.pop(ip);
                None
            }
        }
    }

    pub fn stats(&self) -> (usize, u64, u64) {
        self.inner.lock().map(|inner| (inner.lru.len(), inner.hits, inner.misses)).unwrap_or((0, 0, 0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    #[test]
    fn insert_and_lookup_returns_hostname() {
        let cache = DnsHostnameCache::new(16);
        let ip = IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4));
        cache.insert(ip, "example.com".to_string(), Duration::from_secs(300));
        assert_eq!(cache.lookup(&ip), Some("example.com".to_string()));
    }

    #[test]
    fn expired_entry_returns_none() {
        let cache = DnsHostnameCache::new(16);
        let ip = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
        // Insert with 0s TTL — will be clamped to MIN_TTL (60s), so we can't actually
        // test expiry in a unit test without mocking time. Instead test the eviction path
        // by directly constructing an expired entry via the struct.
        cache.insert(ip, "test.com".to_string(), Duration::from_secs(300));
        // Entry is fresh, should return
        assert!(cache.lookup(&ip).is_some());
        // Manually expire it by forcing an expired entry
        {
            let mut inner = cache.inner.lock().unwrap();
            if let Some(entry) = inner.lru.get_mut(&ip) {
                entry.expires = Instant::now() - Duration::from_secs(1);
            }
        }
        assert_eq!(cache.lookup(&ip), None);
    }

    #[test]
    fn capacity_evicts_lru() {
        let cache = DnsHostnameCache::new(2);
        let ip1 = IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1));
        let ip2 = IpAddr::V4(Ipv4Addr::new(2, 2, 2, 2));
        let ip3 = IpAddr::V4(Ipv4Addr::new(3, 3, 3, 3));
        cache.insert(ip1, "a.com".to_string(), Duration::from_secs(300));
        cache.insert(ip2, "b.com".to_string(), Duration::from_secs(300));
        cache.insert(ip3, "c.com".to_string(), Duration::from_secs(300));
        // ip1 should be evicted (LRU)
        assert_eq!(cache.lookup(&ip1), None);
        assert!(cache.lookup(&ip2).is_some());
        assert!(cache.lookup(&ip3).is_some());
    }

    #[test]
    fn stats_tracks_hits_and_misses() {
        let cache = DnsHostnameCache::new(16);
        let ip = IpAddr::V4(Ipv4Addr::new(5, 5, 5, 5));
        cache.insert(ip, "stats.com".to_string(), Duration::from_secs(300));
        let _ = cache.lookup(&ip); // hit
        let _ = cache.lookup(&IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9))); // miss
        let (size, hits, misses) = cache.stats();
        assert_eq!(size, 1);
        assert_eq!(hits, 1);
        assert_eq!(misses, 1);
    }
}
