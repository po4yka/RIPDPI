//! Cross-layer feature-contract harness.
//!
//! The 5 supported families (`proxy_setting`, `relay_kind`, `strategy_step`,
//! `diagnostics_probe`, `root_helper_command`) each declare a list of
//! manifests in `manifests/<family>/<name>.json`. A manifest records which
//! files the feature touches across the settings → Kotlin config → Rust
//! config → runtime descriptor pipeline, along with the exact substring each
//! layer file MUST contain to keep the contract whole.
//!
//! See `README.md` next to this file for the contributor-facing workflow and
//! the manifest schema.
//!
//! The crate has no `main` and ships nothing — every assertion lives in the
//! `tests/` programs, which use [`load_family_manifests`] and
//! [`assert_layer_markers`] to validate each family's manifests against the
//! checked-in tree.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::Deserialize;

/// One layer of the cross-layer touchpoint chain for a feature.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ManifestLayer {
    /// Short layer id (e.g. `"proto"`, `"kotlin_settings_section"`,
    /// `"rust_descriptor"`). Used in failure messages — keep snake_case.
    pub id: String,
    /// Path to the layer file, relative to the **repository root**.
    pub path: String,
    /// Substring that MUST be present in the file. The check is a literal
    /// `contains` — keep markers narrow enough that an unrelated change does
    /// not coincidentally satisfy them, but stable enough not to break on a
    /// trivial rename.
    pub marker: String,
    /// Human-readable hint shown on failure: which file the contributor
    /// forgot to update, and what the missing thing looks like.
    pub fix_hint: String,
}

/// One feature-contract manifest. Shape is intentionally minimal — the goal
/// is to make new manifests cheap to author.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FeatureManifest {
    /// Manifest schema version. Bump when the on-disk shape changes in a
    /// way that breaks existing manifests; additive fields stay on v1.
    pub schema_version: u32,
    /// Family the manifest belongs to: `proxy_setting` | `relay_kind` |
    /// `strategy_step` | `diagnostics_probe` | `root_helper_command`.
    pub family: String,
    /// Manifest name — must equal the file's stem (`foo.json` → `foo`).
    pub name: String,
    /// The wire-stable identifier the feature exposes across layers (e.g.
    /// `"port"` for a proxy setting, `"off"` for a relay kind,
    /// `"http_domcase"` for a strategy step, `"network_environment"` for a
    /// probe, `"send_fake_tcp"` for a root-helper command). Family tests
    /// resolve this against the live registry.
    pub wire_id: String,
    /// One-line description; printed in failure messages.
    pub summary: String,
    /// Cross-layer touchpoints. Empty is rejected — every feature touches at
    /// least one layer.
    pub layers: Vec<ManifestLayer>,
    /// Free-form shotgun-surgery checklist surfaced in failure messages —
    /// the "remember to also edit" list new contributors need.
    #[serde(default)]
    pub checklist: Vec<String>,
}

/// The set of supported family names. Tests assert their family argument
/// matches one of these so a typo in the test program fails loudly.
pub const KNOWN_FAMILIES: &[&str] =
    &["proxy_setting", "relay_kind", "strategy_step", "diagnostics_probe", "root_helper_command"];

/// Walk up from `start` until a directory containing `settings.gradle.kts`
/// is found. Panics if the marker is missing.
///
/// `start` defaults to `CARGO_MANIFEST_DIR` when called from a test under
/// this crate; an override is supported for unit tests of the harness
/// itself.
#[must_use]
pub fn locate_repo_root() -> PathBuf {
    let start = env_path("CARGO_MANIFEST_DIR");
    let mut current = start.as_path();
    loop {
        if current.join("settings.gradle.kts").exists() {
            return current.to_path_buf();
        }
        current = current.parent().unwrap_or_else(|| {
            panic!("repository root (with settings.gradle.kts) not found above {}", start.display())
        });
    }
}

fn env_path(var: &str) -> PathBuf {
    PathBuf::from(std::env::var(var).unwrap_or_else(|_| panic!("{var} must be set when running cargo tests")))
}

/// Directory holding the manifest tree (`native/rust/crates/feature-contract-harness/manifests`).
#[must_use]
pub fn manifests_root() -> PathBuf {
    env_path("CARGO_MANIFEST_DIR").join("manifests")
}

/// Load every manifest under `manifests/<family>/`, sorted by file name for
/// deterministic test output. Panics on parse failure — a malformed manifest
/// is itself a bug the harness should surface.
#[must_use]
pub fn load_family_manifests(family: &str) -> Vec<(PathBuf, FeatureManifest)> {
    assert!(KNOWN_FAMILIES.contains(&family), "unknown family `{family}` — update KNOWN_FAMILIES if you add one");
    let dir = manifests_root().join(family);
    assert!(dir.is_dir(), "manifest directory missing: {}", dir.display());
    let mut out = Vec::new();
    let mut entries: Vec<_> = fs::read_dir(&dir)
        .unwrap_or_else(|err| panic!("read_dir({}) failed: {err}", dir.display()))
        .map(|entry| entry.expect("dir entry").path())
        .filter(|path| path.extension().and_then(|s| s.to_str()) == Some("json"))
        .collect();
    entries.sort();
    for path in entries {
        let raw = fs::read_to_string(&path).unwrap_or_else(|err| panic!("read {}: {err}", path.display()));
        let manifest: FeatureManifest =
            serde_json::from_str(&raw).unwrap_or_else(|err| panic!("parse manifest {}: {err}", path.display()));
        assert_eq!(
            manifest.family,
            family,
            "manifest {} has family `{}`, expected `{family}`",
            path.display(),
            manifest.family
        );
        let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
        assert_eq!(manifest.name, stem, "manifest name {} must match file stem {stem}", manifest.name);
        assert!(
            !manifest.layers.is_empty(),
            "manifest {} has no layers — every feature touches at least one file",
            manifest.name
        );
        out.push((path, manifest));
    }
    assert!(!out.is_empty(), "no manifests found under {} — at least one per family is required", dir.display());
    out
}

/// Verify that every layer file declared by `manifest` exists at the
/// repository root and contains its `marker` substring. On failure the panic
/// message includes the manifest's fix hint plus the full shotgun-surgery
/// checklist — enough for a contributor to find the file they forgot.
pub fn assert_layer_markers(repo_root: &Path, manifest: &FeatureManifest) {
    let mut misses: Vec<String> = Vec::new();
    for layer in &manifest.layers {
        let path = repo_root.join(&layer.path);
        let body = match fs::read_to_string(&path) {
            Ok(body) => body,
            Err(err) => {
                misses.push(format!("[{}] file missing or unreadable: {} ({err})", layer.id, path.display()));
                continue;
            }
        };
        if !body.contains(&layer.marker) {
            misses.push(format!(
                "[{}] {} does not contain marker `{}`\n           fix: {}",
                layer.id, layer.path, layer.marker, layer.fix_hint,
            ));
        }
    }
    if misses.is_empty() {
        return;
    }
    panic!(
        "feature `{}` ({}/{}) drift detected — wireId `{}`:\n{}\n\nShotgun-surgery checklist for `{}`:\n{}\n\n\
         Either update the layer file to restore the marker, or update the manifest at \
         manifests/{}/{}.json if the marker name has changed intentionally.",
        manifest.name,
        manifest.family,
        manifest.summary,
        manifest.wire_id,
        misses.iter().map(|m| format!("  - {m}")).collect::<Vec<_>>().join("\n"),
        manifest.family,
        manifest
            .checklist
            .iter()
            .enumerate()
            .map(|(i, line)| format!("  {}. {line}", i + 1))
            .collect::<Vec<_>>()
            .join("\n"),
        manifest.family,
        manifest.name,
    );
}

/// Cross-family duplicate-name check used by `tests/manifest_self_check.rs`.
///
/// A manifest name must be unique within its family (the file system already
/// guarantees this for free) and must not collide across families either, so
/// failure messages can refer to a feature unambiguously.
#[must_use]
pub fn collect_all_manifests() -> Vec<(PathBuf, FeatureManifest)> {
    let mut all = Vec::new();
    for family in KNOWN_FAMILIES {
        all.extend(load_family_manifests(family));
    }
    all
}

/// Map family → wire ids declared by all manifests of that family. Useful
/// for cross-checking the manifests against the live registry once per
/// family.
#[must_use]
pub fn wire_ids_by_family() -> BTreeMap<&'static str, Vec<String>> {
    let mut out: BTreeMap<&'static str, Vec<String>> = BTreeMap::new();
    for family in KNOWN_FAMILIES {
        let ids: Vec<String> =
            load_family_manifests(family).into_iter().map(|(_, manifest)| manifest.wire_id).collect();
        out.insert(*family, ids);
    }
    out
}
