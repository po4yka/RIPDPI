//! Sanity check on the harness itself: the manifest tree must be well-formed
//! before any per-family test runs. Catches malformed manifests, name
//! collisions, and an unknown family value with a friendlier message than
//! the family-specific tests would produce.

use std::fs;
use std::path::PathBuf;

use feature_contract_harness::{
    FeatureManifest, KNOWN_FAMILIES, ManifestLayer, assert_layer_markers, collect_all_manifests, manifests_root,
    wire_ids_by_family,
};

fn temp_repo_dir(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("ripdpi-feature-contract-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&path);
    fs::create_dir_all(&path).expect("create temp repo dir");
    path
}

#[test]
fn every_family_directory_has_at_least_one_manifest() {
    // load_family_manifests panics with a useful message when the directory
    // is missing or empty — driving it once per family surfaces those
    // failures up front.
    for family in KNOWN_FAMILIES {
        let manifests = feature_contract_harness::load_family_manifests(family);
        assert!(!manifests.is_empty(), "family `{family}` has no manifests under manifests/{family}/");
    }
}

#[test]
fn manifest_names_are_unique_across_all_families() {
    let mut seen: Vec<String> = Vec::new();
    for (path, manifest) in collect_all_manifests() {
        assert!(
            !seen.contains(&manifest.name),
            "duplicate manifest name `{}` (second occurrence at {}) — names must be unique across families",
            manifest.name,
            path.display(),
        );
        seen.push(manifest.name);
    }
}

#[test]
fn every_manifest_layer_has_a_non_empty_fix_hint() {
    // The whole point of the harness is the failure message; a manifest with
    // an empty hint loses that signal.
    for (path, manifest) in collect_all_manifests() {
        for layer in &manifest.layers {
            assert!(
                !layer.fix_hint.trim().is_empty(),
                "manifest {} layer `{}` has an empty fix_hint — the failure message would tell the contributor nothing",
                path.display(),
                layer.id,
            );
        }
    }
}

#[test]
fn manifests_root_points_at_the_crate_manifest_tree() {
    let root = manifests_root();
    assert!(root.is_absolute(), "manifests_root must be absolute, got {}", root.display());
    assert!(root.is_dir(), "manifests_root must point at an existing directory: {}", root.display());
    assert_eq!(root.file_name().and_then(|name| name.to_str()), Some("manifests"));
    assert!(
        root.join("proxy_setting").join("proxy_listen_port.json").is_file(),
        "manifests_root must resolve from CARGO_MANIFEST_DIR, got {}",
        root.display(),
    );
}

#[test]
fn collect_all_manifests_covers_every_known_family() {
    let all = collect_all_manifests();
    assert!(
        all.len() >= KNOWN_FAMILIES.len(),
        "collect_all_manifests must include at least one manifest per known family"
    );
    for family in KNOWN_FAMILIES {
        assert!(
            all.iter().any(|(_, manifest)| manifest.family == *family),
            "collect_all_manifests omitted family `{family}`"
        );
    }
}

#[test]
fn wire_ids_by_family_covers_the_real_manifest_ids() {
    let ids = wire_ids_by_family();
    assert_eq!(ids.len(), KNOWN_FAMILIES.len(), "wire_ids_by_family must include every known family");
    for family in KNOWN_FAMILIES {
        let family_ids = ids.get(family).unwrap_or_else(|| panic!("wire_ids_by_family omitted `{family}`"));
        let expected: Vec<String> = feature_contract_harness::load_family_manifests(family)
            .into_iter()
            .map(|(_, manifest)| manifest.wire_id)
            .collect();
        assert_eq!(family_ids, &expected, "wire_ids_by_family drifted for `{family}`");
    }
}

#[test]
fn assert_layer_markers_reports_missing_markers() {
    let repo_root = temp_repo_dir("missing-marker");
    let layer_path = repo_root.join("layer.txt");
    fs::write(&layer_path, "present-marker").expect("write layer file");
    let manifest = FeatureManifest {
        schema_version: 1,
        family: "proxy_setting".to_string(),
        name: "sample".to_string(),
        wire_id: "sample_wire".to_string(),
        summary: "sample summary".to_string(),
        layers: vec![ManifestLayer {
            id: "sample_layer".to_string(),
            path: "layer.txt".to_string(),
            marker: "missing-marker".to_string(),
            fix_hint: "restore the marker".to_string(),
        }],
        checklist: vec!["update the sample layer".to_string()],
    };
    let failure = std::panic::catch_unwind(|| assert_layer_markers(&repo_root, &manifest));
    let _ = fs::remove_dir_all(&repo_root);
    assert!(failure.is_err(), "assert_layer_markers must fail when a declared marker is absent");
}
