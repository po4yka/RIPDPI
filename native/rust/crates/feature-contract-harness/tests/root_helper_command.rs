//! Cross-layer contract for root-helper commands.
//!
//! Each manifest's `wireId` (the `CMD_*` wire string) must resolve via
//! `command_descriptor` against `COMMAND_DESCRIPTORS`, and a well-formed
//! request constructed from the descriptor's own metadata must validate
//! through `validate_request`. The combination catches "added the constant
//! but forgot the descriptor row" and "added the descriptor row but forgot
//! the dispatch handler" via the marker check.

use feature_contract_harness::{assert_layer_markers, load_family_manifests, locate_repo_root};
use ripdpi_root_helper_protocol::{command_descriptor, validate_request, COMMAND_DESCRIPTORS};

#[test]
fn root_helper_command_manifests_pin_their_cross_layer_markers() {
    let repo_root = locate_repo_root();
    for (_path, manifest) in load_family_manifests("root_helper_command") {
        assert_layer_markers(&repo_root, &manifest);
    }
}

#[test]
fn root_helper_command_manifest_wire_ids_resolve_in_the_descriptor_table() {
    let known: Vec<&'static str> = COMMAND_DESCRIPTORS.iter().map(|descriptor| descriptor.command).collect();
    for (path, manifest) in load_family_manifests("root_helper_command") {
        let descriptor = command_descriptor(&manifest.wire_id).unwrap_or_else(|| {
            panic!(
                "root_helper_command manifest {} declares wireId `{}` that does not appear in COMMAND_DESCRIPTORS \
                 (native/rust/crates/ripdpi-root-helper-protocol/src/command_descriptor.rs).\n\
                 Known commands: {known:?}.\n\
                 Add a `CommandDescriptor {{ command: CMD_<NAME>, … }}` row, then also extend ALL_COMMANDS \
                 (and FD_CARRYING_COMMANDS if the command exchanges an fd) in the same file's tests module.",
                path.display(),
                manifest.wire_id,
            )
        });
        // A well-formed request constructed from the descriptor's own
        // metadata must validate — guards against rows whose params_type /
        // fd flags drift away from what validate_request enforces.
        let validation =
            validate_request(descriptor.command, descriptor.requires_inbound_fd, descriptor.params_type.is_some());
        assert!(
            validation.is_ok(),
            "root_helper_command manifest {} wireId `{}` resolves to a descriptor that fails self-validation: {:?}",
            path.display(),
            manifest.wire_id,
            validation.err(),
        );
    }
}
