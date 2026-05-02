use boring::ssl::{SslVerifyMode, SslVersion};
use serde_json::Value;
use std::fs;
use std::io::Read;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::Path;
use std::thread;
use std::time::Duration;

use crate::{
    build_connector, configure_builder, invariants::validate_profile_config, plan_first_flight, profile,
    profile_catalog, profile_catalog_version, select_rotated_profile_with_set, selected_profile_metadata,
    ProfileInvariantStatus, AVAILABLE_PROFILES,
};

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
