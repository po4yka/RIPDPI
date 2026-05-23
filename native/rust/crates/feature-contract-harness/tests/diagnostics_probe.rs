//! Cross-layer contract for diagnostics probes.
//!
//! Every manifest's `wireId` must resolve via `descriptor_by_probe_type`
//! against `PROBE_DESCRIPTORS`. That descriptor is already pinned 1:1 to the
//! `SCHEDULED_PROBE_INVENTORY` row by the in-crate drift tests; this harness
//! adds the layer-marker check to catch the case where a contributor adds a
//! probe but forgets the descriptor table OR the scheduled inventory.

use feature_contract_harness::{assert_layer_markers, load_family_manifests, locate_repo_root};
use ripdpi_diagnostics_probes::{descriptor_by_probe_type, PROBE_DESCRIPTORS};

#[test]
fn diagnostics_probe_manifests_pin_their_cross_layer_markers() {
    let repo_root = locate_repo_root();
    for (_path, manifest) in load_family_manifests("diagnostics_probe") {
        assert_layer_markers(&repo_root, &manifest);
    }
}

#[test]
fn diagnostics_probe_manifest_wire_ids_resolve_via_scheduled_probe_type() {
    let known_probe_types: Vec<&'static str> =
        PROBE_DESCRIPTORS.iter().map(|descriptor| descriptor.scheduled_probe_type).collect();
    for (path, manifest) in load_family_manifests("diagnostics_probe") {
        let resolved = descriptor_by_probe_type(&manifest.wire_id);
        assert!(
            resolved.is_some(),
            "diagnostics_probe manifest {} declares wireId `{}` that does not match any \
             scheduled_probe_type in PROBE_DESCRIPTORS \
             (native/rust/crates/ripdpi-diagnostics-probes/src/probe_descriptor.rs).\n\
             Known scheduled_probe_types: {known_probe_types:?}.\n\
             A probe manifest's wireId must equal its scheduled_probe_type (NOT the Probe::id — they can differ; \
             quic_reachability vs quic_probe_offline is the canonical mismatch).",
            path.display(),
            manifest.wire_id,
        );
    }
}
