//! Cross-layer contract for proxy settings.
//!
//! Walks every manifest under `manifests/proxy_setting/` and asserts that
//! each declared layer file still contains its marker substring. The failure
//! message tells the contributor which file they forgot to update.
//!
//! Wire-id cross-check is intentionally substring-only: there is no central
//! Rust registry of "all proxy settings", so the harness leans on the
//! per-layer markers (proto field, settings section field, mapper line,
//! Rust runtime field, Rust default) rather than a registry lookup.

use feature_contract_harness::{assert_layer_markers, load_family_manifests, locate_repo_root};

#[test]
fn proxy_setting_manifests_pin_their_cross_layer_markers() {
    let repo_root = locate_repo_root();
    let manifests = load_family_manifests("proxy_setting");
    for (_path, manifest) in &manifests {
        assert_layer_markers(&repo_root, manifest);
    }
}

#[test]
fn proxy_setting_manifests_have_a_proto_and_runtime_layer() {
    // A proxy setting that does not appear in both the proto schema and the
    // Rust runtime config is by definition not a cross-layer setting — the
    // harness exists to make that drift loud.
    for (path, manifest) in load_family_manifests("proxy_setting") {
        let layer_ids: Vec<&str> = manifest.layers.iter().map(|layer| layer.id.as_str()).collect();
        assert!(
            layer_ids.iter().any(|id| id.contains("proto")),
            "proxy_setting manifest {} must list a `proto` layer (the AppSettings proto field is the wire root)",
            path.display(),
        );
        assert!(
            layer_ids.iter().any(|id| id.contains("rust")),
            "proxy_setting manifest {} must list at least one `rust_*` layer (the Rust runtime model is the consuming end)",
            path.display(),
        );
    }
}
