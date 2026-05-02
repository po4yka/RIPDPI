use sha2::{Digest, Sha256};

use crate::profile;

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
