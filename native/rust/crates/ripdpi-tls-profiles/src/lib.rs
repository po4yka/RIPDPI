use boring::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode};
use thiserror::Error;

use sha2::{Digest, Sha256};

mod apply;
mod chrome;
mod edge;
mod firefox;
mod profile;
mod record_choreography;
mod safari;
mod trust;

#[cfg(test)]
mod packet_parity_tests;

pub use profile::{
    profile_catalog, profile_metadata, ProfileCatalog, ProfileConfig, ProfileInvariantStatus, ProfileMetadata,
    ProfileParityTargets, ProfileTemplateMetadata, AVAILABLE_PROFILES,
};
pub use record_choreography::{
    apply_record_choreography, plan_first_flight, planned_record_payload_boundaries, planned_record_payload_lengths,
    selected_record_choreography, RecordChoreography, TlsTemplateFirstFlightPlan,
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
    trust::seed_default_trust(&mut builder)?;
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

pub fn profile_catalog_version() -> &'static str {
    profile::profile_catalog().version
}

pub fn selected_profile_metadata(profile: &str) -> ProfileMetadata {
    profile::profile_metadata(profile)
}

pub fn selected_profile_config(profile: &str) -> &'static ProfileConfig {
    profile::lookup_profile(profile)
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
    if config.alpn_template.is_empty()
        || config.extension_order_family.is_empty()
        || config.grease_style.is_empty()
        || config.supported_groups_profile.is_empty()
        || config.key_share_profile.is_empty()
        || config.record_choreography.is_empty()
        || config.ech_bootstrap_policy.is_empty()
        || config.ech_outer_extension_policy.is_empty()
    {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "template metadata must remain explicit for diagnostics and catalog export",
        });
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
    use serde_json::Value;
    use std::fs;
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::path::Path;
    use std::thread;
    use std::time::Duration;

    fn repo_root() -> std::path::PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../").canonicalize().expect("repo root")
    }

    fn capture_client_hello(profile: &str) -> Vec<u8> {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
        let addr = listener.local_addr().expect("listener addr");
        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept client");
            socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set server read timeout");
            let mut header = [0_u8; 5];
            socket.read_exact(&mut header).expect("read TLS record header");
            let payload_len = u16::from_be_bytes([header[3], header[4]]) as usize;
            let mut payload = vec![0_u8; payload_len];
            socket.read_exact(&mut payload).expect("read TLS record payload");
            let _ = socket.shutdown(Shutdown::Both);
            [header.to_vec(), payload].concat()
        });

        let connector = build_connector(profile, false).expect("build connector");
        let stream = TcpStream::connect(addr).expect("connect loopback socket");
        stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set client read timeout");
        stream.set_write_timeout(Some(Duration::from_secs(5))).expect("set client write timeout");
        let _ = connector.connect("template-validation.test", stream);
        server.join().expect("server join")
    }

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
        assert_eq!("android-stable", profile.browser_track);
        assert_eq!(SslVersion::TLS1_2, profile.min_version);
        assert_eq!(SslVersion::TLS1_3, profile.max_version);
        assert_eq!(&[b"h2".as_slice(), b"http/1.1".as_slice()], profile.alpn);
        assert!(profile.grease_enabled);
        assert!(profile.permute_extensions);
        assert_ne!(517, profile.client_hello_size_hint);
        assert_eq!("chrome-stable", metadata.parity_targets.ja3);
        assert_eq!("chrome-stable", metadata.parity_targets.ja4);
        assert_eq!(ProfileInvariantStatus::AvoidsBlocked517ByteClientHello, metadata.invariant_status);
        assert_eq!("h2_http11", metadata.template.alpn_template);
        assert_eq!("chromium_permuted", metadata.template.extension_order_family);
        assert!(!metadata.template.ech_capable);
    }

    #[test]
    fn desktop_and_ech_profiles_publish_distinct_template_metadata() {
        let desktop = selected_profile_metadata("chrome_desktop_stable");
        let ech = selected_profile_metadata("firefox_ech_stable");

        assert_eq!("desktop-stable", desktop.parity_targets.browser_track);
        assert_eq!("chrome-desktop-stable", desktop.parity_targets.ja3);
        assert!(!desktop.template.ech_capable);

        assert_eq!("ech-stable", ech.parity_targets.browser_track);
        assert_eq!("firefox-ech-stable", ech.parity_targets.ja3);
        assert!(ech.template.ech_capable);
        assert_eq!("firefox_ech_grease", ech.template.grease_style);
        assert_eq!("https_rr_or_cdn_fallback", ech.template.ech_bootstrap_policy);
        assert_eq!(Some("adguard"), ech.template.ech_bootstrap_resolver_id);
        assert_eq!("preserve_ech_or_grease", ech.template.ech_outer_extension_policy);
    }

    #[test]
    fn ech_first_flight_plan_carries_bootstrap_and_outer_extension_policy() {
        let payload = capture_client_hello("firefox_ech_stable");
        let plan = plan_first_flight("firefox_ech_stable", &payload).expect("ech first flight plan");

        assert_eq!(plan.record_choreography.as_str(), "sni_ech_tail_adaptive");
        assert_eq!(plan.ech_bootstrap_policy, "https_rr_or_cdn_fallback");
        assert_eq!(plan.ech_bootstrap_resolver_id, Some("adguard"));
        assert_eq!(plan.ech_outer_extension_policy, "preserve_ech_or_grease");
        assert_eq!(plan.grease_style, "firefox_ech_grease");
        assert!(plan.ech_capable_template);
        assert!(plan.ech_present_in_input || plan.ech_outer_extension_policy == "preserve_ech_or_grease");
    }

    #[test]
    fn rotated_selection_uses_profile_set_id_in_hash() {
        let allowed = vec!["chrome_stable".to_string(), "firefox_ech_stable".to_string()];
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

    #[test]
    fn phase11_acceptance_fixture_covers_all_catalog_profiles() {
        let fixture_path = repo_root().join("contract-fixtures/phase11_tls_template_acceptance.json");
        let fixture: Value =
            serde_json::from_str(&fs::read_to_string(fixture_path).expect("phase11 acceptance fixture")).expect("json");

        assert_eq!(fixture["schemaVersion"].as_u64(), Some(2));
        assert_eq!(fixture["corpusId"].as_str(), Some("phase11_tls_template_acceptance"));
        assert_eq!(fixture["catalogVersion"].as_str(), Some(profile_catalog_version()));
        assert_eq!(fixture["profileSetId"].as_str(), Some(profile_catalog().default_profile_set_id));
        let coverage_targets = fixture["coverageTargets"].as_object().expect("coverageTargets object");
        let min_cdn = coverage_targets["minimumAcceptedCdnStacks"].as_u64().expect("minimumAcceptedCdnStacks");
        let min_server = coverage_targets["minimumAcceptedServerStacks"].as_u64().expect("minimumAcceptedServerStacks");
        let min_total =
            coverage_targets["minimumAcceptedStacksPerProfile"].as_u64().expect("minimumAcceptedStacksPerProfile");
        let min_ech = coverage_targets["minimumAcceptedEchStacks"].as_u64().expect("minimumAcceptedEchStacks");
        let stacks = fixture["stacks"].as_array().expect("stacks array");
        let stack_index = stacks
            .iter()
            .map(|entry| (entry["id"].as_str().expect("stack id"), entry))
            .collect::<std::collections::BTreeMap<_, _>>();

        let entries = fixture["profiles"].as_array().expect("profiles array");
        let ids = entries.iter().filter_map(|entry| entry["id"].as_str()).collect::<std::collections::BTreeSet<_>>();
        let expected = AVAILABLE_PROFILES.iter().copied().collect::<std::collections::BTreeSet<_>>();
        assert_eq!(ids, expected);

        for id in AVAILABLE_PROFILES {
            let entry =
                entries.iter().find(|entry| entry["id"].as_str() == Some(*id)).expect("fixture entry for profile");
            let metadata = selected_profile_metadata(id);

            assert_eq!(entry["browserFamily"].as_str(), Some(metadata.parity_targets.browser_family));
            assert_eq!(entry["browserTrack"].as_str(), Some(metadata.parity_targets.browser_track));
            assert_eq!(entry["alpnTemplate"].as_str(), Some(metadata.template.alpn_template));
            assert_eq!(entry["extensionOrderFamily"].as_str(), Some(metadata.template.extension_order_family));
            assert_eq!(entry["greaseStyle"].as_str(), Some(metadata.template.grease_style));
            assert_eq!(entry["supportedGroupsProfile"].as_str(), Some(metadata.template.supported_groups_profile));
            assert_eq!(entry["keyShareProfile"].as_str(), Some(metadata.template.key_share_profile));
            assert_eq!(entry["recordChoreography"].as_str(), Some(metadata.template.record_choreography));
            assert_eq!(entry["echCapable"].as_bool(), Some(metadata.template.ech_capable));
            assert_eq!(entry["echBootstrapPolicy"].as_str(), Some(metadata.template.ech_bootstrap_policy));
            assert_eq!(entry["echBootstrapResolverId"].as_str(), metadata.template.ech_bootstrap_resolver_id);
            assert_eq!(entry["echOuterExtensionPolicy"].as_str(), Some(metadata.template.ech_outer_extension_policy));
            let accepted_stacks = entry["acceptedStacks"].as_array().expect("acceptedStacks array");
            let acceptance_results = entry["acceptanceResults"].as_array().expect("acceptanceResults array");
            let accepted_from_results = acceptance_results
                .iter()
                .filter(|value| value["status"].as_str() == Some("accepted"))
                .map(|value| value["stackId"].as_str().expect("stackId"))
                .collect::<std::collections::BTreeSet<_>>();
            let accepted_from_entry = accepted_stacks
                .iter()
                .map(|value| value.as_str().expect("accepted stack id"))
                .collect::<std::collections::BTreeSet<_>>();
            assert_eq!(accepted_from_entry, accepted_from_results);

            let mut accepted_cdn = 0_u64;
            let mut accepted_server = 0_u64;
            let mut accepted_ech = 0_u64;
            let profile_ech_capable = metadata.template.ech_capable;
            for stack_id in accepted_from_entry {
                let stack = stack_index.get(stack_id).expect("known stack");
                match stack["class"].as_str() {
                    Some("cdn") => accepted_cdn += 1,
                    Some("server") => accepted_server += 1,
                    other => panic!("unexpected stack class: {other:?}"),
                }
                if profile_ech_capable && stack["echCapable"].as_bool() == Some(true) {
                    accepted_ech += 1;
                }
            }

            let summary = entry["acceptanceSummary"].as_object().expect("acceptanceSummary object");
            assert_eq!(summary["acceptedCdnStacks"].as_u64(), Some(accepted_cdn));
            assert_eq!(summary["acceptedServerStacks"].as_u64(), Some(accepted_server));
            assert_eq!(summary["acceptedEchStacks"].as_u64(), Some(accepted_ech));
            assert_eq!(summary["acceptedTotalStacks"].as_u64(), Some(accepted_cdn + accepted_server));
            assert!(accepted_cdn >= min_cdn);
            assert!(accepted_server >= min_server);
            assert!((accepted_cdn + accepted_server) >= min_total);
            if profile_ech_capable {
                assert!(accepted_ech >= min_ech);
            }
        }
    }
}
