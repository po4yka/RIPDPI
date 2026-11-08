use std::collections::HashMap;
use std::num::NonZeroUsize;

use super::{DnsCacheEntry, DnsCacheError, DnsCacheKey};
use lru::LruCache;

/// LRU DNS cache that maps real IPv4 answers to synthetic IPv4 addresses.
///
/// Live mappings are bounded by the cache capacity. Issued addresses are never
/// reused within this session, so a delayed flow cannot select another owner.
pub struct DnsCache {
    lru: LruCache<DnsCacheKey, u32>,
    rev: HashMap<u32, DnsCacheEntry>,
    /// Active TCP/UDP mapping leases keyed by synthetic IP.
    leases: HashMap<u32, usize>,
    net: u32,
    mask: u32,
    max: usize,
    next_offset: u64,
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
        let mut rev = HashMap::new();
        rev.try_reserve(max).map_err(|_| DnsCacheError::AllocationFailed { size: max })?;
        Ok(Self {
            lru: LruCache::new(capacity),
            rev,
            leases: HashMap::new(),
            net,
            mask,
            max,
            next_offset: 0,
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
            .filter(|&(_, &ip)| !self.leases.contains_key(&ip))
            .map(|(key, &ip)| (key.clone(), ip))
            .collect::<Vec<_>>();
        for (key, ip) in stale {
            self.remove_mapping(&key, ip);
        }
    }

    pub(crate) fn find(&mut self, host: &str, real_ip: u32) -> Result<(u32, bool), DnsCacheError> {
        let canonical_host = host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase();
        let key = DnsCacheKey { host: canonical_host, real_ip };

        if let Some(&ip) = self.lru.get(&key) {
            return Ok((ip, true));
        }

        if self.next_offset >= u64::from(!self.mask) {
            return Err(DnsCacheError::SyntheticNetworkExhausted);
        }
        if self.lru.len() == self.max {
            self.evict_mapping()?;
        }
        #[cfg(test)]
        {
            self.allocation_steps = self.allocation_steps.saturating_add(1);
        }
        let fake_ip = self.net | self.next_offset as u32;
        self.next_offset += 1;
        self.lru.put(key.clone(), fake_ip);
        self.rev.insert(fake_ip, DnsCacheEntry { host: key.host, real_ip });
        Ok((fake_ip, false))
    }

    fn evict_mapping(&mut self) -> Result<(), DnsCacheError> {
        let candidate =
            self.lru.iter().rev().find(|&(_, &ip)| !self.leases.contains_key(&ip)).map(|(key, &ip)| (key.clone(), ip));

        if let Some((key, ip)) = candidate {
            self.remove_mapping(&key, ip);
            return Ok(());
        }

        Err(DnsCacheError::AllMappingsLeased)
    }

    fn remove_mapping(&mut self, key: &DnsCacheKey, ip: u32) {
        self.lru.pop(key);
        self.rev.remove(&ip);
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

#[cfg(test)]
mod ownership_tests;
