use std::collections::{HashMap, HashSet};
use std::num::NonZeroUsize;

use lru::LruCache;
use tracing::debug;

use super::{DnsCacheEntry, DnsCacheError, DnsCacheKey};

/// LRU DNS cache that maps real IPv4 answers to synthetic IPv4 addresses.
///
/// The cache allocates addresses from the range `[net, net+max)` and preserves
/// a reverse mapping so later tunnel sessions can turn a synthetic destination
/// back into the original upstream IPv4 address.
pub struct DnsCache {
    lru: LruCache<DnsCacheKey, usize>,
    rev: HashMap<u32, DnsCacheEntry>,
    records: Vec<Option<DnsCacheKey>>,
    /// Synthetic IPs that must not be evicted while a TCP session is active.
    pinned: HashSet<u32>,
    net: u32,
    mask: u32,
    max: usize,
    next_free: usize,
}

impl DnsCache {
    pub fn new(net: u32, mask: u32, max: usize) -> Self {
        debug_assert!(max > 0, "max must be non-zero");
        debug_assert!((max as u64) <= ((!mask) as u64), "max exceeds addressable range");
        let capacity = NonZeroUsize::new(max).expect("max must be > 0");
        Self {
            lru: LruCache::new(capacity),
            rev: HashMap::new(),
            records: vec![None; max],
            pinned: HashSet::new(),
            net,
            mask,
            max,
            next_free: 0,
        }
    }

    pub fn lookup(&mut self, ip: u32) -> Option<DnsCacheEntry> {
        if ip & self.mask != self.net {
            return None;
        }

        let entry = self.rev.get(&ip)?.clone();
        self.lru.get(&DnsCacheKey { host: entry.host.clone(), real_ip: entry.real_ip });
        Some(entry)
    }

    pub fn contains_mapped_ip(&self, ip: u32) -> bool {
        ip & self.mask == self.net
    }

    /// Pin a synthetic IP so it is not evicted while a TCP session is active.
    pub fn pin(&mut self, ip: u32) {
        self.pinned.insert(ip);
    }

    /// Release a pin on a synthetic IP.
    pub fn unpin(&mut self, ip: u32) {
        self.pinned.remove(&ip);
    }

    pub(super) fn find(&mut self, host: &str, real_ip: u32) -> Result<(u32, bool), DnsCacheError> {
        if self.max == 0 {
            return Err(DnsCacheError::EmptyCache);
        }

        let key = DnsCacheKey { host: host.to_string(), real_ip };

        if let Some(&idx) = self.lru.get(&key) {
            return Ok((self.net | idx as u32, true));
        }

        let idx = if self.next_free < self.max {
            let idx = self.next_free;
            self.next_free += 1;
            idx
        } else {
            self.evict_slot()?
        };

        let fake_ip = self.net | idx as u32;
        self.lru.put(key.clone(), idx);
        self.records[idx] = Some(key.clone());
        self.rev.insert(fake_ip, DnsCacheEntry { host: key.host, real_ip });
        Ok((fake_ip, false))
    }

    fn evict_slot(&mut self) -> Result<usize, DnsCacheError> {
        let candidate = self
            .lru
            .iter()
            .rev()
            .find(|&(_, &slot)| {
                let candidate_ip = self.net | slot as u32;
                !self.pinned.contains(&candidate_ip)
            })
            .map(|(key, &slot)| (key.clone(), slot));

        if let Some((evicted_key, evicted_idx)) = candidate {
            self.remove_slot(&evicted_key, evicted_idx);
            return Ok(evicted_idx);
        }

        // All candidates are pinned; evict the true LRU to prevent unbounded growth.
        debug!("mapdns LRU eviction: all cache slots are pinned; evicting LRU anyway");
        let (_, evicted_idx) = self.lru.pop_lru().ok_or(DnsCacheError::EmptyCache)?;
        self.remove_record(evicted_idx);
        Ok(evicted_idx)
    }

    fn remove_slot(&mut self, key: &DnsCacheKey, idx: usize) {
        self.lru.pop(key);
        self.remove_record(idx);
    }

    fn remove_record(&mut self, idx: usize) {
        let evicted_ip = self.net | idx as u32;
        self.rev.remove(&evicted_ip);
        self.records[idx] = None;
    }
}
