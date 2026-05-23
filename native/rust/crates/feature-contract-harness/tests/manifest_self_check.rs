//! Sanity check on the harness itself: the manifest tree must be well-formed
//! before any per-family test runs. Catches malformed manifests, name
//! collisions, and an unknown family value with a friendlier message than
//! the family-specific tests would produce.

use feature_contract_harness::{collect_all_manifests, KNOWN_FAMILIES};

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
