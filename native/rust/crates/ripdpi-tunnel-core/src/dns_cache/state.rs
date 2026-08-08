use std::collections::HashMap;
use std::num::NonZeroUsize;

use super::{DnsCacheEntry, DnsCacheError, DnsCacheKey};
use lru::LruCache;

/// LRU DNS cache that maps real IPv4 answers to synthetic IPv4 addresses.
///
/// The cache allocates addresses from the range `[net, net+max)` and preserves
/// a reverse mapping so later flows in the same tunnel session can turn a
/// synthetic destination back into the original upstream IPv4 address.
pub struct DnsCache {
    lru: LruCache<DnsCacheKey, usize>,
    rev: HashMap<u32, DnsCacheEntry>,
    records: Vec<Option<DnsCacheKey>>,
    /// Active TCP/UDP mapping leases keyed by synthetic IP.
    leases: HashMap<u32, usize>,
    net: u32,
    mask: u32,
    max: usize,
    next_free: usize,
    free_slots: Vec<usize>,
    /// True only when the active TUN owns an IPv6 address and default route.
    pub(super) ipv6_enabled: bool,
    #[cfg(test)]
    reset_inspections: usize,
    #[cfg(test)]
    allocation_steps: usize,
}

impl DnsCache {
    pub fn new(net: u32, mask: u32, max: usize) -> Result<Self, DnsCacheError> {
        let capacity = NonZeroUsize::new(max).ok_or(DnsCacheError::EmptyCache)?;
        let inverted = !mask;
        if inverted != 0 && inverted & inverted.wrapping_add(1) != 0 {
            return Err(DnsCacheError::NonContiguousNetmask);
        }
        if net & mask != net {
            return Err(DnsCacheError::NetworkHasHostBits);
        }
        if (max as u64) > u64::from(inverted) {
            return Err(DnsCacheError::CapacityExceedsNetwork { size: max, available: inverted });
        }
        let mut records = Vec::new();
        records.try_reserve_exact(max).map_err(|_| DnsCacheError::AllocationFailed { size: max })?;
        records.resize_with(max, || None);
        Ok(Self {
            lru: LruCache::new(capacity),
            rev: HashMap::new(),
            records,
            leases: HashMap::new(),
            net,
            mask,
            max,
            next_free: 0,
            free_slots: Vec::new(),
            ipv6_enabled: false,
            #[cfg(test)]
            reset_inspections: 0,
            #[cfg(test)]
            allocation_steps: 0,
        })
    }

    pub(crate) fn set_ipv6_enabled(&mut self, enabled: bool) {
        self.ipv6_enabled = enabled;
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

    /// Acquire a mapping lease. Returns false for an IP with no live mapping.
    pub fn pin(&mut self, ip: u32) -> bool {
        if !self.rev.contains_key(&ip) {
            return false;
        }
        let count = self.leases.entry(ip).or_default();
        *count = count.saturating_add(1);
        true
    }

    /// Release a pin on a synthetic IP.
    pub fn unpin(&mut self, ip: u32) {
        let Some(count) = self.leases.get_mut(&ip) else {
            return;
        };
        *count = count.saturating_sub(1);
        if *count == 0 {
            self.leases.remove(&ip);
        }
    }

    /// Drop every mapping that is not owned by an active flow. Generation
    /// changes call this before committing a response from the new underlay.
    pub(crate) fn reset_unleased(&mut self) {
        #[cfg(test)]
        {
            self.reset_inspections = self.reset_inspections.saturating_add(self.lru.len());
        }
        let stale = self
            .lru
            .iter()
            .filter_map(|(key, &index)| {
                let ip = self.net | index as u32;
                (!self.leases.contains_key(&ip)).then(|| (key.clone(), index))
            })
            .collect::<Vec<_>>();
        for (key, index) in stale {
            self.reclaim_slot(&key, index);
        }
    }

    pub(crate) fn find(&mut self, host: &str, real_ip: u32) -> Result<(u32, bool), DnsCacheError> {
        if self.max == 0 {
            return Err(DnsCacheError::EmptyCache);
        }

        let canonical_host = host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase();
        let key = DnsCacheKey { host: canonical_host, real_ip };

        if let Some(&idx) = self.lru.get(&key) {
            return Ok((self.net | idx as u32, true));
        }

        #[cfg(test)]
        {
            self.allocation_steps = self.allocation_steps.saturating_add(1);
        }
        let idx = if let Some(idx) = self.free_slots.pop() {
            idx
        } else if self.next_free < self.max {
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
                !self.leases.contains_key(&candidate_ip)
            })
            .map(|(key, &slot)| (key.clone(), slot));

        if let Some((evicted_key, evicted_idx)) = candidate {
            self.clear_slot(&evicted_key, evicted_idx);
            return Ok(evicted_idx);
        }

        Err(DnsCacheError::AllMappingsLeased)
    }

    fn reclaim_slot(&mut self, key: &DnsCacheKey, idx: usize) {
        self.clear_slot(key, idx);
        self.free_slots.push(idx);
    }

    fn clear_slot(&mut self, key: &DnsCacheKey, idx: usize) {
        self.lru.pop(key);
        self.remove_record(idx);
    }

    fn remove_record(&mut self, idx: usize) {
        let evicted_ip = self.net | idx as u32;
        self.rev.remove(&evicted_ip);
        self.records[idx] = None;
    }

    #[cfg(test)]
    pub(crate) fn lease_count(&self, ip: u32) -> usize {
        self.leases.get(&ip).copied().unwrap_or(0)
    }

    #[cfg(test)]
    pub(crate) fn reset_inspection_count(&self) -> usize {
        self.reset_inspections
    }

    #[cfg(test)]
    pub(crate) fn allocation_step_count(&self) -> usize {
        self.allocation_steps
    }
}
