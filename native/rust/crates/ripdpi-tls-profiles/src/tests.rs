use boring::ssl::{SslVerifyMode, SslVersion};
use golden_test_support::repo_root;
use serde_json::Value;
use std::fs;
use std::io::Read;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use crate::{
    AVAILABLE_PROFILES, ProfileInvariantStatus, build_connector, configure_builder,
    invariants::validate_profile_config, plan_first_flight, profile, profile_catalog, profile_catalog_version,
    select_rotated_profile_with_set, selected_profile_metadata,
};

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
fn native_default_alias_is_consistent_across_lookup_and_invariants() {
    use crate::profile_invariants_pass;
    // The documented alias must resolve like a catalog entry AND pass the
    // invariant gate — one identifier cannot be valid at connect time and
    // invalid at validate time.
    assert_eq!(crate::selected_profile_config("native_default").name, "chrome_stable");
    assert!(profile_invariants_pass("native_default"));
    // Truly unknown names keep the lookup fallback but fail validation gates.
    assert_eq!(profile::lookup_profile("definitely_not_a_profile").name, "chrome_stable");
    assert!(!profile_invariants_pass("definitely_not_a_profile"));
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
        let entry = entries.iter().find(|entry| entry["id"].as_str() == Some(*id)).expect("fixture entry for profile");
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

/// Chrome 120 ClientHello fingerprint regression tests.
///
/// These tests are deterministic — they operate entirely on the `ProfileConfig`
/// struct fields with no network I/O and no real TLS handshake. The intent
/// translates the spec's Go uTLS v1.8.2 pin: assert that the Rust Chrome 120
/// profile configuration cannot silently drift from its frozen fingerprint,
/// covering the padding-extension regression and GREASE ECH cipher-mismatch
/// described in uTLS PR #375.
///
/// A JA4-like fingerprint is derived by hashing a pipe-delimited canonical
/// string of the fields that control the on-wire ClientHello structure. The
/// frozen constant must be updated intentionally if the profile is changed.
#[cfg(test)]
mod chrome_120_fingerprint_regression {
    use sha2::{Digest, Sha256};

    use crate::profile::lookup_profile;

    /// Canonical field separator used when building the fingerprint input.
    const SEP: &str = "|";

    /// Frozen SHA-256 fingerprint of the Chrome 120 (`chrome_stable`) profile.
    ///
    /// Computed from the pipe-delimited canonical string:
    ///   cipher_list_tls12 | ciphersuites_tls13 | curves | sigalgs |
    ///   alpn (comma-joined) | grease_style | extension_order_family |
    ///   supported_groups_profile | key_share_profile | ech_capable
    ///
    /// To regenerate after an intentional profile update run:
    ///   cargo test -p ripdpi-tls-profiles -- --nocapture chrome_120_frozen_fingerprint_hash_unchanged
    const CHROME_120_FROZEN_FINGERPRINT: &str = "a6e59fca8d6f7c665986a67c95d29117a21b56f5e1f838b9067815fef08738e1";

    fn compute_profile_fingerprint(profile_name: &str) -> String {
        let p = lookup_profile(profile_name);
        let alpn_str =
            p.alpn.iter().map(|proto| std::str::from_utf8(proto).unwrap_or("?")).collect::<Vec<_>>().join(",");
        let canonical = [
            p.cipher_list_tls12,
            p.ciphersuites_tls13,
            p.curves,
            p.sigalgs,
            alpn_str.as_str(),
            p.grease_style,
            p.extension_order_family,
            p.supported_groups_profile,
            p.key_share_profile,
            if p.ech_capable { "true" } else { "false" },
        ]
        .join(SEP);
        let mut hasher = Sha256::new();
        hasher.update(canonical.as_bytes());
        hasher.finalize().iter().map(|b| format!("{b:02x}")).collect()
    }

    /// Assert 1: extension_order_family is the Chromium permuted family.
    ///
    /// Chrome 120 uses `chromium_permuted` — the padding extension is implicit
    /// in this family's size budget. Any change to this field indicates a
    /// profile-family migration that could break fingerprint mimicry.
    #[test]
    fn chrome_120_extension_order_family_is_chromium_permuted() {
        let profile = lookup_profile("chrome_stable");
        assert_eq!(
            profile.extension_order_family, "chromium_permuted",
            "Chrome 120 profile must use chromium_permuted extension order family; \
             changing this breaks padding-extension budget"
        );
    }

    /// Assert 2: padding extension presence — Chrome 120 must NOT be ECH-capable.
    ///
    /// uTLS v1.8.2 restored the padding extension after PQ key shares altered
    /// packet sizing. In this Rust translation the padding budget is preserved by
    /// keeping `ech_capable = false` on the Chrome 120 profile; enabling ECH
    /// would alter the extension set and eliminate the padding slot.
    #[test]
    fn chrome_120_padding_extension_present_via_non_ech_profile() {
        let profile = lookup_profile("chrome_stable");
        assert!(
            !profile.ech_capable,
            "Chrome 120 profile must remain ECH-disabled; enabling ECH removes the \
             padding-extension slot restored by uTLS v1.8.2 (PR #375)"
        );
        // The client_hello_size_hint encodes the padding budget: it must not be
        // the blocked 517-byte value and must sit within the mimicry envelope.
        assert_ne!(profile.client_hello_size_hint, 517, "Chrome 120 must avoid the blocked 517-byte fingerprint");
        assert!(
            (480..=540).contains(&profile.client_hello_size_hint),
            "Chrome 120 size hint {} is outside the [480, 540] mimicry envelope",
            profile.client_hello_size_hint
        );
    }

    /// Assert 3: cipher-suite order matches Chrome 120 reference.
    ///
    /// The TLS 1.2 cipher order and TLS 1.3 cipher list are frozen here. Any
    /// reorder or addition is a fingerprint-visible change that DPI can detect.
    #[test]
    fn chrome_120_cipher_suite_order_matches_reference() {
        let profile = lookup_profile("chrome_stable");

        // TLS 1.2 ciphers — must appear in exactly this order (Chrome 120 reference).
        let expected_tls12_ciphers: &[&str] = &[
            "ECDHE-ECDSA-AES128-GCM-SHA256",
            "ECDHE-RSA-AES128-GCM-SHA256",
            "ECDHE-ECDSA-AES256-GCM-SHA384",
            "ECDHE-RSA-AES256-GCM-SHA384",
            "ECDHE-ECDSA-CHACHA20-POLY1305",
            "ECDHE-RSA-CHACHA20-POLY1305",
        ];
        // Normalise whitespace introduced by the multi-line string literal.
        let actual_tls12: Vec<&str> =
            profile.cipher_list_tls12.split(':').map(str::trim).filter(|s| !s.is_empty()).collect();
        assert_eq!(
            actual_tls12, expected_tls12_ciphers,
            "Chrome 120 TLS 1.2 cipher order drifted from frozen reference"
        );

        // TLS 1.3 ciphers — BoringSSL controls the actual wire order, but the
        // declared preference string must match Chrome 120.
        let expected_tls13_ciphers: &[&str] =
            &["TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"];
        let actual_tls13: Vec<&str> =
            profile.ciphersuites_tls13.split(':').map(str::trim).filter(|s| !s.is_empty()).collect();
        assert_eq!(
            actual_tls13, expected_tls13_ciphers,
            "Chrome 120 TLS 1.3 cipher order drifted from frozen reference"
        );

        // Supported groups (curves) — X25519 must be first per Chrome 120.
        let expected_curves: &[&str] = &["X25519MLKEM768", "X25519", "P-256", "P-384"];
        let actual_curves: Vec<&str> = profile.curves.split(':').map(str::trim).filter(|s| !s.is_empty()).collect();
        assert_eq!(actual_curves, expected_curves, "Chrome 120 curves order drifted from frozen reference");
    }

    /// Assert 4: frozen JA4-like fingerprint hash must not change.
    ///
    /// This is the primary regression gate. Any unintentional change to the
    /// profile fields listed above will produce a different SHA-256 and fail
    /// here, forcing an explicit review before the constant is updated.
    #[test]
    fn chrome_120_frozen_fingerprint_hash_unchanged() {
        let actual = compute_profile_fingerprint("chrome_stable");
        assert_eq!(
            actual, CHROME_120_FROZEN_FINGERPRINT,
            "Chrome 120 JA4-like fingerprint hash changed — review the profile diff carefully \
             before updating CHROME_120_FROZEN_FINGERPRINT.\n  got: {actual}\n  want: {CHROME_120_FROZEN_FINGERPRINT}"
        );
    }
}
