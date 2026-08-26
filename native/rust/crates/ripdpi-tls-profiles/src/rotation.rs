use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};

use sha2::{Digest, Sha256};

use crate::profile;

/// Telemetry counter `tls.fingerprint_rotation_active`: the cumulative number
/// of rotated outbound connections resolved through
/// [`resolve_connection_profile`] (one bump per rotating resolution). Exposed
/// for the telemetry layer to poll.
static FINGERPRINT_ROTATION_ACTIVE: AtomicU64 = AtomicU64::new(0);

/// Returns the cumulative count of rotated TLS connections resolved through
/// [`resolve_connection_profile`] — the `tls.fingerprint_rotation_active`
/// telemetry counter.
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
    rotation_count: AtomicU64,
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
        Ok(Self {
            pool: resolved,
            profile_set_id: profile::profile_catalog().default_profile_set_id,
            rotation_count: AtomicU64::new(0),
        })
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
    /// selection on the selector-local counter ([`Self::selection_count`]).
    /// Process-global telemetry is bumped by [`resolve_connection_profile`]
    /// when a rotated connection is resolved through it, so exact counting
    /// stays observable per instance without cross-instance interference.
    #[must_use]
    pub fn select(&self, authority: &str, session_seed: u64) -> &'static str {
        let hash = stable_rotation_hash(authority, session_seed, self.profile_set_id);
        let span = self.pool.len() as u64;
        let idx = usize::try_from(hash % span).unwrap_or(0);
        let chosen = self.pool[idx];
        self.rotation_count.fetch_add(1, Ordering::Relaxed);
        tracing::debug!(profile = chosen, "tls fingerprint selected");
        chosen
    }

    /// Selector-local count of selections. Exact per-instance counting is
    /// race-free regardless of which test runner (cargo test / nextest) or
    /// sibling tests execute concurrently; the process-global
    /// [`fingerprint_rotation_count`] is shared by all rotating resolutions.
    #[cfg(test)]
    fn selection_count(&self) -> u64 {
        self.rotation_count.load(Ordering::Relaxed)
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

/// Reserved `tls_fingerprint_profile` value that activates per-connection uTLS
/// fingerprint rotation (`Profile::Rotating`). When an outbound's configured
/// profile equals this marker, [`resolve_connection_profile`] draws a fresh
/// fingerprint from the process-global default rotation pool for every
/// connection instead of mimicking one fixed browser.
pub const ROTATING_PROFILE_MARKER: &str = "rotating";

/// Process-global selector over the default one-per-family pool, built lazily so
/// the rotation surface costs nothing until a profile opts in.
static DEFAULT_SELECTOR: OnceLock<RotatingProfileSelector> = OnceLock::new();

/// Monotonic per-connection seed so adjacent outbound connections rotate to a
/// fresh (but deterministic-per-seed) fingerprint. Wrapping on overflow is
/// harmless — the seed only needs to vary between connections.
static ROTATION_CONNECTION_SEQ: AtomicU64 = AtomicU64::new(0);

/// Whether `name` requests per-connection rotation rather than a fixed profile.
#[must_use]
pub fn is_rotating_profile(name: &str) -> bool {
    name == ROTATING_PROFILE_MARKER
}

/// Resolve the concrete `'static` profile name an outbound TLS connection should
/// mimic when connecting to `authority`.
///
/// If `requested` is [`ROTATING_PROFILE_MARKER`], a fresh fingerprint is drawn
/// from the default rotation pool keyed by `authority` and a monotonic
/// per-connection seed, incrementing the `tls.fingerprint_rotation_active`
/// counter once. Otherwise the requested name is canonicalised via
/// [`profile::lookup_profile`] (unknown names keep the historical
/// default-profile fallback). The returned name is suitable for
/// [`crate::configure_builder`], [`crate::build_connector`], and
/// [`crate::selected_profile_config`].
#[must_use]
pub fn resolve_connection_profile(requested: &str, authority: &str) -> &'static str {
    if is_rotating_profile(requested) {
        let selector = DEFAULT_SELECTOR.get_or_init(RotatingProfileSelector::with_default_pool);
        let seed = ROTATION_CONNECTION_SEQ.fetch_add(1, Ordering::Relaxed);
        let chosen = selector.select(authority, seed);
        FINGERPRINT_ROTATION_ACTIVE.fetch_add(1, Ordering::Relaxed);
        chosen
    } else {
        profile::lookup_profile(requested).name
    }
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

    use super::{
        DEFAULT_ROTATION_POOL, ROTATING_PROFILE_MARKER, RotatingProfileSelector, fingerprint_rotation_count,
        is_rotating_profile, resolve_connection_profile,
    };

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
        // Selector-local counting: the exact assertion stays valid no matter
        // which runner (cargo test / nextest) or sibling tests run in parallel.
        let selector = RotatingProfileSelector::with_default_pool();
        for seed in 0..10 {
            let _ = selector.select("c.example", seed);
        }
        assert_eq!(selector.selection_count(), 10);
    }

    #[test]
    fn resolve_connection_profile_passes_through_fixed_names() {
        assert!(is_rotating_profile(ROTATING_PROFILE_MARKER));
        assert!(!is_rotating_profile("firefox_stable"));
        // A fixed profile name resolves to itself.
        assert_eq!(resolve_connection_profile("firefox_stable", "x.example"), "firefox_stable");
        // Unknown names keep the historical default-profile fallback (chrome_stable).
        assert_eq!(resolve_connection_profile("unknown_profile_xyz", "x.example"), "chrome_stable");
    }

    #[test]
    fn resolve_connection_profile_rotates_and_counts_for_the_marker() {
        // Only this test reaches the global telemetry counter — every other
        // test drives its own selector instance, whose selections no longer
        // touch the process-global counter. The exact delta assertion is
        // therefore deterministic under both cargo test threads and nextest
        // per-process isolation.
        let before = fingerprint_rotation_count();
        let resolved: Vec<&str> =
            (0..100).map(|_| resolve_connection_profile(ROTATING_PROFILE_MARKER, "rot.example")).collect();
        // Never leaks the marker; every resolution is a real pool profile.
        for name in &resolved {
            assert_ne!(*name, ROTATING_PROFILE_MARKER);
            assert!(DEFAULT_ROTATION_POOL.contains(name), "resolved {name} is not in the rotation pool");
        }
        // The marker actually rotates the fingerprint across connections.
        let distinct: HashSet<&str> = resolved.iter().copied().collect();
        assert!(distinct.len() >= 2, "marker did not rotate across connections: {distinct:?}");
        // Each rotated resolve bumps the pull-model telemetry counter exactly once.
        assert_eq!(fingerprint_rotation_count() - before, 100);
    }
}
