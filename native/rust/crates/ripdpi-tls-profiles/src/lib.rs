use boring::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode};
use thiserror::Error;

use sha2::{Digest, Sha256};

mod apply;
mod chrome;
mod edge;
mod firefox;
mod profile;
mod safari;

pub use profile::{
    profile_catalog, profile_metadata, ProfileCatalog, ProfileConfig, ProfileInvariantStatus, ProfileMetadata,
    ProfileParityTargets, AVAILABLE_PROFILES,
};

#[derive(Debug, Error)]
pub enum Error {
    #[error("BoringSSL error: {0}")]
    Ssl(#[from] boring::error::ErrorStack),
    #[error("TLS profile invariant failed for {profile}: {reason}")]
    Invariant { profile: &'static str, reason: &'static str },
}

/// Returns a configured builder that the caller can further customize before
/// calling `.build()`. Use this when you need to set additional options
/// (e.g., custom verify callback, session_id).
pub fn configure_builder(profile: &str) -> Result<SslConnectorBuilder, Error> {
    let mut builder = SslConnector::builder(SslMethod::tls())?;
    let config = profile::lookup_profile(profile);
    validate_profile_config(config)?;
    apply::apply_profile(&mut builder, config)?;
    Ok(builder)
}

/// Returns a finalized `SslConnector` for synchronous TLS connections.
pub fn build_connector(profile: &str, verify: bool) -> Result<SslConnector, Error> {
    let mut builder = configure_builder(profile)?;
    if !verify {
        builder.set_verify(SslVerifyMode::NONE);
    }
    Ok(builder.build())
}

/// Default weights by browser market share: Chrome 65%, Firefox 20%, Safari 10%, Edge 5%.
const DEFAULT_WEIGHTS: &[(&str, u32)] =
    &[("chrome_stable", 65), ("firefox_stable", 20), ("safari_stable", 10), ("edge_stable", 5)];

pub fn profile_catalog_version() -> &'static str {
    profile::profile_catalog().version
}

pub fn selected_profile_metadata(profile: &str) -> ProfileMetadata {
    profile::profile_metadata(profile)
}

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
        DEFAULT_WEIGHTS.iter().filter(|(name, _)| allowed_profiles.iter().any(|a| a == *name)).copied().collect()
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
        // Return the static profile name from lookup to get 'static lifetime
        return profile::lookup_profile(default_profile).name;
    }
    select_rotated_profile(domain, session_seed, allowed_profiles)
}

fn weighted_pick(candidates: &[(&str, u32)], hash: u64) -> &'static str {
    let total: u32 = candidates.iter().map(|(_, w)| w).sum();
    if total == 0 {
        return "chrome_stable";
    }
    let target = (hash % total as u64) as u32;
    let mut cumulative = 0u32;
    for (name, weight) in candidates {
        cumulative += weight;
        if target < cumulative {
            return profile::lookup_profile(name).name;
        }
    }
    profile::lookup_profile(candidates.last().unwrap().0).name
}

fn stable_rotation_hash(authority: &str, session_seed: u64, profile_set_id: &str) -> u64 {
    let digest = Sha256::digest(format!("{authority}|{session_seed}|{profile_set_id}"));
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(&digest[..8]);
    u64::from_be_bytes(bytes)
}

fn validate_profile_config(config: &ProfileConfig) -> Result<(), Error> {
    if config.client_hello_size_hint == 517 {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "517-byte ClientHello is blocked by known middlebox fingerprinting rules",
        });
    }
    if config.client_hello_size_hint < 480 || config.client_hello_size_hint > 540 {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "ClientHello size hint drifted outside the expected mimicry envelope",
        });
    }
    let supports_h2 = config.alpn.iter().any(|value| *value == b"h2");
    let supports_http11 = config.alpn.iter().any(|value| *value == b"http/1.1");
    if !supports_h2 || !supports_http11 {
        return Err(Error::Invariant { profile: config.name, reason: "ALPN list must include both h2 and http/1.1" });
    }
    if config.ja3_parity_target.is_empty() || config.ja4_parity_target.is_empty() {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "JA3/JA4 parity targets must be declared for telemetry and diagnostics",
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use boring::ssl::SslVersion;

    #[test]
    fn chrome_builder_succeeds() {
        let connector = build_connector("chrome_stable", true);
        assert!(connector.is_ok(), "Chrome profile should build: {:?}", connector.err());
    }

    #[test]
    fn firefox_builder_succeeds() {
        let connector = build_connector("firefox_stable", true);
        assert!(connector.is_ok(), "Firefox profile should build: {:?}", connector.err());
    }

    #[test]
    fn unknown_profile_falls_back_to_chrome() {
        let connector = build_connector("unknown_profile", true);
        assert!(connector.is_ok());
    }

    #[test]
    fn configure_builder_allows_customization() {
        let mut builder = configure_builder("chrome_stable").unwrap();
        builder.set_verify(SslVerifyMode::NONE);
        let _ = builder.build();
    }

    #[test]
    fn unknown_profile_lookup_uses_chrome_profile() {
        let profile = profile::lookup_profile("native_default");
        assert_eq!("chrome_stable", profile.name);
    }

    #[test]
    fn chrome_profile_matches_phase_zero_invariants() {
        let profile = profile::lookup_profile("chrome_stable");
        let metadata = selected_profile_metadata("chrome_stable");

        assert_eq!("chrome_stable", profile.name);
        assert_eq!(SslVersion::TLS1_2, profile.min_version);
        assert_eq!(SslVersion::TLS1_3, profile.max_version);
        assert_eq!(&[b"h2".as_slice(), b"http/1.1".as_slice()], profile.alpn);
        assert!(profile.grease_enabled);
        assert!(profile.permute_extensions);
        assert_ne!(517, profile.client_hello_size_hint);
        assert_eq!("chrome-stable", metadata.parity_targets.ja3);
        assert_eq!("chrome-stable", metadata.parity_targets.ja4);
        assert_eq!(ProfileInvariantStatus::AvoidsBlocked517ByteClientHello, metadata.invariant_status);
    }

    #[test]
    fn rotated_selection_uses_profile_set_id_in_hash() {
        let allowed = vec!["chrome_stable".to_string(), "firefox_stable".to_string()];
        let seeds = [42_u64, 1337_u64, 9_001_u64, 65_535_u64];

        for seed in seeds {
            let left = select_rotated_profile_with_set("example.org", seed, "set-a", &allowed);
            let repeated_left = select_rotated_profile_with_set("example.org", seed, "set-a", &allowed);
            assert_eq!(left, repeated_left);
        }

        let distinct_across_sets = seeds.into_iter().any(|seed| {
            select_rotated_profile_with_set("example.org", seed, "set-a", &allowed)
                != select_rotated_profile_with_set("example.org", seed, "set-b", &allowed)
        });
        assert!(distinct_across_sets || allowed.len() == 1);
    }

    #[test]
    fn catalog_version_is_exposed() {
        assert_eq!("v1", profile_catalog_version());
    }

    #[test]
    fn all_catalog_profiles_publish_safe_parity_metadata() {
        for name in AVAILABLE_PROFILES {
            let config = profile::lookup_profile(name);
            validate_profile_config(config).expect("catalog profile should remain valid");
            let metadata = selected_profile_metadata(name);
            assert_eq!(profile_catalog_version(), metadata.catalog_version);
            assert!(!metadata.parity_targets.browser_family.is_empty());
            assert!(!metadata.parity_targets.browser_track.is_empty());
            assert!(!metadata.parity_targets.ja3.is_empty());
            assert!(!metadata.parity_targets.ja4.is_empty());
            assert_ne!(517, metadata.client_hello_size_hint);
        }
    }
}
