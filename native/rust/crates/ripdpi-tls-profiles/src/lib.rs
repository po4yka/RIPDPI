use boring::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode};
use thiserror::Error;

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

mod apply;
mod chrome;
mod edge;
mod firefox;
mod profile;
mod safari;

pub use profile::{ProfileConfig, AVAILABLE_PROFILES};

#[derive(Debug, Error)]
pub enum Error {
    #[error("BoringSSL error: {0}")]
    Ssl(#[from] boring::error::ErrorStack),
}

/// Returns a configured builder that the caller can further customize before
/// calling `.build()`. Use this when you need to set additional options
/// (e.g., custom verify callback, session_id).
pub fn configure_builder(profile: &str) -> Result<SslConnectorBuilder, Error> {
    let mut builder = SslConnector::builder(SslMethod::tls())?;
    let config = profile::lookup_profile(profile);
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

/// Select a TLS profile using deterministic weighted rotation.
/// The same (domain, session_seed) pair always returns the same profile
/// within a session, but different sessions get different profiles.
pub fn select_rotated_profile(domain: &str, session_seed: u64, allowed_profiles: &[String]) -> &'static str {
    let candidates: Vec<(&str, u32)> = if allowed_profiles.is_empty() {
        DEFAULT_WEIGHTS.to_vec()
    } else {
        DEFAULT_WEIGHTS.iter().filter(|(name, _)| allowed_profiles.iter().any(|a| a == *name)).copied().collect()
    };
    if candidates.is_empty() {
        return "chrome_stable";
    }
    let mut hasher = DefaultHasher::new();
    domain.hash(&mut hasher);
    session_seed.hash(&mut hasher);
    let hash = hasher.finish();
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

#[cfg(test)]
mod tests {
    use super::*;

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
}
