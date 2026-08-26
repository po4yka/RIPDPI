//! Per-`(host, ip_set, app_family, network_profile)` transport-policy cache.
//!
//! Ownership: this is a **reference** cache. The live owner of direct-mode
//! transport policy on Android is the Kotlin service layer
//! (`ServerCapabilityStore` + `DirectPathPolicyLearner`); this type does not
//! itself enforce any live Android policy and is wired into no decision path
//! yet. It stays test-validated for a future native policy owner gated on
//! per-flow UID->package attribution.
//!
//! Sibling to the TLS family cache.  Shares the same invalidation rules:
//!
//! * ASN change — [`TransportPolicyCache::invalidate_on_asn_change`]
//! * Access-type change — [`TransportPolicyCache::invalidate_on_access_type_change`]
//! * 3 consecutive failures — [`TransportPolicyCache::record_failure`] /
//!   [`TransportPolicyCache::invalidate_on_three_failures`]
//! * 7-day TTL — [`TransportPolicyCache::prune_expired`]
//! * HTTPS/SVCB TTL expiry — [`TransportPolicyCache::prune_expired`] (callers
//!   supply `svcb_ttl_secs`; 0 means no SVCB TTL)
//! * ECH capability change — [`TransportPolicyCache::invalidate_on_ech_change`]
//!
//! Write path uses atomic rename semantics via [`AtomicFile`].

use std::collections::HashMap;
use std::io;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// 7-day TTL in seconds.
pub const DEFAULT_TTL_SECS: u64 = 7 * 24 * 3600;

// ── Key types ────────────────────────────────────────────────────────────────

/// Coarse access-technology tag stored in the cache key.
///
/// This is intentionally a simple integer so the cache has no dependency on
/// `ripdpi-runtime-strategy` (which lives higher in the dep graph and already
/// depends on this crate).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AccessTypeTag(pub u8);

impl AccessTypeTag {
    pub const UNKNOWN: Self = Self(0);
    pub const WIFI: Self = Self(1);
    pub const CELLULAR: Self = Self(2);
    pub const ETHERNET: Self = Self(3);
    pub const VPN: Self = Self(4);
}

/// Network-profile key component stored in the cache key.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct NetProfileKey {
    /// Autonomous System Number (0 = unknown).
    pub asn: u32,
    /// Coarse access-technology tag.
    pub access_type: AccessTypeTag,
    /// Whether ECH was available on last probe.
    pub ech_available: bool,
}

/// Cache key — the full 4-tuple from the spec.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct TransportPolicyCacheKey {
    pub host: String,
    /// Sorted, deduplicated IP set for stable equality.
    pub ip_set: Vec<IpAddr>,
    /// Opaque app-family string (e.g. `"browser"`, `"messaging"`).
    pub app_family: String,
    pub net_profile: NetProfileKey,
}

impl TransportPolicyCacheKey {
    /// Construct a key, sorting and deduplicating `ip_set` for stable hashing.
    pub fn new(
        host: impl Into<String>,
        mut ip_set: Vec<IpAddr>,
        app_family: impl Into<String>,
        net_profile: NetProfileKey,
    ) -> Self {
        ip_set.sort_unstable_by_key(|ip| match ip {
            IpAddr::V4(v4) => (0u8, u128::from(v4.to_bits())),
            IpAddr::V6(v6) => (1u8, v6.to_bits()),
        });
        ip_set.dedup();
        Self { host: host.into(), ip_set, app_family: app_family.into(), net_profile }
    }
}

// ── Cached policy ────────────────────────────────────────────────────────────

/// Minimal transport-policy value stored in the cache.
///
/// Fields mirror `ripdpi-runtime-policy::TransportPolicy` but are inlined here
/// to avoid a dependency cycle.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CachedTransportPolicy {
    /// Opaque policy outcome tag (e.g. `"TRANSPARENT_OK"`, `"OWNED_STACK_ONLY"`).
    pub outcome_tag: String,
    /// Whether QUIC/UDP is disabled for this host.
    pub quic_disabled: bool,
    /// Preferred HTTP stack tier (`"H3"`, `"H2"`, `"H1"`).
    pub preferred_stack: String,
}

// ── Cache entry ──────────────────────────────────────────────────────────────

#[derive(Debug)]
struct Entry {
    policy: CachedTransportPolicy,
    /// Unix-epoch seconds when this entry was inserted.
    inserted_at: u64,
    /// HTTPS/SVCB TTL in seconds (0 = no SVCB TTL constraint).
    svcb_ttl_secs: u64,
    /// Count of consecutive failures recorded against this entry.
    consecutive_failures: u8,
}

impl Entry {
    fn is_expired(&self, now: u64, ttl_secs: u64) -> bool {
        // Hard 7-day TTL
        if now.saturating_sub(self.inserted_at) >= ttl_secs {
            return true;
        }
        // HTTPS/SVCB TTL
        if self.svcb_ttl_secs > 0 && now.saturating_sub(self.inserted_at) >= self.svcb_ttl_secs {
            return true;
        }
        false
    }
}

// ── AtomicFile ───────────────────────────────────────────────────────────────

/// Writes `data` to `path` atomically: write to a sibling `.tmp` file,
/// fsync the file data, rename into place, then best-effort fsync the parent
/// directory so the rename itself survives a crash (LMK SIGKILL durability).
/// The rename is atomic on all POSIX systems and on NTFS.
pub struct AtomicFile {
    path: PathBuf,
}

impl AtomicFile {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn write(&self, data: &[u8]) -> io::Result<()> {
        use std::io::Write as _;
        let tmp = self.path.with_extension("tmp");
        {
            let mut file = std::fs::File::create(&tmp)?;
            file.write_all(data)?;
            file.sync_all()?;
        }
        std::fs::rename(&tmp, &self.path)?;
        // Best-effort: fsync the parent directory so the rename itself is durable.
        // Some filesystems/platforms reject fsync on directories; ignore that gracefully.
        if let Some(parent) = self.path.parent() {
            let _ = std::fs::File::open(parent).and_then(|dir| dir.sync_all());
        }
        Ok(())
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

// ── Cache ────────────────────────────────────────────────────────────────────

/// In-memory transport-policy cache keyed by `(host, ip_set, app_family, net_profile)`.
#[derive(Debug, Default)]
pub struct TransportPolicyCache {
    entries: HashMap<TransportPolicyCacheKey, Entry>,
    ttl_secs: u64,
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO).as_secs()
}

impl TransportPolicyCache {
    /// Create a new cache with the default 7-day TTL.
    pub fn new() -> Self {
        Self { entries: HashMap::new(), ttl_secs: DEFAULT_TTL_SECS }
    }

    /// Create a cache with a custom TTL (useful for tests).
    pub fn with_ttl(ttl_secs: u64) -> Self {
        Self { entries: HashMap::new(), ttl_secs }
    }

    /// Insert or update an entry.  `svcb_ttl_secs = 0` means no SVCB constraint.
    pub fn insert(&mut self, key: TransportPolicyCacheKey, policy: CachedTransportPolicy, svcb_ttl_secs: u64) {
        self.entries.insert(key, Entry { policy, inserted_at: now_secs(), svcb_ttl_secs, consecutive_failures: 0 });
    }

    /// Look up a policy.  Returns `None` on cache miss or if the entry has expired.
    ///
    /// Expired entries are lazily removed on lookup.
    pub fn lookup(&mut self, key: &TransportPolicyCacheKey) -> Option<&CachedTransportPolicy> {
        let ttl = self.ttl_secs;
        let now = now_secs();
        if let Some(entry) = self.entries.get(key)
            && entry.is_expired(now, ttl)
        {
            self.entries.remove(key);
            return None;
        }
        self.entries.get(key).map(|e| &e.policy)
    }

    /// Record one consecutive failure for `host`.  Invalidates all entries for
    /// that host once the count reaches 3.  Returns `true` if any entries were
    /// removed.
    pub fn record_failure(&mut self, host: &str) -> bool {
        let mut removed = false;
        self.entries.retain(|key, entry| {
            if key.host != host {
                return true;
            }
            entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);
            let keep = entry.consecutive_failures < 3;
            removed |= !keep;
            keep
        });
        removed
    }

    /// Invalidate all entries for `host` immediately, regardless of their
    /// recorded consecutive-failure counts. Use when the caller has
    /// already established (outside this cache) that three failures
    /// occurred and the entries must not be trusted anymore. Unlike
    /// [`record_failure`], this never waits for a failure counter to
    /// reach 3.
    pub fn invalidate_on_three_failures(&mut self, host: &str) -> bool {
        let before = self.entries.len();
        self.entries.retain(|key, _| key.host != host);
        self.entries.len() != before
    }

    /// Invalidate all entries whose `net_profile.asn` matches `old_asn`.
    pub fn invalidate_on_asn_change(&mut self, old_asn: u32) -> bool {
        let before = self.entries.len();
        self.entries.retain(|key, _| key.net_profile.asn != old_asn);
        self.entries.len() != before
    }

    /// Invalidate all entries whose `net_profile.access_type` matches `old_tag`.
    pub fn invalidate_on_access_type_change(&mut self, old_tag: AccessTypeTag) -> bool {
        let before = self.entries.len();
        self.entries.retain(|key, _| key.net_profile.access_type != old_tag);
        self.entries.len() != before
    }

    /// Invalidate all entries whose `net_profile.ech_available` differs from
    /// `new_ech_available` (i.e. ECH capability changed).
    pub fn invalidate_on_ech_change(&mut self, new_ech_available: bool) -> bool {
        let before = self.entries.len();
        self.entries.retain(|key, _| key.net_profile.ech_available == new_ech_available);
        self.entries.len() != before
    }

    /// Remove all entries that have exceeded their TTL or SVCB TTL.
    pub fn prune_expired(&mut self, now: u64) -> usize {
        let ttl = self.ttl_secs;
        let before = self.entries.len();
        self.entries.retain(|_, entry| !entry.is_expired(now, ttl));
        before - self.entries.len()
    }

    /// Number of live entries.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;

    use super::*;

    fn make_key(host: &str, asn: u32) -> TransportPolicyCacheKey {
        TransportPolicyCacheKey::new(
            host,
            vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
            "browser",
            NetProfileKey { asn, access_type: AccessTypeTag::WIFI, ech_available: false },
        )
    }

    fn policy() -> CachedTransportPolicy {
        CachedTransportPolicy {
            outcome_tag: "TRANSPARENT_OK".to_owned(),
            quic_disabled: false,
            preferred_stack: "H3".to_owned(),
        }
    }

    // ── Test 1: insert + lookup returns the policy ────────────────────────────

    #[test]
    fn insert_then_lookup_returns_policy() {
        let mut cache = TransportPolicyCache::new();
        let key = make_key("example.com", 1234);
        cache.insert(key.clone(), policy(), 0);
        let result = cache.lookup(&key);
        assert!(result.is_some());
        assert_eq!(result.unwrap().outcome_tag, "TRANSPARENT_OK");
    }

    // ── Test 2: lookup with different host returns None ───────────────────────

    #[test]
    fn lookup_different_host_returns_none() {
        let mut cache = TransportPolicyCache::new();
        cache.insert(make_key("example.com", 1234), policy(), 0);
        let miss = cache.lookup(&make_key("other.com", 1234));
        assert!(miss.is_none());
    }

    // ── Test 3: different net_profile returns None (key isolation) ────────────

    #[test]
    fn lookup_different_net_profile_returns_none() {
        let mut cache = TransportPolicyCache::new();
        let key_wifi = TransportPolicyCacheKey::new(
            "example.com",
            vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
            "browser",
            NetProfileKey { asn: 1234, access_type: AccessTypeTag::WIFI, ech_available: false },
        );
        let key_cell = TransportPolicyCacheKey::new(
            "example.com",
            vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
            "browser",
            NetProfileKey { asn: 1234, access_type: AccessTypeTag::CELLULAR, ech_available: false },
        );
        cache.insert(key_wifi, policy(), 0);
        assert!(cache.lookup(&key_cell).is_none());
    }

    // ── Test 4: 3 consecutive failures invalidates the entry ─────────────────

    #[test]
    fn three_consecutive_failures_invalidates_entry() {
        let mut cache = TransportPolicyCache::new();
        let key = make_key("example.com", 1234);
        cache.insert(key.clone(), policy(), 0);

        assert!(!cache.record_failure("example.com")); // 1st — count=1, not removed yet
        assert!(!cache.record_failure("example.com")); // 2nd — count=2
        let removed = cache.record_failure("example.com"); // 3rd — should remove
        assert!(removed);
        assert!(cache.lookup(&key).is_none());
    }

    // ── Test 5: 7-day TTL prunes expired entries ──────────────────────────────

    #[test]
    fn seven_day_ttl_prunes_expired_entries() {
        let mut cache = TransportPolicyCache::with_ttl(DEFAULT_TTL_SECS);
        let key = make_key("example.com", 1234);
        cache.insert(key, policy(), 0);

        // Simulate time beyond 7 days.
        let future = now_secs() + DEFAULT_TTL_SECS + 1;
        let pruned = cache.prune_expired(future);
        assert_eq!(pruned, 1);
        assert!(cache.is_empty());
    }

    // ── Test 6: ASN-change invalidates entries with old ASN ───────────────────

    #[test]
    fn asn_change_invalidates_matching_entries() {
        let mut cache = TransportPolicyCache::new();
        let key_old_asn = make_key("example.com", 1111);
        let key_new_asn = make_key("example.com", 2222);
        cache.insert(key_old_asn.clone(), policy(), 0);
        cache.insert(key_new_asn.clone(), policy(), 0);

        let removed = cache.invalidate_on_asn_change(1111);
        assert!(removed);
        assert!(cache.lookup(&key_old_asn).is_none());
        assert!(cache.lookup(&key_new_asn).is_some());
    }

    // ── Test 7: SVCB TTL expiry ───────────────────────────────────────────────

    #[test]
    fn svcb_ttl_prunes_before_hard_ttl() {
        let hard_ttl = 3600; // 1 hour
        let mut cache = TransportPolicyCache::with_ttl(hard_ttl);
        let key = make_key("example.com", 1234);
        // Insert with a 60-second SVCB TTL.
        cache.insert(key, policy(), 60);

        // 30 s later — not expired by either TTL.
        let pruned = cache.prune_expired(now_secs() + 30);
        assert_eq!(pruned, 0);

        // 61 s later — expired by SVCB TTL (hard TTL is still 1 h away).
        let pruned = cache.prune_expired(now_secs() + 61);
        assert_eq!(pruned, 1);
    }

    // ── Test 8: ECH change invalidates entries ────────────────────────────────

    #[test]
    fn ech_change_invalidates_entries() {
        let mut cache = TransportPolicyCache::new();
        let key_no_ech = TransportPolicyCacheKey::new(
            "example.com",
            vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
            "browser",
            NetProfileKey { asn: 1234, access_type: AccessTypeTag::WIFI, ech_available: false },
        );
        let key_ech = TransportPolicyCacheKey::new(
            "example.com",
            vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
            "browser",
            NetProfileKey { asn: 1234, access_type: AccessTypeTag::WIFI, ech_available: true },
        );
        cache.insert(key_no_ech.clone(), policy(), 0);
        cache.insert(key_ech.clone(), policy(), 0);

        // ECH became available — invalidate entries that had ech_available=false.
        let removed = cache.invalidate_on_ech_change(true);
        assert!(removed);
        assert!(cache.lookup(&key_no_ech).is_none());
        assert!(cache.lookup(&key_ech).is_some());
    }

    // ── Test 9: AtomicFile write creates the file ─────────────────────────────

    #[test]
    fn atomic_file_write_creates_file() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("ripdpi-test-atomic-{}.json", std::process::id()));
        let af = AtomicFile::new(&path);
        af.write(b"{\"test\":1}").expect("write should succeed");
        let contents = std::fs::read(&path).expect("file should exist after atomic write");
        assert_eq!(contents, b"{\"test\":1}");
        let _ = std::fs::remove_file(&path);
    }
}
