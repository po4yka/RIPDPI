use std::sync::atomic::{AtomicU64, Ordering};

use sha2::{Digest, Sha256};

use crate::profile;

/// Telemetry counter `tls.fingerprint_rotation_active`: the cumulative number
/// of rotated outbound handshakes (one per [`RotatingProfileSelector::select`]
/// call). Exposed for the telemetry layer to poll.
static FINGERPRINT_ROTATION_ACTIVE: AtomicU64 = AtomicU64::new(0);

/// Returns the cumulative count of rotated TLS handshakes — the
/// `tls.fingerprint_rotation_active` telemetry counter.
#[must_use]
pub fn fingerprint_rotation_count() -> u64 {
    FINGERPRINT_ROTATION_ACTIVE.load(Ordering::Relaxed)
}

/// Default per-connection rotation pool: the current profile for each mimicked
/// browser family, selected uniformly so JA3/JA4 fingerprints spread evenly
/// across outbound connections.
///
/// An iOS 18 Safari family is intentionally absent until an authentic
/// ClientHello template (verified against refraction-networking/utls reference
/// captures) is added to the profile catalog — a fabricated mobile fingerprint
/// is worse than none, because a wrong JA3/JA4 is itself a detection signal.
/// See `docs/native/tls-fingerprint-rotation.md`.
const DEFAULT_ROTATION_POOL: &[&str] = &["chrome_stable", "firefox_stable", "safari_stable", "edge_stable"];

/// Selects a fresh ClientHello fingerprint profile for every outbound TLS
/// connection from a fixed pool of browser fingerprints, defeating JA3/JA4
/// session-correlation by nation-state DPI.
///
/// Selection is deterministic for a given `(authority, session_seed)` so a
/// reconnect inside one session keeps a stable fingerprint, but uniformly
/// distributed across the pool as `session_seed` varies per connection. Pass a
/// fresh `session_seed` per outbound connection to rotate.
pub struct RotatingProfileSelector {
    pool: Vec<&'static str>,
    profile_set_id: &'static str,
}

impl RotatingProfileSelector {
    /// Build a selector over `pool`. Every entry must be a known profile in
    /// [`profile::AVAILABLE_PROFILES`]; an unknown name is rejected so a typo
    /// can never silently collapse the pool to a single default fingerprint.
    ///
    /// # Errors
    /// Returns [`crate::Error::Invariant`] if the pool is empty or names an
    /// unknown profile.
    pub fn new(pool: &[&str]) -> Result<Self, crate::Error> {
        if pool.is_empty() {
            return Err(crate::Error::Invariant {
                profile: "rotating_profile_selector",
                reason: "rotation pool must not be empty",
            });
        }
        let mut resolved = Vec::with_capacity(pool.len());
        for name in pool {
            let canonical = profile::AVAILABLE_PROFILES.iter().copied().find(|known| known == name).ok_or(
                crate::Error::Invariant {
                    profile: "rotating_profile_selector",
                    reason: "rotation pool contains an unknown profile name",
                },
            )?;
            resolved.push(canonical);
        }
        Ok(Self { pool: resolved, profile_set_id: profile::profile_catalog().default_profile_set_id })
    }

    /// A selector over the default one-per-browser-family pool
    /// ([`DEFAULT_ROTATION_POOL`]).
    #[must_use]
    pub fn with_default_pool() -> Self {
        // DEFAULT_ROTATION_POOL is a compile-time subset of AVAILABLE_PROFILES.
        Self::new(DEFAULT_ROTATION_POOL).expect("default rotation pool is valid")
    }

    /// The profiles this selector rotates over.
    #[must_use]
    pub fn pool(&self) -> &[&'static str] {
        &self.pool
    }

    /// Pick a fingerprint profile for one outbound connection and record the
    /// rotation in the `tls.fingerprint_rotation_active` telemetry counter.
    #[must_use]
    pub fn select(&self, authority: &str, session_seed: u64) -> &'static str {
        let hash = stable_rotation_hash(authority, session_seed, self.profile_set_id);
        let span = self.pool.len() as u64;
        let idx = usize::try_from(hash % span).unwrap_or(0);
        let chosen = self.pool[idx];
        FINGERPRINT_ROTATION_ACTIVE.fetch_add(1, Ordering::Relaxed);
        tracing::debug!(profile = chosen, "tls.fingerprint_rotation_active");
        chosen
    }
}

/// Default rotation weights bias toward Android/Chromium while still sampling
/// desktop and ECH-capable families.
const DEFAULT_WEIGHTS: &[(&str, u32)] = &[
    ("chrome_stable", 40),
    ("chrome_desktop_stable", 15),
    ("firefox_stable", 20),
    ("firefox_ech_stable", 10),
    ("safari_stable", 10),
    ("edge_stable", 5),
];

/// Select a TLS profile using deterministic weighted rotation.
/// The same (domain, session_seed) pair always returns the same profile
/// within a session, but different sessions get different profiles.
pub fn select_rotated_profile(domain: &str, session_seed: u64, allowed_profiles: &[String]) -> &'static str {
    select_rotated_profile_with_set(
        domain,
        session_seed,
        profile::profile_catalog().default_profile_set_id,
        allowed_profiles,
    )
}

/// Deterministic rotation that stays stable for a single catalog/profile-set version.
pub fn select_rotated_profile_with_set(
    authority: &str,
    session_seed: u64,
    profile_set_id: &str,
    allowed_profiles: &[String],
) -> &'static str {
    let candidates: Vec<(&str, u32)> = if allowed_profiles.is_empty() {
        DEFAULT_WEIGHTS.to_vec()
    } else {
        DEFAULT_WEIGHTS
            .iter()
            .filter(|(name, _)| allowed_profiles.iter().any(|allowed| allowed == *name))
            .copied()
            .collect()
    };
    if candidates.is_empty() {
        return "chrome_stable";
    }
    let hash = stable_rotation_hash(authority, session_seed, profile_set_id);
    weighted_pick(&candidates, hash)
}

/// Main entry point: returns profile name respecting rotation config.
pub fn select_profile_for_connection(
    rotation_enabled: bool,
    default_profile: &str,
    domain: &str,
    session_seed: u64,
    allowed_profiles: &[String],
) -> &'static str {
    if !rotation_enabled {
        // Return the static profile name from lookup to get 'static lifetime.
        return profile::lookup_profile(default_profile).name;
    }
    select_rotated_profile(domain, session_seed, allowed_profiles)
}

fn weighted_pick(candidates: &[(&str, u32)], hash: u64) -> &'static str {
    let total: u32 = candidates.iter().map(|(_, weight)| weight).sum();
    if total == 0 {
        return "chrome_stable";
    }
    let target = (hash % u64::from(total)) as u32;
    let mut cumulative = 0_u32;
    for (name, weight) in candidates {
        cumulative += weight;
        if target < cumulative {
            return profile::lookup_profile(name).name;
        }
    }
    profile::lookup_profile(candidates.last().expect("non-empty candidate list").0).name
}

fn stable_rotation_hash(authority: &str, session_seed: u64, profile_set_id: &str) -> u64 {
    let digest = Sha256::digest(format!("{authority}|{session_seed}|{profile_set_id}"));
    let mut bytes = [0_u8; 8];
    bytes.copy_from_slice(&digest[..8]);
    u64::from_be_bytes(bytes)
}

#[cfg(test)]
mod selector_tests {
    use std::collections::{HashMap, HashSet};

    use super::{RotatingProfileSelector, fingerprint_rotation_count};

    #[test]
    fn new_rejects_empty_and_unknown_pools() {
        assert!(RotatingProfileSelector::new(&[]).is_err());
        assert!(RotatingProfileSelector::new(&["chrome_stable", "not_a_browser"]).is_err());
        assert!(RotatingProfileSelector::new(&["chrome_stable", "firefox_stable"]).is_ok());
    }

    #[test]
    fn default_pool_covers_the_browser_families() {
        let selector = RotatingProfileSelector::with_default_pool();
        for family in ["chrome_stable", "firefox_stable", "safari_stable", "edge_stable"] {
            assert!(selector.pool().contains(&family), "default pool missing {family}");
        }
    }

    #[test]
    fn selection_is_deterministic_per_authority_seed() {
        let selector = RotatingProfileSelector::with_default_pool();
        assert_eq!(selector.select("a.example", 7), selector.select("a.example", 7));
    }

    #[test]
    fn rotation_yields_more_than_one_fingerprint_across_connections() {
        let selector = RotatingProfileSelector::with_default_pool();
        let distinct: HashSet<&str> = (0..100).map(|seed| selector.select("d.example", seed)).collect();
        assert!(distinct.len() >= 2, "rotation produced only one fingerprint: {distinct:?}");
    }

    #[test]
    fn distribution_is_roughly_uniform_over_1000_trials() {
        let selector = RotatingProfileSelector::with_default_pool();
        let pool_size = selector.pool().len();
        let trials: u64 = 1000;
        let mut counts: HashMap<&str, u32> = HashMap::new();
        for seed in 0..trials {
            *counts.entry(selector.select("example.com", seed)).or_default() += 1;
        }
        for name in selector.pool() {
            assert!(counts.get(name).copied().unwrap_or(0) > 0, "profile {name} never selected");
        }
        #[allow(clippy::cast_precision_loss)]
        let expected = trials as f64 / pool_size as f64;
        for (name, count) in &counts {
            let ratio = f64::from(*count) / expected;
            assert!(ratio > 0.6 && ratio < 1.4, "profile {name} skewed: {count} vs ~{expected:.0} (ratio {ratio:.2})");
        }
    }

    #[test]
    fn counter_increments_once_per_selection() {
        // Relies on nextest's process-per-test isolation: no other test mutates
        // this process's FINGERPRINT_ROTATION_ACTIVE concurrently.
        let before = fingerprint_rotation_count();
        let selector = RotatingProfileSelector::with_default_pool();
        for seed in 0..10 {
            let _ = selector.select("c.example", seed);
        }
        assert_eq!(fingerprint_rotation_count() - before, 10);
    }
}
