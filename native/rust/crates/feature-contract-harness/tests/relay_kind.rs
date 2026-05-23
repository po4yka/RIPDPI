//! Cross-layer contract for relay kinds.
//!
//! The relay kind is the most heavily plumbed cross-layer concept in the
//! codebase: proto comment, Kotlin constant, Kotlin RelayKindDescriptor row,
//! Kotlin resolver registration, native wire DTO, Rust transport descriptor,
//! Rust backend builder. The harness pins the layer markers and cross-checks
//! each manifest's `wireId` against the Rust descriptor table.

use feature_contract_harness::{assert_layer_markers, load_family_manifests, locate_repo_root};
use ripdpi_relay_core::relay_transport_descriptor;

#[test]
fn relay_kind_manifests_pin_their_cross_layer_markers() {
    let repo_root = locate_repo_root();
    for (_path, manifest) in load_family_manifests("relay_kind") {
        assert_layer_markers(&repo_root, &manifest);
    }
}

#[test]
fn relay_kind_manifest_wire_ids_resolve_in_the_rust_descriptor_table() {
    // Every manifest's wireId must either resolve through
    // `relay_transport_descriptor` (the public lookup into
    // RELAY_TRANSPORT_REGISTRATIONS) or be the documented passthrough "off"
    // kind, which is intentionally absent from the descriptor table.
    for (path, manifest) in load_family_manifests("relay_kind") {
        let resolved = relay_transport_descriptor(&manifest.wire_id);
        match (manifest.wire_id.as_str(), resolved) {
            ("off", None) => { /* documented exception — see transport_descriptor.rs doc-comment */ }
            (id, Some(descriptor)) => {
                assert_eq!(
                    descriptor.kind_id, id,
                    "descriptor kind_id `{}` differs from manifest wireId `{id}`",
                    descriptor.kind_id,
                );
            }
            (id, None) => {
                panic!(
                    "relay_kind manifest {} declares wireId `{id}` that does not resolve in \
                     RELAY_TRANSPORT_REGISTRATIONS (native/rust/crates/ripdpi-relay-core/src/transport_descriptor.rs).\n\
                     If you just added a manifest, mirror it with a RelayTransportRegistration row.\n\
                     If you intend an unsupported / passthrough kind, document the exception alongside `off`.",
                    path.display(),
                );
            }
        }
    }
}
