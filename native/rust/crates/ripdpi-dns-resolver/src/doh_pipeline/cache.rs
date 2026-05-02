use std::num::NonZeroUsize;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use lru::LruCache;

use super::DohBatchLookup;

struct CachedBatchLookup {
    lookup: DohBatchLookup,
    expires_at: Instant,
}

pub(super) struct LookupCache {
    entries: Mutex<LruCache<String, CachedBatchLookup>>,
}

impl LookupCache {
    pub(super) fn new(capacity: NonZeroUsize) -> Self {
        Self { entries: Mutex::new(LruCache::new(capacity)) }
    }

    pub(super) fn fresh_lookup(&self, domain: &str) -> Option<DohBatchLookup> {
        let cache = self.entries.lock().ok()?;
        let cached = cache.peek(domain)?;
        (cached.expires_at > Instant::now()).then(|| cached.lookup.clone())
    }

    pub(super) fn store_lookup(&self, domain: &str, lookup: &DohBatchLookup) {
        let Some(ttl_secs) = lookup.cache_ttl_secs.filter(|ttl| *ttl > 0) else {
            return;
        };
        if let Ok(mut cache) = self.entries.lock() {
            cache.put(
                domain.to_string(),
                CachedBatchLookup {
                    lookup: lookup.clone(),
                    expires_at: Instant::now() + Duration::from_secs(u64::from(ttl_secs)),
                },
            );
        }
    }
}
